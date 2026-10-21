/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** All store paths pass both configured-directory and store-local containment checks. */
final class StoreFiles {
  static final java.util.concurrent.atomic.AtomicLong headerReads = new java.util.concurrent.atomic.AtomicLong();
  static final long MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
  static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls()
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
  private final Path root;
  private final SecurityValidator security;
  private final SecurityValidator containment = new SecurityValidator();

  private final java.util.function.BiConsumer<Path, Object> published;
  @FunctionalInterface interface Mover { void move(Path from, Path to) throws IOException; }
  @FunctionalInterface interface Pause { void millis(long delay) throws InterruptedException; }
  private final Mover mover;
  private final Pause pause;
  private final boolean windows;
  StoreFiles(Path root, SecurityValidator security) throws IOException {
    this(root, security, (path, value) -> {});
  }
  StoreFiles(Path root, SecurityValidator security, java.util.function.BiConsumer<Path, Object> published) throws IOException {
    this(root, security, published, (from, to) -> Files.move(from, to,
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), Thread::sleep, File.separatorChar == '\\');
  }
  StoreFiles(Path root, SecurityValidator security, java.util.function.BiConsumer<Path, Object> published,
      Mover mover, Pause pause, boolean windows) throws IOException {
    this.published = published;
    this.mover = mover; this.pause = pause; this.windows = windows;
    security.validate(root);
    this.root = root.toRealPath();
    this.security = security;
    containment.addAllowedDirectory(this.root);
  }

  private java.util.function.Consumer<String> capturePublicationFailure;
  private java.util.function.Consumer<ReplicaPublications.Event> capturePublication = event -> {};
  private java.time.Clock publicationClock = java.time.Clock.systemUTC();
  @FunctionalInterface interface AnnotationProbe { void before(Path marker) throws IOException; }
  private AnnotationProbe annotationProbe = marker -> {};
  void annotationProbe(AnnotationProbe probe) { annotationProbe = probe; }
  void beforeCaptureAnnotation(Path marker) throws IOException { annotationProbe.before(marker); }
  private java.util.function.Function<Path, ReplicaPublications.Cause> publicationCauses = receipt -> null;
  void publicationCauses(java.util.function.Function<Path, ReplicaPublications.Cause> causes) { publicationCauses = causes; }
  ReplicaPublications.Cause publicationCause(Path receipt) { return publicationCauses.apply(receipt); }
  void recording(java.util.function.Consumer<String> failure) {
    capturePublicationFailure = failure;
  }
  void publicationEvents(java.util.function.Consumer<ReplicaPublications.Event> publication, java.time.Clock clock) {
    capturePublication = publication; publicationClock = clock;
  }
  void recording(java.util.function.Consumer<String> failure,
      java.util.function.Consumer<ReplicaPublications.Event> publication, java.time.Clock clock) {
    capturePublicationFailure = failure; capturePublication = publication; publicationClock = clock;
  }
  private StoreCatalog.PathObserver observer = path -> {};
  void observe(StoreCatalog.PathObserver observer) { this.observer = observer; }

  Path root() { return root; }

  Path check(Path path) throws IOException {
    observer.inspect(path);
    security.validate(path);
    containment.validate(path);
    return path;
  }

  Path resolve(Path parent, String relative) throws IOException {
    if (relative == null || relative.isBlank() || relative.contains("\\") || relative.contains(":")) {
      throw new IOException("Invalid relative manifest path: " + relative);
    }
    var path = Path.of(relative);
    if (path.isAbsolute()) throw new IOException("Absolute manifest path: " + relative);
    for (var part : path) {
      if (part.toString().equals("..") || part.toString().equals(".")) {
        throw new IOException("Traversal in manifest path: " + relative);
      }
    }
    var resolved = check(parent.resolve(path));
    var within = new SecurityValidator();
    within.addAllowedDirectory(parent);
    within.validate(resolved);
    return resolved;
  }

  static String relative(Path parent, Path file) {
    return parent.relativize(file).toString().replace(File.separatorChar, '/');
  }

  static String robotName(String name) {
    if (name == null || !name.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Robot names use only letters, digits, dots, hyphens, and underscores");
    }
    return component(name);
  }

  static String component(String name) {
    if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
        || name.endsWith(".") || name.endsWith(" ")
        || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
        || name.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
      throw new IllegalArgumentException("Not a portable robot or file name: " + name);
    }
    FileNameEncoding.check(name);
    return name;
  }

  <T> T read(Path path, Class<T> type) throws IOException {
    check(path);
    if (type == StoreManifest.Header.class) return type.cast(ReplicaCatalogFiles.read(this, path).header());
    if (Files.size(path) > MAX_MANIFEST_BYTES) throw new IOException("Manifest is too large: " + path);
    try (var reader = Files.newBufferedReader(path)) {
      var value = JSON.fromJson(reader, type);
      if (value == null) throw new IOException("Empty manifest: " + path);
      return value;
    } catch (RuntimeException e) {
      throw new IOException("Invalid manifest " + path + ": " + e.getMessage(), e);
    }
  }

  void write(Path path, Object value) throws IOException {
    write(path, value, false);
  }

  /** Migration forces the prepared bytes before the format marker can expose them. */
  void writeDurable(Path path, Object value) throws IOException { write(path, value, true); }

  private void write(Path path, Object value, boolean durable) throws IOException {
    check(path);
    if (path.equals(root.resolve("store.json")) && value instanceof StoreManifest.Header header)
      value = ReplicaMasters.moved(root, header);
    Files.createDirectories(path.getParent());
    check(path);
    var temporary = Files.createTempFile(path.getParent(), ".manifest-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temporary)) {
        JSON.toJson(value instanceof StoreManifest.Header header ? ReplicaCatalogFiles.store(this, header) : value, writer);
      }
      if (Files.size(temporary) > MAX_MANIFEST_BYTES) {
        throw new IOException("Manifest is too large (maximum " + MAX_MANIFEST_BYTES
            + " bytes); previous manifest retained: " + path);
      }
      if (durable) try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      // A crash leaves the old manifest or the complete new one, never a half-written catalog.
      // A filesystem without atomic rename is refused rather than weakening that guarantee.
      if (capturePublicationFailure == null) ReplicaMasters.prepare(root, this, path, value, temporary);
      else ReplicaMasters.prepareCapture(root, this, path, value, temporary);
      replace(temporary, path);
      if (value instanceof StoreManifest.Header && path.equals(root.resolve("store.json"))) {
        ReplicaCatalogFiles.invalidate(path);
        try { ReplicaCatalogFiles.published(this); }
        catch (IOException cleanup) { org.slf4j.LoggerFactory.getLogger(StoreFiles.class).warn("Catalog is published; old metadata cleanup is pending: {}", cleanup.getMessage()); }
      }
      if (capturePublicationFailure == null) {
        ReplicaMasters.published(root, this, path, value);
        if (value instanceof StoreManifest.Session || value instanceof StoreManifest.LogFile) Files.deleteIfExists(check(root.resolve(ReplicaMasters.PENDING)));
      } else if (value instanceof StoreManifest.Session session) {
        try {
          ReplicaMasters.published(root, this, path, value);
          Files.deleteIfExists(check(path.resolveSibling(ReplicaMasters.CAPTURE_PENDING)));
          capturePublication.accept(new ReplicaPublications.Event(path, null));
        } catch (IOException | RuntimeException publication) {
          var event = ReplicaMasters.captureFailed(root, this, path, session, publication, publicationClock);
          capturePublication.accept(event);
          var cause = event.pending().cause();
          capturePublicationFailure.accept("PUBLICATION_PENDING: " + cause.code() + ": " + cause.message());
        }
      }
      published.accept(path, value);
    } catch (IOException | RuntimeException | Error failure) {
      try { Files.deleteIfExists(temporary); }
      catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /** Catalog parts use the same forced atomic replacement as other store metadata. */
  void writeBytes(Path path, byte[] bytes, boolean durable) throws IOException {
    check(path); Files.createDirectories(path.getParent());
    var temporary = Files.createTempFile(path.getParent(), ".manifest-", ".tmp");
    try {
      Files.write(temporary, bytes);
      if (durable) try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
      replace(temporary, path);
    } finally { Files.deleteIfExists(temporary); }
  }

  /** Windows can deny replacement transiently. Retry only that error, for at most 620 ms
   * of backoff, without deleting the destination or weakening the atomic-move requirement. */
  private void replace(Path temporary, Path path) throws IOException {
    for (int attempt = 0; ; attempt++) {
      try { mover.move(temporary, path); return; }
      catch (AccessDeniedException denied) {
        if (!windows || attempt == 5) throw denied;
        try { pause.millis(20L << attempt); }
        catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          var failure = new InterruptedIOException("Interrupted replacing manifest " + path);
          failure.initCause(interrupted);
          throw failure;
        }
      }
    }
  }

  static String hash(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] block = new byte[65536];
        int size;
        while ((size = input.read(block)) != -1) digest.update(block, 0, size);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("The JDK must provide SHA-256", e);
    }
  }
}

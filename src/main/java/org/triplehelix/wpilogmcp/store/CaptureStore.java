/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.tools.SignalResolver.MetadataRole;

/**
 * Captures use the same store manifests and queue as imports. The optional open_capture field
 * leaves the existing finished-file contract intact: hashes and exact sizes are facts only after
 * close. A Windows rename refusal defers the cosmetic name; the manifest already has the match.
 */
public final class CaptureStore implements CaptureWriter.Observer {
  @FunctionalInterface interface Mover { void move(Path from, Path to) throws IOException; }
  private final LogStore store;
  private final LogManager manager;
  private final Clock clock;
  private final Mover mover;
  private final EnumMap<MetadataRole, Long> times = new EnumMap<>(MetadataRole.class);
  private String event, matchType;
  private Integer matchNumber, teamNumber;
  private long typeOrdinal, number;
  private BiConsumer<Path, Path> moved = (from, to) -> {};

  CaptureStore(LogStore store, LogManager manager, Clock clock) { this(store, manager, clock, Files::move); }
  CaptureStore(LogStore store, LogManager manager, Clock clock, Mover mover) {
    this.store = store; this.manager = manager; this.clock = clock; this.mover = mover;
  }
  public void onMove(BiConsumer<Path, Path> moved) { this.moved = moved; }

  @Override public Path create(String address, Instant start) throws IOException {
    times.clear(); event = null; matchType = null; matchNumber = null; teamNumber = null; typeOrdinal = 0; number = 0;
    return store.capture(io -> {
      // Percent escapes are portable and injective, unlike replacing every IPv6 ':' with '_'.
      String id = "address-" + java.net.URLEncoder.encode(address, java.nio.charset.StandardCharsets.UTF_8);
      var robot = store.root().resolve("robots").resolve(StoreFiles.component(id));
      io.write(robot.resolve("robot.json"), new Robot(id, null, null, null, "stated"));
      var day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(start);
      var name = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(start);
      var parent = io.check(robot.resolve("sessions").resolve(day)); Files.createDirectories(parent);
      var directory = parent.resolve(name);
      int suffix = 2;
      while (true) {
        try { Files.createDirectory(io.check(directory)); break; }
        catch (java.nio.file.FileAlreadyExistsException e) { directory = parent.resolve(name + "_" + suffix++); }
      }
      io.write(directory.resolve("session.json"), new Session(UUID.randomUUID().toString(), start.toString(),
          start.toString(), "pit_clock", null, null, null, null, List.of()));
      return directory.resolve("capture.wpilog");
    });
  }

  @Override public void opened(CaptureWriter.Session session, boolean resumed) throws IOException { update(session); }
  @Override public void flushed(CaptureWriter.Session session) throws IOException { update(session); }
  @Override public void closed(CaptureWriter.Session session) throws IOException { update(session); }

  @Override public void value(CaptureWriter.Session session, EntryInfo entry, ValueFrame frame, WpilogOutput.Written written)
      throws IOException {
    var role = MetadataRole.of(entry.name(), entry.type()).orElse(null);
    if (role == null || role == MetadataRole.SERIAL || role == MetadataRole.COMMENTS) return;
    if (times.containsKey(role) && times.get(role) > frame.timestampUs()) return;
    times.put(role, frame.timestampUs());
    Object value = frame.value();
    switch (role) {
      case EVENT -> { if (value instanceof String s && !s.isBlank()) event = s.strip(); }
      case MATCH_TYPE -> typeOrdinal = ((Number) value).longValue();
      case MATCH_NUMBER -> number = ((Number) value).longValue();
      case TEAM -> { long team = ((Number) value).longValue(); if (team > 0 && team <= Integer.MAX_VALUE) teamNumber = (int) team; }
      default -> { }
    }
    if (typeOrdinal >= 1 && typeOrdinal <= 3 && number > 0 && number <= Integer.MAX_VALUE) {
      matchType = LogDirectory.MatchType.fromOrdinal((int) typeOrdinal).getFriendlyName(); matchNumber = (int) number;
    }
    update(session);
  }

  private void update(CaptureWriter.Session capture) throws IOException {
    Path before = capture.path();
    Path after = store.capture(io -> {
      var directory = before.getParent();
      var path = directory.resolve("session.json");
      var old = io.read(path, Session.class);
      var provenance = new Provenance("captured", null, "capture.wpilog", capture.startedAt().toString(), false);
      var files = old.files().stream().filter(f -> !f.path().equals("capture.wpilog")).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      double min = capture.minTimestampUs() / 1_000_000.0, max = capture.maxTimestampUs() / 1_000_000.0;
      var now = clock.instant();
      OpenCapture open = null;
      if (capture.open()) open = new OpenCapture("capture.wpilog", provenance, Files.size(io.check(before)), min, max);
      else files.add(new LogFile("capture.wpilog", StoreFiles.hash(io.check(before)), Files.size(before), "wpilog",
          provenance, true, min, max, capture.startedAt().toString(), capture.endedAt().toString(), "pit_clock", false, null));
      var session = new Session(old.id(), old.startedAt(), (capture.open() ? now : capture.endedAt()).toString(),
          old.startBasis(), event, matchType, matchNumber, teamNumber, List.copyOf(files), open);
      io.write(path, session); // Facts are visible even if the following rename is refused.
      if (event == null || matchNumber == null || matchType == null) return before;
      String prefix = DateTimeFormatter.ofPattern("HHmmss'Z'").withZone(ZoneOffset.UTC).format(capture.startedAt());
      String code = switch (matchType) { case "Practice" -> "P"; case "Qualification" -> "Q"; default -> "E"; };
      String name = prefix + "_" + event.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + code + matchNumber;
      var target = io.check(directory.resolveSibling(name));
      if (target.equals(directory) || directory.getFileName().toString().matches(java.util.regex.Pattern.quote(name) + "_[0-9]+")) return before;
      int suffix = 2;
      while (Files.exists(target)) target = io.check(directory.resolveSibling(name + "_" + suffix++));
      try (var reservation = LogFileAccess.reserveMove(List.of(directory))) {
        if (!capture.open() && !manager.release(directory).released()) return before;
        try { mover.move(io.check(directory), target); }
        catch (java.nio.file.FileSystemException e) {
          // Windows refuses an open/mapped capture. Keep its facts and retry after close.
          org.slf4j.LoggerFactory.getLogger(CaptureStore.class).debug("Capture rename deferred: {}", e.getMessage());
          return before;
        }
        var header = io.read(store.root().resolve("store.json"), Header.class);
        var moves = new ArrayList<Move>();
        for (var move : header.moves()) {
          var oldTarget = io.resolve(store.root(), move.movedTo());
          moves.add(new Move(move.originalPath(), StoreFiles.relative(store.root(), oldTarget.startsWith(directory)
              ? target.resolve(directory.relativize(oldTarget)) : oldTarget), move.movedAt()));
        }
        var destination = target.resolve("capture.wpilog");
        moves.add(new Move(before.toString(), StoreFiles.relative(store.root(), destination), now.toString()));
        io.write(store.root().resolve("store.json"), new Header(header.formatVersion(), header.createdAt(), header.id(), moves));
        return destination;
      }
    });
    if (!before.equals(after)) { capture.relocate(after); moved.accept(before, after); }
  }
}

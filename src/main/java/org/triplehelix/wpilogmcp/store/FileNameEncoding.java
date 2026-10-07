/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** The native path parser decides representability, including Windows' Unicode paths. */
final class FileNameEncoding {
  private FileNameEncoding() {}
  private static final AtomicBoolean warned = new AtomicBoolean();
  static void check(String name) { check(name, System.getProperty("sun.jnu.encoding", "unknown"), Path::of); }
  static void check(String name, String encoding, Consumer<String> nativePath) {
    // Reject malformed Unicode on every platform, even where the path API accepts it.
    if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(name)) throw refusal(encoding);
    try { nativePath.accept(name); }
    catch (java.nio.file.InvalidPathException invalid) { throw refusal(encoding); }
  }
  private static IllegalArgumentException refusal(String encoding) {
    return new IllegalArgumentException("File name cannot be represented using platform encoding " + encoding
        + "; start the server with a UTF-8 locale (and use a valid Unicode file name)");
  }
  static void warnIfNeeded() {
    warnIfNeeded(System.getProperty("sun.jnu.encoding", "unknown"), warned,
        message -> org.slf4j.LoggerFactory.getLogger(LogStore.class).warn(message));
  }
  static void warnIfNeeded(String encoding, AtomicBoolean once, Consumer<String> output) {
    boolean utf8;
    try { utf8 = java.nio.charset.Charset.forName(encoding).equals(java.nio.charset.StandardCharsets.UTF_8); }
    catch (IllegalArgumentException unknown) { utf8 = false; }
    if (!utf8 && once.compareAndSet(false, true)) output.accept("Platform file name encoding is " + encoding
        + "; non-ASCII file names it cannot represent will be refused; start the server with a UTF-8 locale");
  }
}

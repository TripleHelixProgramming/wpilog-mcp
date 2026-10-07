/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.harness;

import com.google.gson.JsonObject;
import edu.wpi.first.networktables.GenericPublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.PubSubOption;
import edu.wpi.first.util.WPIUtilJNI;
import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import edu.wpi.first.util.datalog.ReplayRecords;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WPILib's reader and native publishers form the second replay path. A file handshake lets the
 * verifier acknowledge each batch after capture has consumed it; fast mode never guesses a sleep
 * long enough for TCP. Pacing uses source timestamps, while the NT clock starts at source time.
 */
final class LogReplay {
  private record Entry(String name, String type, String metadata, int firstValue) {}
  private static final int BATCH = 1024;
  private static final String METADATA = "wpilog_metadata";
  private static int dsUpdates;
  private static final java.security.MessageDigest dsDigest = digest();
  private static java.security.MessageDigest digest() {
    try { return java.security.MessageDigest.getInstance("SHA-256"); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  static void run(String[] args) throws Exception {
    if (args.length < 5) throw new IllegalArgumentException("--replay file control-directory nt4-port shift-us [speed]");
    var path = Path.of(args[1]); var control = Files.createDirectories(Path.of(args[2]));
    int port = Integer.parseInt(args[3]); long shift = Long.parseLong(args[4]);
    double speed = args.length > 5 ? Double.parseDouble(args[5]) : 1;
    if (!Double.isFinite(speed) || speed < 0) throw new IllegalArgumentException("speed");
    try (var channel = FileChannel.open(path); var nt = NetworkTableInstance.create();
         var watcher = control.getFileSystem().newWatchService()) {
      // macOS scans a directory when registering. Register once before publishing ready, so
      // atomic replacements of handshake files cannot disappear during another registration.
      control.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
      write(control.resolve("watch_registrations"), "1");
      var bytes = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN);
      var reader = new DataLogReader(bytes); if (!reader.isValid()) throw new IllegalArgumentException();
      int first = 12 + bytes.getInt(8);
      var entries = new LinkedHashMap<String, Entry>(); var active = new HashMap<Integer, String>();
      long min = Long.MAX_VALUE, max = 0;
      for (int offset = first, end; (end = ReplayRecords.end(reader, offset)) != -1; offset = end) {
        var record = ReplayRecords.at(reader, offset);
        if (record.isStart()) {
          var start = record.getStartData(); active.put(start.entry, start.name);
          var before = entries.putIfAbsent(start.name, new Entry(start.name, start.type, start.metadata, -1));
          if (before != null && !before.type.equals(start.type)) throw new IllegalArgumentException();
        } else if (record.isFinish()) active.remove(record.getFinishEntry());
        else if (!record.isControl()) {
          var name = active.get(record.getEntry()); if (name == null) break;
          var entry = entries.get(name);
          validateStrings(record, entry.type);
          if (entry.firstValue < 0) entries.put(name, new Entry(name, entry.type, entry.metadata, offset));
          min = Math.min(min, record.getTimestamp());
          max = Math.max(max, record.getTimestamp());
        }
      }
      boolean akit = reader.getExtraHeader().equals("AdvantageKit")
          || entries.keySet().stream().anyMatch(n -> n.startsWith("/RealOutputs/"));
      if (min == Long.MAX_VALUE) min = 0;
      WPIUtilJNI.enableMockTime(); WPIUtilJNI.setMockTime(min + shift);
      nt.startServer("", "127.0.0.1", 0, port);
      var publishers = new LinkedHashMap<String, GenericPublisher>(); var aliases = new HashMap<String, GenericPublisher>();
      var counts = new HashMap<String, Integer>();
      entries.values().forEach(e -> counts.merge(topic(e.name, akit), 1, Integer::sum));
      String namespace = "/__wpilog_replay__";
      while (occupied(counts, namespace)) namespace += "_";
      var topics = new HashMap<String, String>(); int ordinal = 0;
      for (var entry : entries.values()) {
        String canonical = topic(entry.name, akit);
        topics.put(entry.name, counts.get(canonical) > 1 ? namespace + "/" + ordinal : canonical); ordinal++;
      }
      var names = new HashSet<>(topics.values());
      var options = new PubSubOption[] {PubSubOption.keepDuplicates(true), PubSubOption.sendAll(true), PubSubOption.periodic(0.001)};
      for (var entry : entries.values()) {
        String type = switch (entry.type) { case "int64" -> "int"; case "int64[]" -> "int[]"; default -> entry.type; };
        String name = topics.get(entry.name); var properties = new JsonObject(); properties.addProperty(METADATA, entry.metadata);
        properties.addProperty("wpilog_entry_name", entry.name);
        publishers.put(entry.name, nt.getTopic(name).genericPublishEx(type, properties.toString(), options));
        String canonical = topic(entry.name, akit); int schema = canonical.indexOf("/.schema/");
        if (isSchema(entry) && schema >= 0 && names.add(canonical.substring(schema))) {
          aliases.put(entry.name, nt.getTopic(canonical.substring(schema)).genericPublishEx(type, "{\"wpilog_replay_schema_alias\":true}", options));
        }
      }
      write(control.resolve("ready"), Integer.toString(publishers.size() + aliases.size()));
      waitFor(watcher, () -> Files.exists(control.resolve("go")));
      long propertiesSent = 0;
      var metadata = new HashMap<String, String>(); entries.values().forEach(e -> metadata.put(e.name, e.metadata));
      long sent = 0, started = System.nanoTime();
      var seeded = new HashSet<Integer>();
      for (var entry : entries.values()) if (isSchema(entry) && entry.firstValue >= 0) {
        var record = ReplayRecords.at(reader, entry.firstValue); seeded.add(entry.firstValue);
        if (aliases.containsKey(entry.name)) { publish(aliases.get(entry.name), entry.type, record, shift); sent++; }
        publish(publishers.get(entry.name), entry.type, record, shift); sent++;
      }
      barrier(nt, control, watcher, sent, propertiesSent);
      active.clear(); var declared = new HashSet<String>(); long elapsed = 0, sinceBarrier = 0;
      for (int offset = first, end; (end = ReplayRecords.end(reader, offset)) != -1; offset = end) {
        var record = ReplayRecords.at(reader, offset);
        if (record.isStart()) {
          var start = record.getStartData(); active.put(start.entry, start.name);
          if (!declared.add(start.name) && !start.metadata.equals(metadata.put(start.name, start.metadata))) {
            barrier(nt, control, watcher, sent, propertiesSent); property(publishers.get(start.name), start.metadata);
            barrier(nt, control, watcher, sent, ++propertiesSent);
          }
        } else if (record.isFinish()) active.remove(record.getFinishEntry());
        else if (record.isSetMetadata()) {
          var update = record.getSetMetadataData(); var name = active.get(update.entry);
          if (name != null && !update.metadata.equals(metadata.put(name, update.metadata))) {
            barrier(nt, control, watcher, sent, propertiesSent); property(publishers.get(name), update.metadata);
            barrier(nt, control, watcher, sent, ++propertiesSent);
          }
        } else if (!record.isControl()) {
          var name = active.get(record.getEntry()); if (name == null) break;
          if (seeded.contains(offset)) continue;
          elapsed = Math.max(elapsed, record.getTimestamp() - min);
          if (speed > 0) {
            long remaining = started + (long) (elapsed * 1000 / speed) - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
          }
          WPIUtilJNI.setMockTime(record.getTimestamp() + shift);
          driveDriverStation(name, record, entries.get(name).type, shift);
          publish(publishers.get(name), entries.get(name).type, record, shift); sent++; sinceBarrier++;
          if (aliases.containsKey(name)) { publish(aliases.get(name), entries.get(name).type, record, shift); sent++; }
          if (sinceBarrier >= BATCH) { barrier(nt, control, watcher, sent, propertiesSent); sinceBarrier = 0; }
        }
      }
      barrier(nt, control, watcher, sent, propertiesSent);
      WPIUtilJNI.setMockTime(max + shift);
      write(control.resolve("ds_updates"), Integer.toString(dsUpdates));
      write(control.resolve("ds_digest"), java.util.HexFormat.of().formatHex(dsDigest.digest()));
      write(control.resolve("done"), Long.toString(sent));
      waitFor(watcher, () -> Files.exists(control.resolve("stop")));
      publishers.values().forEach(GenericPublisher::close); aliases.values().forEach(GenericPublisher::close);
      nt.stopServer();
    }
  }

  private static String topic(String name, boolean akit) {
    if (name.startsWith("NT:")) return name.substring(3);
    return akit ? "/AdvantageKit" + (name.startsWith("/") ? name : "/" + name) : name;
  }
  private static boolean occupied(Map<String, Integer> names, String namespace) {
    return names.keySet().stream().anyMatch(n -> n.startsWith(namespace));
  }
  private static boolean isSchema(Entry entry) { return entry.type.equals("structschema") || entry.type.equals("proto:FileDescriptorProto"); }
  private static void property(GenericPublisher publisher, String metadata) {
    publisher.getTopic().setProperty(METADATA, new com.google.gson.JsonPrimitive(metadata).toString());
  }
  private static void publish(GenericPublisher publisher, String type, DataLogRecord record, long shift) {
    long time = Math.addExact(record.getTimestamp(), shift);
    // NT interprets timestamp zero as "now"; make that now exactly the source timestamp too.
    WPIUtilJNI.setMockTime(time);
    switch (type) {
      case "boolean" -> publisher.setBoolean(record.getBoolean(), time);
      case "int64" -> publisher.setInteger(record.getInteger(), time);
      case "float" -> publisher.setFloat(record.getFloat(), time);
      case "double" -> publisher.setDouble(record.getDouble(), time);
      case "string", "json" -> publisher.setString(record.getString(), time);
      case "boolean[]" -> publisher.setBooleanArray(record.getBooleanArray(), time);
      case "int64[]" -> publisher.setIntegerArray(record.getIntegerArray(), time);
      case "float[]" -> publisher.setFloatArray(record.getFloatArray(), time);
      case "double[]" -> publisher.setDoubleArray(record.getDoubleArray(), time);
      case "string[]" -> publisher.setStringArray(record.getStringArray(), time);
      default -> publisher.setRaw(record.getRaw(), time);
    }
  }

  private static void driveDriverStation(String name, DataLogRecord record, String type, long shift) {
    if (name.startsWith("NT:")) name = name.substring(3);
    if (name.equals("/FMSInfo/FMSControlData")) {
      int flags = integer(record, type);
      DriverStationSim.setEnabled((flags & 1) != 0); DriverStationSim.setAutonomous((flags & 2) != 0);
      DriverStationSim.setTest((flags & 4) != 0); DriverStationSim.setEStop((flags & 8) != 0);
      DriverStationSim.setFmsAttached((flags & 16) != 0); DriverStationSim.setDsAttached((flags & 32) != 0);
    } else switch (name) {
      case "/DriverStation/Enabled", "DS:enabled" -> DriverStationSim.setEnabled(record.getBoolean());
      case "/DriverStation/Autonomous", "DS:autonomous" -> DriverStationSim.setAutonomous(record.getBoolean());
      case "/DriverStation/Test", "DS:test" -> DriverStationSim.setTest(record.getBoolean());
      case "/DriverStation/EmergencyStop", "DS:estop" -> DriverStationSim.setEStop(record.getBoolean());
      case "/DriverStation/FMSAttached" -> DriverStationSim.setFmsAttached(record.getBoolean());
      case "/DriverStation/DSAttached" -> DriverStationSim.setDsAttached(record.getBoolean());
      case "/DriverStation/EventName", "/FMSInfo/EventName" -> DriverStationSim.setEventName(record.getString());
      case "/DriverStation/MatchNumber", "/FMSInfo/MatchNumber" -> DriverStationSim.setMatchNumber(integer(record, type));
      case "/DriverStation/MatchType", "/FMSInfo/MatchType" -> {
        int value = integer(record, type); if (value >= 0 && value < DriverStation.MatchType.values().length) DriverStationSim.setMatchType(DriverStation.MatchType.values()[value]);
      }
      default -> { return; }
    }
    // HAL's new-packet notification sets DS attached unconditionally. A replay can record
    // disconnection too, so retain that recorded field across the notification.
    boolean attached = DriverStationSim.getDsAttached();
    DriverStationSim.notifyNewData();
    DriverStationSim.setDsAttached(attached);
    DriverStation.refreshData();
    dsUpdates++;
    int state = (DriverStationSim.getEnabled() ? 1 : 0) | (DriverStationSim.getAutonomous() ? 2 : 0)
        | (DriverStationSim.getTest() ? 4 : 0) | (DriverStationSim.getEStop() ? 8 : 0)
        | (DriverStationSim.getFmsAttached() ? 16 : 0) | (DriverStationSim.getDsAttached() ? 32 : 0);
    dsDigest.update(ByteBuffer.allocate(12).putLong(record.getTimestamp() + shift).putInt(state).array());
    byte[] event = DriverStation.getEventName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    dsDigest.update(ByteBuffer.allocate(12).putInt(DriverStation.getMatchNumber())
        .putInt(DriverStation.getMatchType().ordinal()).putInt(event.length).array());
    dsDigest.update(event);
  }

  private static int integer(DataLogRecord record, String type) {
    return switch (type) {
      case "int64" -> (int) record.getInteger();
      case "float" -> (int) record.getFloat();
      case "double" -> (int) record.getDouble();
      default -> throw new IllegalArgumentException("Driver Station number has a nonnumeric type");
    };
  }

  private static void validateStrings(DataLogRecord record, String type) throws java.nio.charset.CharacterCodingException {
    if (!type.equals("string") && !type.equals("json") && !type.equals("string[]")) return;
    var bytes = ByteBuffer.wrap(record.getRaw()).order(ByteOrder.LITTLE_ENDIAN);
    var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
    if (!type.equals("string[]")) { decoder.decode(bytes); return; }
    int count = bytes.getInt();
    for (int i = 0; i < count; i++) {
      int size = bytes.getInt(); decoder.decode(bytes.slice(bytes.position(), size)); bytes.position(bytes.position() + size);
    }
  }

  private static void barrier(NetworkTableInstance nt, Path control, java.nio.file.WatchService watcher, long sent, long properties) throws Exception {
    nt.flush(); write(control.resolve("sent"), Long.toString(sent));
    write(control.resolve("sent_properties"), Long.toString(properties));
    waitFor(watcher, () -> Files.exists(control.resolve("received")) && Long.parseLong(Files.readString(control.resolve("received")).strip()) >= sent
        && Files.exists(control.resolve("received_properties"))
        && Long.parseLong(Files.readString(control.resolve("received_properties")).strip()) >= properties);
  }
  @FunctionalInterface private interface Ready { boolean get() throws Exception; }
  private static void waitFor(java.nio.file.WatchService watcher, Ready ready) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!ready.get()) {
      long left = deadline - System.nanoTime(); if (left <= 0) throw new IllegalStateException("Replay handshake timeout");
      var key = watcher.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
      if (key != null) { key.pollEvents(); key.reset(); }
    }
  }
  private static void write(Path path, String value) throws Exception {
    var temporary = path.resolveSibling(path.getFileName() + ".tmp"); Files.writeString(temporary, value);
    Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }
}

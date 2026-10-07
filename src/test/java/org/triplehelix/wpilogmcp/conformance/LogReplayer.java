/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import org.triplehelix.wpilogmcp.nt4.NtType;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

/**
 * A real log is both the robot script and the independent oracle. The receive barrier bounds
 * fast replay by what capture has accepted, not merely by what the gateway has queued to send.
 * WPILOG metadata has no NT4 field; a named custom property transports it without interpreting it.
 */
final class LogReplayer {
  static final String METADATA = "wpilog_metadata";
  static final String ENTRY_NAME = "wpilog_entry_name";
  static final String SCHEMA_ALIAS = "wpilog_replay_schema_alias";
  static final int BATCH_RECORDS = 1024;
  private final ReplaySource source;
  private final Nt4Gateway gateway;
  private final LongConsumer robotTime;
  private LongConsumer receivedProperties;
  private final Map<ReplaySource.Entry, String> metadata = new HashMap<>();
  private long propertiesSent;
  private final Map<ReplaySource.Entry, String> aliases = new HashMap<>();
  private long sent;

  LogReplayer(ReplaySource source, Nt4Gateway gateway, LongConsumer robotTime) {
    this(source, gateway, robotTime, ignored -> {});
  }
  LogReplayer(ReplaySource source, Nt4Gateway gateway, LongConsumer robotTime, LongConsumer receivedProperties) {
    this.source = source; this.gateway = gateway; this.robotTime = robotTime; this.receivedProperties = receivedProperties;
    source.entries.values().forEach(e -> metadata.put(e, e.metadata));
    var available = new HashSet<String>(); source.entries.values().forEach(e -> available.add(source.topic(e)));
    for (var entry : source.entries.values()) {
      String alias = source.schemaAlias(entry);
      if (alias != null && available.add(alias)) aliases.put(entry, alias);
    }
  }

  int topicCount() { return source.entries.size() + aliases.size(); }
  long sent() { return sent; }

  void unannounce() {
    source.entries.values().forEach(e -> gateway.unannounce(source.topic(e)).join());
    aliases.values().forEach(name -> gateway.unannounce(name).join());
  }

  void announce() {
    for (var entry : source.entries.values()) {
      var props = new JsonObject(); props.addProperty(METADATA, entry.metadata);
      props.addProperty(ENTRY_NAME, entry.name);
      gateway.announce(source.topic(entry), ReplaySource.ntType(entry.type), props).join();
      if (aliases.containsKey(entry)) {
        var alias = new JsonObject(); alias.addProperty(SCHEMA_ALIAS, true);
        gateway.announce(aliases.get(entry), ReplaySource.ntType(entry.type), alias).join();
      }
    }
  }

  void replay(long shiftUs, double speed, LongConsumer awaitElapsedUs, LongConsumer receivedThrough) {
    if (!Double.isFinite(speed) || speed < 0) throw new IllegalArgumentException("Replay speed");
    if (!source.limitations.isEmpty()) throw new IllegalArgumentException("WPILOG topic declarations cannot be replayed losslessly: " + source.path);
    sent = 0;
    var seeded = new HashSet<Integer>();
    for (var entry : source.entries.values()) if (entry.schema() && entry.count > 0) {
      var record = source.reader.at(entry.offset(0)); seeded.add(record.offset());
      publish(entry, record, shiftUs);
    }
    receivedThrough.accept(sent);
    var active = new HashMap<Long, ReplaySource.Entry>();
    var announced = new HashSet<ReplaySource.Entry>();
    long elapsed = 0;
    for (int at = source.reader.first; ; ) {
      var record = source.reader.at(at); if (record == null) break; at = record.end();
      if (record.id() == 0) {
        if (source.reader.control(record) == 0) {
          var entry = source.declarations.get(record.offset()); if (entry == null) break;
          active.put(source.reader.controlId(record), entry);
          if (!announced.add(entry)) {
            receivedThrough.accept(sent);
            metadata(entry, source.reader.start(record).metadata());
          }
        } else if (source.reader.control(record) == 1) active.remove(source.reader.controlId(record));
        else if (source.reader.control(record) == 2) {
          var entry = active.get(source.reader.controlId(record));
          if (entry != null) { receivedThrough.accept(sent); metadata(entry, source.reader.metadata(record)); }
        }
        continue;
      }
      var entry = active.get(record.id()); if (entry == null) break;
      if (seeded.contains(record.offset())) continue;
      elapsed = Math.max(elapsed, record.timestampUs() - source.minUs);
      if (speed > 0) awaitElapsedUs.accept((long) (elapsed / speed));
      publish(entry, record, shiftUs);
      if (sent % BATCH_RECORDS == 0) receivedThrough.accept(sent);
    }
    receivedThrough.accept(sent);
  }

  void replay(long shiftUs, double speed, LongConsumer awaitElapsedUs, LongConsumer receivedThrough, LongConsumer controls) {
    receivedProperties = controls;
    replay(shiftUs, speed, awaitElapsedUs, receivedThrough);
  }

  private void metadata(ReplaySource.Entry entry, String text) {
    if (text.equals(metadata.put(entry, text))) return;
    var properties = new JsonObject(); properties.addProperty(METADATA, text);
    gateway.properties(source.topic(entry), properties).join();
    receivedProperties.accept(++propertiesSent);
  }

  private void publish(ReplaySource.Entry entry, IndependentLog.Records.Record record, long shiftUs) {
    long time = Math.addExact(record.timestampUs(), shiftUs); robotTime.accept(time);
    Object value = ReplaySource.value(entry.type, source.reader.payload(record));
    int code = NtType.fromNt4(ReplaySource.ntType(entry.type)).code();
    if (aliases.containsKey(entry)) { gateway.value(aliases.get(entry), time, code, value).join(); sent++; }
    gateway.value(source.topic(entry), time, code, value); sent++;
  }
}

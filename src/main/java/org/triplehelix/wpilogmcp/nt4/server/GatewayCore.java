/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;

/**
 * Pure gateway state transitions. Callers supply time and send the returned deliveries after the
 * lock is released. Overlapping subscriptions produce one stream: any matching all subscription
 * keeps every change; the minimum requested period drives one client sweep (permitted by NT4).
 * Client publications are isolated acknowledgement sinks and cannot change the upstream table.
 */
public final class GatewayCore {
  public record Delivery(String client, ControlMessage control, List<ValueFrame> values) {
    public Delivery { values = List.copyOf(values); }
  }
  private record Pending(long sequence, ValueFrame frame, int bytes) {}
  private static final class Client {
    final Map<Integer, Subscribe> subscriptions = new LinkedHashMap<>();
    final Set<Integer> announced = new HashSet<>();
    final Map<Integer, Announce> publishers = new HashMap<>();
    final Map<Integer, List<Pending>> pending = new HashMap<>();
    long dueUs;
    long pendingBytes;
    int pendingValues;
    boolean warned;
  }

  private final Map<String, Client> clients = new LinkedHashMap<>();
  private final Map<String, Announce> topics = new LinkedHashMap<>();
  private final Map<Integer, ValueFrame> latest = new HashMap<>();
  private int nextId;
  private long sequence;
  private final long maxPendingBytes;
  private final int maxPendingValues;

  public GatewayCore() { this(32L << 20, 65536); }
  /** Explicit limits let tests exercise a long-period subscriber without allocating megabytes. */
  GatewayCore(long maxPendingBytes, int maxPendingValues) {
    if (maxPendingBytes <= 0 || maxPendingValues <= 0) throw new IllegalArgumentException("Positive gateway queue limits required");
    this.maxPendingBytes = maxPendingBytes; this.maxPendingValues = maxPendingValues;
  }

  public synchronized void connect(String client) { clients.put(client, new Client()); }
  public synchronized void disconnect(String client) { clients.remove(client); }
  public synchronized int clientCount() { return clients.size(); }
  public synchronized Map<String, Announce> topics() { return Map.copyOf(topics); }

  /** Lets the socket adapter log a write refusal exactly once per connection. */
  public synchronized boolean firstWrite(String client) {
    var state = clients.get(client);
    if (state == null || state.warned) return false;
    state.warned = true;
    return true;
  }

  public synchronized List<Delivery> announce(String name, String type, JsonObject properties) {
    if (topics.containsKey(name)) throw new IllegalArgumentException("Topic already exists: " + name);
    var topic = new Announce(name, nextId++, type, null, properties);
    topics.put(name, topic);
    var out = new ArrayList<Delivery>();
    clients.forEach((id, c) -> {
      if (c.subscriptions.values().stream().anyMatch(s -> s.matches(name))) announce(out, id, c, topic);
    });
    return List.copyOf(out);
  }

  public synchronized List<Delivery> properties(String name, JsonObject update) {
    var old = topics.get(name);
    if (old == null) return List.of();
    var changed = old.withUpdate(update);
    topics.put(name, changed);
    if (!changed.cached()) latest.remove(changed.id());
    return clients.entrySet().stream().filter(e -> e.getValue().announced.contains(changed.id()))
        .map(e -> control(e.getKey(), new Properties(name, null, update))).toList();
  }

  public synchronized List<Delivery> unannounce(String name) {
    var topic = topics.remove(name);
    if (topic == null) return List.of();
    latest.remove(topic.id());
    var out = new ArrayList<Delivery>();
    clients.forEach((id, c) -> {
      // Flush preceding changes before the deletion, even when its period has not elapsed.
      flush(out, id, c);
      if (c.announced.remove(topic.id())) out.add(control(id, new Unannounce(name, topic.id())));
    });
    return List.copyOf(out);
  }

  /** Returns subscribers removed for exceeding their period's bounded pending queue. */
  public synchronized List<String> value(String name, long timestampUs, int code, Object value) {
    var topic = topics.get(name);
    if (topic == null) throw new IllegalArgumentException("Topic not announced: " + name);
    var frame = new ValueFrame(topic.id(), timestampUs, code, value);
    var old = latest.get(topic.id());
    if (topic.cached() && (old == null || timestampUs >= old.timestampUs())) latest.put(topic.id(), frame);
    long order = sequence++;
    int bytes = -1;
    var dropped = new ArrayList<String>();
    for (var iterator = clients.entrySet().iterator(); iterator.hasNext();) {
      var entry = iterator.next(); var c = entry.getValue();
      var matching = valueSubscriptions(c, name);
      if (matching.isEmpty()) continue;
      if (bytes < 0) bytes = frame.encode().length;
      var queue = c.pending.computeIfAbsent(topic.id(), ignored -> new ArrayList<>());
      if (matching.stream().noneMatch(Subscribe::all)) {
        for (var previous : queue) c.pendingBytes -= previous.bytes();
        c.pendingValues -= queue.size(); queue.clear();
      }
      if (c.pendingBytes + bytes > maxPendingBytes || c.pendingValues >= maxPendingValues) {
        dropped.add(entry.getKey()); iterator.remove(); continue;
      }
      queue.add(new Pending(order, frame, bytes)); c.pendingBytes += bytes; c.pendingValues++;
    }
    return List.copyOf(dropped);
  }

  /** A disconnect ends the visible session even if capture later resumes its file. */
  public synchronized List<Delivery> endSession() {
    var deliveries = new ArrayList<Delivery>();
    for (String name : List.copyOf(topics.keySet())) deliveries.addAll(unannounce(name));
    return List.copyOf(deliveries);
  }

  public synchronized List<Delivery> tick(long nowUs) {
    var out = new ArrayList<Delivery>();
    clients.forEach((id, c) -> {
      if (nowUs >= c.dueUs) {
        flush(out, id, c);
        c.dueUs = nowUs + periodUs(c);
      }
    });
    return List.copyOf(out);
  }

  public synchronized List<Delivery> receive(String client, ControlMessage message, long nowUs) {
    var c = clients.get(client);
    if (c == null) return List.of();
    var out = new ArrayList<Delivery>();
    if (message instanceof Subscribe incoming) {
      var previouslyReceiving = new HashSet<Integer>();
      for (var topic : topics.values()) {
        if (!valueSubscriptions(c, topic.name()).isEmpty()) previouslyReceiving.add(topic.id());
      }
      var options = new JsonObject();
      var old = c.subscriptions.get(incoming.subuid());
      if (old != null) options = old.options();
      var merged = options;
      incoming.options().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
      c.subscriptions.put(incoming.subuid(), new Subscribe(incoming.topics(), incoming.subuid(), merged));
      prunePending(c);
      for (var topic : topics.values()) {
        if (c.subscriptions.values().stream().noneMatch(s -> s.matches(topic.name()))) continue;
        announce(out, client, c, topic);
        var retained = latest.get(topic.id());
        if (retained != null && !previouslyReceiving.contains(topic.id())
            && !valueSubscriptions(c, topic.name()).isEmpty()) {
          out.add(new Delivery(client, null, List.of(retained)));
        }
      }
      c.dueUs = nowUs + periodUs(c);
    } else if (message instanceof Unsubscribe u) {
      c.subscriptions.remove(u.subuid());
      prunePending(c);
    } else if (message instanceof Publish p) {
      var topic = topics.get(p.name());
      if (topic == null) {
        topic = c.publishers.values().stream().filter(t -> t.name().equals(p.name())).findFirst()
            .orElseGet(() -> new Announce(p.name(), nextId++, p.type(), null, new JsonObject()));
      }
      c.publishers.put(p.pubuid(), topic);
      c.announced.add(topic.id());
      out.add(control(client, new Announce(topic.name(), topic.id(), topic.type(), p.pubuid(), topic.properties())));
    } else if (message instanceof Unpublish u) {
      var topic = c.publishers.remove(u.pubuid());
      if (topic != null && !topics.containsKey(topic.name())
          && c.publishers.values().stream().noneMatch(t -> t.id() == topic.id())) {
        c.announced.remove(topic.id());
        out.add(control(client, new Unannounce(topic.name(), topic.id())));
      }
    } else if (message instanceof SetProperties p) {
      var topic = topics.get(p.name());
      if (topic == null) topic = c.publishers.values().stream().filter(t -> t.name().equals(p.name())).findFirst().orElse(null);
      if (topic != null) {
        // NT4 has no read-only refusal message. Acknowledge the actual unchanged properties,
        // including null for absent keys, so the peer never believes an ignored write took effect.
        var actual = topic.properties();
        var answer = new JsonObject();
        p.update().keySet().forEach(k -> answer.add(k, actual.has(k) ? actual.get(k) : JsonNull.INSTANCE));
        out.add(control(client, new Properties(p.name(), true, answer)));
      }
    }
    return List.copyOf(out);
  }

  public synchronized List<Delivery> receive(String client, ValueFrame value, long serverNowUs) {
    if (!clients.containsKey(client) || value.topicId() != -1) return List.of();
    return List.of(new Delivery(client, null,
        List.of(new ValueFrame(-1, serverNowUs, value.typeCode(), value.value()))));
  }

  private void prunePending(Client c) {
    for (var topic : topics.values()) {
      var matching = valueSubscriptions(c, topic.name());
      if (matching.isEmpty()) c.pending.remove(topic.id());
      else if (matching.stream().noneMatch(Subscribe::all)) {
        var queued = c.pending.get(topic.id());
        if (queued != null && queued.size() > 1) {
          c.pending.put(topic.id(), new ArrayList<>(List.of(queued.get(queued.size() - 1))));
        }
      }
    }
    c.pendingBytes = c.pending.values().stream().flatMap(List::stream).mapToLong(Pending::bytes).sum();
    c.pendingValues = c.pending.values().stream().mapToInt(List::size).sum();
  }

  private static List<Subscribe> valueSubscriptions(Client c, String name) {
    return c.subscriptions.values().stream().filter(s -> !s.topicsOnly() && s.matches(name)).toList();
  }

  private static long periodUs(Client c) {
    return Math.max(1, (long) (c.subscriptions.values().stream().filter(s -> !s.topicsOnly())
        .mapToDouble(Subscribe::periodic).min().orElse(0.1) * 1_000_000));
  }

  private static void flush(List<Delivery> out, String id, Client c) {
    var frames = c.pending.values().stream().flatMap(List::stream)
        .sorted(Comparator.comparingLong(Pending::sequence)).map(Pending::frame).toList();
    if (!frames.isEmpty()) out.add(new Delivery(id, null, frames));
    c.pending.clear();
    c.pendingBytes = 0; c.pendingValues = 0;
  }

  private static void announce(List<Delivery> out, String id, Client c, Announce topic) {
    if (c.announced.add(topic.id())) out.add(control(id, topic));
  }

  private static Delivery control(String client, ControlMessage message) {
    return new Delivery(client, message, List.of());
  }
}

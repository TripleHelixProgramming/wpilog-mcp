/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.SampleBatch;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;

/** The Stage 3 continuation engine, above the shared native reader. There is no timestamp
 * continuation, decoder, acquisition thread or transport here. A source supplies independently
 * owned fixed prefixes of ONE proven recording; catalog admission and alias proof stay with it.
 *
 * <p>Only a request carrying a completed page's next cursor acknowledges that page. Until then
 * the exact encoded page is retained. A failed write, filtered record or malformed value cannot
 * advance past unseen records. Historical windows apply to bootstrap; subsequent deltas include
 * every newly published ordinal, including late records outside that historical window.
 *
 * <p>The native follow route owns lease expiry and wait admission; the source owns catalog and
 * permission proof. The client broker is a separate Stage 3 integration gate. No caller may invoke
 * prefix acquisition or decoding on the recorder loop. One next call owns a bounded batch; close
 * retires admission immediately and releases its prefix when that admitted batch finishes.
 */
public final class OrdinalFollow implements AutoCloseable {
  public static final int MAX_RECORDS = 4096, MAX_PAGE_BYTES = 4 << 20;
  private static final int TURN_RECORDS = 256;

  /** Identity/generation come from the source owner, never a filename or inferred clock. */
  public record Member(String id, long generation, ReadSnapshot snapshot, String contentSha256, String writerIdentity) {
    public Member(String id, long generation, ReadSnapshot snapshot) { this(id, generation, snapshot, null, null); }
    public Member(String id, long generation, ReadSnapshot snapshot, String contentSha256) { this(id, generation, snapshot, contentSha256, null); }
    public Member {
      requireId(id); if (generation < 0) throw new IllegalArgumentException("Negative member generation");
      Objects.requireNonNull(snapshot);
      if (writerIdentity != null) requireId(writerIdentity);
      if (contentSha256 != null && !contentSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid final member hash");
    }
  }

  /** Ownership of every member snapshot transfers to the engine on a successful open. */
  public record Prefix(String recordingId, long catalogRevision, boolean finalized, List<Member> members)
      implements AutoCloseable {
    public Prefix {
      requireId(recordingId); members = List.copyOf(members);
      if (catalogRevision < 0 || members.isEmpty() || members.size() > 64)
        throw new IllegalArgumentException("A prefix needs a nonnegative revision and 1..64 members");
      var ids = new HashSet<String>();
      for (var member : members) if (!ids.add(member.id())) throw new IllegalArgumentException("Duplicate member identity");
    }
    @Override public void close() throws IOException {
      IOException failure = null;
      for (var member : members) try { member.snapshot().close(); }
      catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
      if (failure != null) throw failure;
    }
  }
  @FunctionalInterface public interface Source {
    Prefix open() throws IOException;
    /** Retained bytes do not grant access after a directory or connection is revoked. */
    default void authorize() throws IOException {}
    /** Called only after a frontier is acknowledged, outside all loops and monitors. False
     * means a bounded idle timeout, not permission to reopen the same prefix speculatively. */
    default boolean awaitChange(java.time.Duration wait, java.util.function.BooleanSupplier cancelled) throws IOException { return true; }
  }

  /** Shared across follows, including pages still being written after their cache was retired.
   * Reserve two maximum frames during serialization, then retain only the final encoded size.
   * Native index/decoding memory is owned and bounded by the native reader, not this byte count. */
  public static final class Budget {
    private final long limit;
    private final AtomicLong held = new AtomicLong();
    public Budget(long limit) {
      if (limit < 0) throw new IllegalArgumentException("Negative follow byte budget");
      this.limit = limit;
    }
    public long heldBytes() { return held.get(); }
    private void reserve(long bytes) throws ReadException {
      long old;
      do {
        old = held.get();
        if (bytes > limit - old) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
            "Follow pages exceed the shared " + limit + " byte budget; finish or close another read, then retry this cursor", bytes <= limit);
      } while (!held.compareAndSet(old, old + bytes));
    }
    private void release(long bytes) { held.addAndGet(-bytes); }
  }

  private static final class Buffer {
    final byte[] bytes;
    final Budget budget;
    final AtomicInteger references = new AtomicInteger(1); // retry cache owns the first reference
    Buffer(byte[] bytes, Budget budget) { this.bytes = bytes; this.budget = budget; }
    void release() { if (references.decrementAndGet() == 0) budget.release(bytes.length); }
  }

  /** One admitted response. Close it after success OR disconnect; the retry cache keeps its own
   * reference. Read-only buffers and all derived views are valid only for this handle's lifetime. */
  public static final class Page implements AutoCloseable {
    private final Buffer buffer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Page(Buffer buffer) { this.buffer = buffer; buffer.references.incrementAndGet(); }
    public ByteBuffer payload() {
      if (closed.get()) throw new IllegalStateException("Follow page is closed");
      return ByteBuffer.wrap(buffer.bytes).asReadOnlyBuffer();
    }
    public void writeTo(OutputStream output) throws IOException {
      var bytes = payload(); var chunk = new byte[Math.min(8192, bytes.remaining())];
      while (bytes.hasRemaining()) {
        if (closed.get()) throw new ReadException(ReadProblem.Code.CLOSED, "Follow response was closed");
        int count = Math.min(chunk.length, bytes.remaining()); bytes.get(chunk, 0, count); output.write(chunk, 0, count);
      }
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) buffer.release(); }
  }

  private record Selection(String entry, String field) {}
  private record Key(String member, String entry, String field) {}
  private record Turn(Member member, ReadQuery query) {
    Key key() { return new Key(member.id(), query.entry(), query.field()); }
  }
  private record Pending(String input, String output, Buffer buffer, Map<Key, Integer> progress, boolean complete, Key turn) {}
  private record EntryProof(int id, String type, long count) {}
  private record MemberProof(long generation, String hash, String writerIdentity, Map<String, EntryProof> entries) {}
  private record FrontierProof(String recording, long revision, boolean finalized, Map<String, MemberProof> members) {
    static FrontierProof of(Prefix prefix) {
      var members = new LinkedHashMap<String, MemberProof>();
      for (var member : prefix.members()) {
        var entries = new LinkedHashMap<String, EntryProof>();
        for (var entry : member.snapshot().entries().values()) entries.put(entry.name(),
            new EntryProof(entry.id(), entry.type(), member.snapshot().sampleCount(entry.name())));
        members.put(member.id(), new MemberProof(member.generation(), member.contentSha256(), member.writerIdentity(), Map.copyOf(entries)));
      }
      return new FrontierProof(prefix.recordingId(), prefix.catalogRevision(), prefix.finalized(), Map.copyOf(members));
    }
  }
  private final Source source;
  private final List<ReadQuery> queries;
  private final String epoch, id = UUID.randomUUID().toString(), initial = UUID.randomUUID().toString();
  private final Budget budget;
  private final int pageRecords, pageBytes;
  private final AtomicBoolean busy = new AtomicBoolean(), closed = new AtomicBoolean();
  private Prefix prefix;
  private FrontierProof proof;
  private JsonObject description;
  private String prefixId, cursor = initial;
  private Map<Key, Integer> progress = Map.of();
  private Pending pending;
  private Key turn;
  private boolean bootstrap = true, complete, described, ended;

  public OrdinalFollow(String epoch, Source source, List<ReadQuery> queries, Budget budget) throws IOException {
    this(epoch, source, queries, budget, MAX_RECORDS, MAX_PAGE_BYTES);
  }
  public OrdinalFollow(String epoch, Source source, List<ReadQuery> queries, Budget budget,
      int pageRecords, int pageBytes) throws IOException {
    requireId(epoch); this.epoch = epoch; this.source = Objects.requireNonNull(source);
    this.queries = List.copyOf(queries); this.budget = Objects.requireNonNull(budget);
    if (queries.isEmpty() || queries.size() > 256 || pageRecords < 1 || pageRecords > MAX_RECORDS
        || pageBytes < 1024 || pageBytes > MAX_PAGE_BYTES)
      throw new IllegalArgumentException("Follow limits: 1..256 entries, 1..4096 records, 1024..4194304 bytes");
    var names = new HashSet<Selection>();
    for (var query : queries) if (!names.add(new Selection(query.entry(), query.field())) || query.firstOrdinal() != 0
        || query.order() != ReadQuery.Order.FILE || query.reduction() != null)
      throw new IllegalArgumentException("Follow queries need distinct entry/field pairs, file order, and no initial ordinal or reduction");
    this.pageRecords = pageRecords; this.pageBytes = pageBytes;
    replacePrefix(); // Fix P0 before a caller can request or lose the first history page.
  }

  public String initialCursor() { return initial; }
  public String id() { return id; }

  /** Reusing input reproduces identical bytes. Supplying next_cursor commits the preceding
   * page, then starts another bounded page. Unknown/old/foreign cursors never restart silently. */
  public Page next(String acknowledgement) throws IOException {
    return next(acknowledgement, java.time.Duration.ZERO).orElseThrow();
  }

  /** A timeout acknowledges the preceding page but returns no new cursor. Retrying that same
   * acknowledgement waits again; an unacknowledged page is always returned without waiting. */
  public java.util.Optional<Page> next(String acknowledgement, java.time.Duration wait) throws IOException {
    if (wait.isNegative() || wait.compareTo(java.time.Duration.ofSeconds(25)) > 0)
      throw new IllegalArgumentException("Follow wait must be between zero and 25 seconds");
    if (!busy.compareAndSet(false, true)) throw new ReadException(ReadProblem.Code.BUSY, "Another follow page is being prepared");
    try {
      checkOpen();
      source.authorize();
      if (pending != null && pending.input().equals(acknowledgement)) return java.util.Optional.of(new Page(pending.buffer()));
      if (pending != null && pending.output().equals(acknowledgement)) {
        progress = pending.progress(); complete = pending.complete(); cursor = pending.output(); described = true;
        turn = pending.turn();
        pending.buffer().release(); pending = null;
        if (complete) {
          ended = proof.finalized();
          var retired = prefix; prefix = null;
          if (ended) { proof = null; description = null; progress = Map.of(); turn = null; }
          if (retired != null) retired.close();
        }
      }
      if (!cursor.equals(acknowledgement)) throw new ReadException(ReadProblem.Code.INVALID_REQUEST,
          "Follow cursor is stale or belongs to another lease/epoch; rebootstrap the same recording explicitly");
      if (ended) throw new ReadException(ReadProblem.Code.FOLLOW_ENDED, "The finalized recording is fully acknowledged; close this follow");
      if (complete) {
        if (!proof.finalized()) {
          if (!source.awaitChange(wait, closed::get)) { checkOpen(); return java.util.Optional.empty(); }
          checkOpen(); replacePrefix();
        }
        bootstrap = false; complete = false;
      }
      long reservation = 2L * pageBytes;
      budget.reserve(reservation);
      try {
        var next = new HashMap<>(progress); var batches = new JsonArray();
        var payloads = new HashMap<Key, Long>();
        String nextCursor = UUID.randomUUID().toString();
        var response = envelope(prefixId, nextCursor, described ? null : description, bootstrap);
        response.add("batches", batches);
        int remaining = pageBytes - ReadWire.bytes(response, pageBytes).length;
        var turns = new java.util.ArrayList<Turn>();
        for (var member : prefix.members()) for (var query : queries) turns.add(new Turn(member, query));
        int position = 0;
        for (int i = 0; i < turns.size(); i++) if (turns.get(i).key().equals(turn)) { position = i; break; }
        int work = 0, skipped = 0;
        var deferred = new HashSet<Key>(); Key firstDeferred = null;
        // Fill the remaining space from other selections after a byte deferral, without
        // reading the blocked selection again. Give the first deferral a fresh page next;
        // otherwise a chatty selection can consume its space on every page. Only ack commits it.
        while (work < pageRecords && skipped < turns.size()) {
          var selected = turns.get(position); position = (position + 1) % turns.size();
          var member = selected.member(); var query = selected.query(); var key = selected.key();
          int ordinal = next.getOrDefault(key, 0);
          long count = member.snapshot().sampleCount(query.entry());
          int admitted = ordinal - progress.getOrDefault(key, 0);
          if (deferred.contains(key) || ordinal == count || admitted >= query.batchRecords() || payloads.getOrDefault(key, 0L) >= query.batchBytes()) {
            skipped++; continue;
          }
          skipped = 0;
          var frame = new EntryPage(member.id(), query);
          int overhead = frame.overhead();
          int recordBytes = singleRecordBytes(prefixId, overhead);
          // Read one physical record at a time, on one cursor per entry/turn. This keeps
          // problem coordinates exact and lets an unfilled page defer one record without
          // acknowledging it. Lookahead is charged as work even when its value is deferred.
          int payloadLimit = query.recordLimit() == null ? recordBytes : Math.min(recordBytes, query.recordLimit().maxBytes());
          var read = new ReadQuery(query.entry(), bootstrap ? query.windows() : List.of(), ReadQuery.Order.FILE,
              1, query.batchBytes(), query.field(), null, ordinal)
              .withRecordLimit(payloadLimit, ReadQuery.LimitAction.PROBLEM);
          try (var samples = member.snapshot().read(read)) {
            int visited = 0;
            while (work < pageRecords && visited < TURN_RECORDS && admitted < query.batchRecords() && ordinal < count
                && payloads.getOrDefault(key, 0L) < query.batchBytes()) {
              var batch = samples.next(); checkOpen(); work++; visited++;
              if (batch.recordsVisited() != 1) throw new IOException("Follow reader must visit one physical ordinal");
              int end = Math.addExact(ordinal, 1);
              if (end > count) throw new IOException("Follow reader exceeded its fixed prefix");
              var body = record(batch, query.entry(), ordinal, recordBytes);
              // Charging the complete one-record wrapper slightly overestimates the merged
              // arrays. Fixed frame widths and commas are reserved separately, so the final
              // encoding is bounded without serializing the growing page after each sample.
              int bytes = ReadWire.bytes(body, recordBytes).length + (frame.records == 0 ? overhead : 0);
              if (bytes > remaining) {
                deferred.add(key); if (firstDeferred == null) firstDeferred = key;
                break;
              }
              if (frame.records == 0) batches.add(frame.json);
              remaining -= bytes; frame.add(body, batch.payloadBytes(), end, end == count);
              next.put(key, end); ordinal = end; admitted++;
              payloads.merge(key, batch.payloadBytes(), Long::sum);
            }
          }
        }
        boolean finished = atFrontier(next);
        response.addProperty("frontier_complete", finished);
        response.addProperty("ended", finished && proof.finalized());
        byte[] encoded = ReadWire.bytes(response, pageBytes); checkOpen();
        budget.release(reservation - encoded.length); reservation = 0;
        pending = new Pending(cursor, nextCursor, new Buffer(encoded, budget), Map.copyOf(next), finished,
            firstDeferred == null ? turns.get(position).key() : firstDeferred);
        return java.util.Optional.of(new Page(pending.buffer()));
      } catch (ReadWire.WireLimit failure) {
        throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
            "Follow metadata or frame exceeds the page budget; no ordinals acknowledged", failure);
      } finally { if (reservation != 0) budget.release(reservation); }
    } finally {
      busy.set(false);
      if (closed.get()) close();
    }
  }

  /** False completion flags reserve their longest encoding; cursor/UUID widths never vary. */
  private JsonObject envelope(String prefix, String nextCursor, JsonObject frontier, boolean history) {
    var response = new JsonObject(); response.addProperty("api_version", 1);
    response.addProperty("server_epoch", epoch); response.addProperty("follow_id", id);
    response.addProperty("cursor", cursor); response.addProperty("next_cursor", nextCursor);
    response.addProperty("prefix_id", prefix); response.addProperty("bootstrap", history);
    response.addProperty("frontier_complete", false);
    response.addProperty("ended", false);
    if (frontier != null) response.add("frontier", frontier);
    response.add("batches", new JsonArray()); return response;
  }

  private int singleRecordBytes(String prefix, int overhead) throws IOException {
    return pageBytes - ReadWire.bytes(envelope(prefix, initial, null, false), pageBytes).length - overhead;
  }

  /** Merged entry frames retain native accounting, including filtered and malformed records. */
  private static final class EntryPage {
    final JsonObject json = new JsonObject(), body = new JsonObject();
    final JsonArray samples = new JsonArray(), problems = new JsonArray();
    int records;
    long payload;
    EntryPage(String member, ReadQuery query) {
      json.addProperty("member_id", member); json.addProperty("entry", query.entry()); json.addProperty("field", query.field());
      json.addProperty("next_ordinal", Integer.MAX_VALUE); json.add("batch", body);
      body.addProperty("kind", "batch"); body.add("samples", samples); body.add("problems", problems);
      body.addProperty("records_visited", Integer.MAX_VALUE); body.addProperty("payload_bytes", Long.toString(Long.MAX_VALUE));
      body.addProperty("complete", false);
    }
    int overhead() throws IOException { return ReadWire.bytes(json, MAX_PAGE_BYTES).length + 1; }
    void add(JsonObject record, long bytes, int end, boolean complete) {
      samples.addAll(record.getAsJsonArray("samples")); problems.addAll(record.getAsJsonArray("problems"));
      records++; payload += bytes;
      json.addProperty("next_ordinal", end); body.addProperty("records_visited", records);
      body.addProperty("payload_bytes", Long.toString(payload)); body.addProperty("complete", complete);
    }
  }

  private static JsonObject gap(String entry, int ordinal, long offset, long payload) {
    var problem = new JsonObject(); problem.addProperty("code", "RESOURCE_LIMIT"); problem.addProperty("entry", entry);
    problem.addProperty("record_offset", offset); problem.addProperty("ordinal", ordinal);
    problem.addProperty("payload_bytes", Long.toString(payload));
    problem.addProperty("message", "Record exceeds the follow payload or wire expansion limit; value omitted");
    var out = new JsonObject(); out.add("samples", new JsonArray());
    var problems = new JsonArray(); problems.add(problem); out.add("problems", problems); return out;
  }

  private static JsonObject record(SampleBatch batch, String entry, int ordinal, int bytes) throws IOException {
    try {
      var body = ReadWire.batch(batch, bytes);
      for (var item : body.getAsJsonArray("problems")) {
        var problem = item.getAsJsonObject(); problem.addProperty("ordinal", ordinal);
        problem.addProperty("payload_bytes", Long.toString(batch.payloadBytes()));
      }
      ReadWire.bytes(body, bytes); return body;
    } catch (ReadWire.WireLimit failure) {
      // Conservative expansion refuses before allocating a large tagged value tree.
    } catch (ReadException failure) {
      if (failure.code() != ReadProblem.Code.RESOURCE_LIMIT) throw failure;
    }
    long offset = batch.samples().isEmpty() ? batch.problems().get(0).recordOffset() : batch.samples().get(0).recordOffset();
    return gap(entry, ordinal, offset, batch.payloadBytes());
  }

  private boolean atFrontier(Map<Key, Integer> next) {
    for (var member : prefix == null ? List.<Member>of() : prefix.members()) for (var query : queries)
      if (next.getOrDefault(new Key(member.id(), query.entry(), query.field()), 0) < member.snapshot().sampleCount(query.entry())) return false;
    return true;
  }

  private void replacePrefix() throws IOException {
    Prefix candidate = Objects.requireNonNull(source.open());
    try {
      checkOpen();
      if (proof != null) validateSuccessor(candidate);
      var out = new JsonObject(); out.addProperty("recording_id", candidate.recordingId());
      out.addProperty("catalog_revision", Long.toString(candidate.catalogRevision())); out.addProperty("finalized", candidate.finalized());
      var members = new JsonArray(); int metadataBytes = 0;
      String candidateId = UUID.randomUUID().toString();
      for (var member : candidate.members()) {
        var item = new JsonObject(); item.addProperty("member_id", member.id()); item.addProperty("generation", Long.toString(member.generation()));
        item.addProperty("content_sha256", member.contentSha256());
        item.addProperty("writer_identity", member.writerIdentity());
        item.add("snapshot", ReadWire.descriptor(member.id(), member.snapshot()));
        metadataBytes = Math.addExact(metadataBytes, ReadWire.bytes(item, pageBytes).length);
        if (metadataBytes > pageBytes) throw metadataLimit(null);
        members.add(item);
        // A prefix may consume a whole metadata-only page, but the next page must be able
        // to carry a diagnosed gap even for its longest selected entry/member names.
        for (var query : queries) if (member.snapshot().entries().containsKey(query.entry())) {
          int bytes = singleRecordBytes(candidateId, new EntryPage(member.id(), query).overhead());
          if (bytes < 1) throw metadataLimit(null);
          ReadWire.bytes(gap(query.entry(), Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), bytes);
        }
      }
      out.add("members", members);
      ReadWire.bytes(envelope(candidateId, initial, out, false), pageBytes);
      var candidateProof = FrontierProof.of(candidate);
      var old = prefix; prefix = candidate; candidate = null;
      proof = candidateProof;
      description = out; prefixId = candidateId; described = false;
      if (old != null) old.close();
    } catch (ReadWire.WireLimit failure) { throw metadataLimit(failure);
    } catch (ReadException failure) {
      if (failure.code() == ReadProblem.Code.RESOURCE_LIMIT) throw metadataLimit(failure);
      throw failure;
    } finally { if (candidate != null) candidate.close(); }
  }

  private static ReadException metadataLimit(Throwable cause) {
    return new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
        "Follow frontier metadata or minimum record frame exceeds the page budget; use fewer entries or recording members", cause);
  }

  private void validateSuccessor(Prefix candidate) throws ReadException {
    if (!proof.recording().equals(candidate.recordingId()) || candidate.catalogRevision() < proof.revision())
      throw changed("Recording identity or catalog revision changed");
    var members = new LinkedHashMap<String, Member>(); candidate.members().forEach(m -> members.put(m.id(), m));
    for (var old : proof.members().entrySet()) {
      var next = members.get(old.getKey());
      if (next == null) throw changed("Retained recording member is no longer available");
      if (old.getValue().generation() != next.generation()) throw changed("Recording member was replaced");
      if (old.getValue().writerIdentity() != null && !old.getValue().writerIdentity().equals(next.writerIdentity())
          || old.getValue().writerIdentity() == null && old.getValue().hash() == null
              && (next.contentSha256() != null || !proof.finalized() && candidate.finalized()))
        throw changed("Recording writer identity changed or its finalization proof is missing");
      if (old.getValue().hash() != null && !old.getValue().hash().equals(next.contentSha256())) throw changed("Finalized recording member hash changed");
      for (var entry : old.getValue().entries().entrySet()) {
        var after = next.snapshot().entries().get(entry.getKey());
        if (after == null || entry.getValue().id() != after.id() || !entry.getValue().type().equals(after.type())
            || next.snapshot().sampleCount(entry.getKey()) < entry.getValue().count())
          throw changed("Recording entry was replaced or its retained history shrank");
      }
    }
  }
  private static ReadException changed(String reason) {
    return new ReadException(ReadProblem.Code.FILE_CHANGED, reason + "; rebootstrap the same recording explicitly if its history is still available");
  }
  private void checkOpen() throws ReadException {
    if (closed.get()) throw new ReadException(ReadProblem.Code.CLOSED, "Follow is closed");
  }
  private static void requireId(String id) {
    if (id == null || id.isBlank() || id.length() > 256) throw new IllegalArgumentException("A bounded source identity is required");
  }
  @Override public void close() throws IOException {
    closed.set(true);
    if (!busy.compareAndSet(false, true)) return;
    try {
      if (pending != null) { pending.buffer().release(); pending = null; }
      var old = prefix; prefix = null; description = null; proof = null;
      if (old != null) old.close();
    } finally { busy.set(false); }
  }
}

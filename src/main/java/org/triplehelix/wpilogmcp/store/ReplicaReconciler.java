/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.triplehelix.wpilogmcp.config.MirrorConfig;

/** Two reconciliation owners enumerate bounded snapshots before payload work, with one coalesced retry per configured
 * mirror. Payload jobs still use the no-queue transfer owner; waiting policies reserve no payloads.
 * A peer hop never enters the master receipt port. */
final class ReplicaReconciler {
  private static final Map<LogStore, Runnable> WAITING = new LinkedHashMap<>();
  static {
    for (int i = 0; i < 2; i++) {
      var worker = new Thread(() -> {
        for (;;) {
          Runnable next;
          synchronized (WAITING) {
            while (WAITING.isEmpty()) try { WAITING.wait(); } catch (InterruptedException ignored) { /* Server-owned daemon. */ }
            var entry = WAITING.entrySet().iterator(); next = entry.next().getValue(); entry.remove();
          }
          next.run(); // I/O and completion callbacks never inherit the scheduling monitor.
        }
      }, "replica-reconcile"); worker.setDaemon(true); worker.start();
    }
  }
  private ReplicaReconciler() {}
  static CompletableFuture<MirrorSync.Result> run(LogStore store, MirrorConfig policy, Clock clock, Consumer<MirrorSync.Progress> progress, long epoch,
      java.util.function.Function<String, List<String>> discovered) {
    var result = new CompletableFuture<MirrorSync.Result>();
    Runnable task = () -> {
      try { result.complete(store.replicaTransfers().reconcile(epoch, () -> reconcile(store, policy, clock, progress, discovered))); }
      catch (Throwable failure) { result.completeExceptionally(failure); }
    };
    boolean admitted;
    synchronized (WAITING) {
      admitted = !WAITING.containsKey(store) && WAITING.size() < 8;
      if (admitted) { WAITING.put(store, task); WAITING.notifyAll(); }
    }
    if (!admitted) result.completeExceptionally(new IOException("BUSY: bounded mirror retry capacity reached"));
    return result;
  }
  private static MirrorSync.Result reconcile(LogStore store, MirrorConfig policy, Clock clock, Consumer<MirrorSync.Progress> progress,
      java.util.function.Function<String, List<String>> discovered) throws Exception {
    store.replicaTransfers().recover(policy).get();
    var failures = new ArrayList<String>(); long copied = 0; boolean masterComplete = false, capacity = false;
    var endpoints = new LinkedHashSet<String>(); endpoints.add(policy.origin()); endpoints.addAll(policy.peers());
    String master = store.replicaHeader().origin().storeId();
    if (policy.masterStoreId() != null && master == null) {
      store.replicaTransfers().master(policy, policy.masterStoreId(), null).get(); master = policy.masterStoreId();
    }
    if (policy.discovery() && master != null) endpoints.addAll(discovered.apply(master));
    for (var address : endpoints) {
      store.replicaTransfers().checkReconciliation();
      boolean authority = address.equals(policy.origin());
      try (var client = new ReplicaClient(address)) {
        if (!authority && master == null) throw new IOException("MASTER_UNKNOWN: configure the master identity before accepting peer claims");
        var peer = client.select(master, authority);
        if (authority) { store.replicaTransfers().master(policy, peer.masterStoreId(), null).get(); master = peer.masterStoreId(); }
        var listing = client.catalog(peer.storeId());
        String epoch = listing.epoch(); long revision = listing.revision(); int total = listing.total(), count = 0;
        String previousKey = null; boolean admitted = true; int selectedCount = 0, matchingCount = 0;
        for (var offer : listing.offers()) {
          store.replicaTransfers().checkReconciliation();
          if (offer.identity() == null || !master.equals(offer.identity().masterStoreId())) throw new IOException("FOREIGN_MASTER: offered identity differs from selected master");
          String key = offer.identity().fileId() + ":" + offer.identity().fileVersion();
          if (previousKey != null && key.compareTo(previousKey) <= 0) throw new IOException("PROTOCOL_MISMATCH: duplicate or unordered offer");
          previousKey = key; if (++count > total) throw new IOException("PROTOCOL_MISMATCH: catalog exceeds its stated total");
          var placement = offer.placement();
          var catalog = store.replicaHeader().replicas();
          boolean held = catalog.files().stream().anyMatch(f -> offer.identity().equals(f.identity()));
          boolean included = placement != null && catalog.includedSessions().contains(placement.sessionId());
          if (!held && !MirrorSync.acquires(policy, placement == null ? null : placement.robot(), placement == null ? null : placement.event(),
              placement == null ? null : placement.endedAt(), placement == null ? null : placement.timeBasis(), clock.instant(), included)) continue;
          if (catalog.exclusions().stream().anyMatch(e -> offer.identity().equals(e.identity()) || e.identity() == null && offer.sha256().equals(e.sha256()))) continue;
          selectedCount++;
          try {
            if (offer.growing()) throw new IOException("ACTIVE_RECORDING: growing bytes cannot establish finalization or authority");
            var candidate = new ReplicaTransfers.Offer(offer.identity(), offer.name(), offer.sha256(), offer.sizeBytes(), offer.kind(), offer.completionBasis(),
                peer.storeId(), offer.growing(), placement == null ? null : new ReplicaCatalog.Placement(placement.robot(), placement.sessionId(), placement.event(),
                    placement.endedAt(), placement.timeBasis(), placement.recordingState(), authority ? "direct_master_claim" : "peer_claim", placement.relationships()));
            if (store.replicaTransfers().unchanged(policy, candidate)) { matchingCount++; continue; }
            if (authority) store.replicaTransfers().confirm(policy, UUID.randomUUID().toString(), new ReplicaCatalog.Confirmation(
                new ReplicaCatalog.MasterRecord(offer.identity(), epoch, revision, offer.sha256(), offer.sizeBytes(), offer.completionBasis()),
                policy.origin(), clock.instant().toString())).get();
            progress.accept(new MirrorSync.Progress("receiving", total - count, offer.sizeBytes(), offer.name()));
            String request = store.replicaTransfers().reconciliationRequest(candidate);
            var receipt = store.replicaTransfers().transfer(policy, request, candidate, offset -> client.bytes(peer.storeId(), offer, offset)).get();
            if (receipt.state().equals("published")) copied = Math.addExact(copied, offer.sizeBytes());
            matchingCount++;
            store.replicaTransfers().observe(policy, new ReplicaJournal.Observation(offer.identity(), offer.sha256(), peer.storeId(),
                receipt.state(), clock.instant().toString())).get();
          } catch (Exception failure) {
            String reason = reason(failure); admitted = false; capacity |= reason.contains("CAPACITY"); add(failures, reason);
            if (reason.startsWith("PEER_CONFLICT") || reason.startsWith("MASTER_MISMATCH") || reason.startsWith("AUTHORITY_CONFLICT"))
              try { store.replicaTransfers().observe(policy, new ReplicaJournal.Observation(offer.identity(), offer.sha256(), peer.storeId(),
                  reason.substring(0, reason.indexOf(':')), clock.instant().toString())).get(); }
              catch (Exception unavailable) { add(failures, reason(unavailable)); }
          }
        }
        if (count != total) throw new IOException("PROTOCOL_MISMATCH: incomplete catalog traversal");
        if (authority) store.replicaTransfers().coverage(policy, new ReplicaJournal.Coverage(master, epoch, revision, total,
            selectedCount, matchingCount, clock.instant().toString())).get();
        if (authority && admitted) masterComplete = true;
      } catch (Exception failure) { add(failures, reason(failure)); }
    }
    if (masterComplete) store.replicaTransfers().master(policy, master, clock.instant().toString()).get();
    var origin = store.replicaHeader().origin();
    String state = capacity ? "capacity_paused" : masterComplete ? "synchronized" : "partial";
    return new MirrorSync.Result(state, origin.lastSync(), copied, List.of(), List.of(), List.copyOf(failures),
        masterComplete ? "Complete selected master catalog traversed; historical held files are preserved"
            : "Master completeness is unknown; peer claims do not establish authority or deletion");
  }
  private static void add(List<String> values, String value) { if (values.size() < 32) values.add(value.length() > 2048 ? value.substring(0, 2048) : value); }
  private static String reason(Throwable failure) { while (failure.getCause() != null) failure = failure.getCause(); return Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()); }
}

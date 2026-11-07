/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One bounded recovery record per mirror. Readers interpret ownership without loading workers. */
final class ReplicaJournal {
  static final int MAX_RETIRED = 32, MAX_RECEIPTS = 128;
  static final String JOURNAL = ".replicas/transfer.json";
  private ReplicaJournal() {}

  record Intent(String requestId, String fingerprint, String policyKey, String storeId, String catalogEpoch,
      long catalogRevision, String operation, ReplicaCatalog.Entry target,
      List<ReplicaCatalog.Entry> previous, List<ReplicaCatalog.Exclusion> exclusions,
      String staging, long flushed, String prefixSha256, boolean ready) {
    Intent {
      uuid(requestId); hash(fingerprint); hash(policyKey); uuid(storeId); uuid(catalogEpoch);
      previous = List.copyOf(previous); exclusions = List.copyOf(exclusions);
      if (catalogRevision < 1 || previous.size() > ReplicaCatalog.MAX_CLEANUP
          || exclusions.size() > ReplicaCatalog.MAX_CLEANUP || flushed < 0
          || !Set.of("transfer", "cleanup", "reinclude", "confirm").contains(operation)) throw new IllegalArgumentException("Invalid replica intent");
      if (operation.equals("transfer")) {
        if (target == null || !generation(target.path()) || target.identity() == null
            || staging == null || !staging.equals(".replicas/staging/" + requestId + ".part")
            || flushed > target.sizeBytes()) throw new IllegalArgumentException("Invalid transfer intent");
        hash(prefixSha256);
      } else if ((!operation.equals("confirm") && target != null) || staging != null || flushed != 0) throw new IllegalArgumentException("Invalid control intent");
    }
    Intent progress(long bytes, String hash, boolean verified, ReplicaCatalog.Entry file) {
      return new Intent(requestId, fingerprint, policyKey, storeId, catalogEpoch, catalogRevision, operation,
          file, previous, exclusions, staging, bytes, hash, verified);
    }
  }
  record Receipt(String requestId, String fingerprint, String state, long catalogRevision,
      ReplicaCatalog.Entry file) {
    Receipt { uuid(requestId); hash(fingerprint); if (catalogRevision < 1) throw new IllegalArgumentException("Invalid receipt"); }
  }
  record Observation(ReplicaCatalog.Identity identity, String sha256, String supplier, String state, String observedAt) {
    Observation {
      java.util.Objects.requireNonNull(identity); hash(sha256);
      if (supplier == null || supplier.length() > 1024 || state == null || state.length() > 128) throw new IllegalArgumentException("Invalid replica observation");
      java.time.Instant.parse(observedAt);
    }
  }
  record Coverage(String masterStoreId, String catalogEpoch, long catalogRevision, int offeredFiles,
      int selectedFiles, int heldMatchingFiles, String observedAt) {
    Coverage {
      uuid(masterStoreId); uuid(catalogEpoch); java.time.Instant.parse(observedAt);
      if (catalogRevision < 1 || offeredFiles < 0 || offeredFiles > ReplicaCatalog.MAX_FILES
          || selectedFiles < 0 || selectedFiles > offeredFiles || heldMatchingFiles < 0 || heldMatchingFiles > selectedFiles)
        throw new IllegalArgumentException("Invalid master coverage");
    }
  }
  record State(int schemaVersion, Intent pending, List<ReplicaCatalog.Entry> retired,
      List<Receipt> receipts, List<ReplicaCatalog.Confirmation> confirmations,
      List<Observation> observations, Coverage coverage) {
    State(int schemaVersion, Intent pending, List<ReplicaCatalog.Entry> retired,
        List<Receipt> receipts, List<ReplicaCatalog.Confirmation> confirmations) {
      this(schemaVersion, pending, retired, receipts, confirmations, List.of(), null);
    }
    State {
      retired = List.copyOf(retired); receipts = List.copyOf(receipts); confirmations = List.copyOf(confirmations);
      observations = observations == null ? List.of() : List.copyOf(observations);
      if (schemaVersion != 1 || retired.size() > MAX_RETIRED || receipts.size() > MAX_RECEIPTS
          || observations.size() > ReplicaCatalog.MAX_FILES * 8
          || confirmations.size() > ReplicaCatalog.MAX_FILES
          || retired.stream().map(ReplicaCatalog.Entry::path).distinct().count() != retired.size()
          || receipts.stream().map(Receipt::requestId).distinct().count() != receipts.size()
          || confirmations.stream().map(c -> c.record().identity()).distinct().count() != confirmations.size())
        throw new IllegalArgumentException("Replica recovery metadata limit or duplicate identity");
    }
    State pending(Intent value) { return new State(schemaVersion, value, retired, receipts, confirmations, observations, coverage); }
    State completed(Receipt receipt, List<ReplicaCatalog.Entry> removed) {
      var next = new ArrayList<>(retired);
      for (var entry : removed) if (!next.contains(entry)) next.add(entry);
      return new State(schemaVersion, null, next, receipts, confirmations, observations, coverage).withReceipt(receipt);
    }
    State withReceipt(Receipt receipt) {
      var history = new ArrayList<>(receipts); history.add(receipt);
      if (history.size() > MAX_RECEIPTS) history.remove(0);
      return new State(schemaVersion, pending, retired, history, confirmations, observations, coverage);
    }
    State observed(Observation value) {
      var next = new ArrayList<>(observations);
      next.removeIf(o -> o.identity().equals(value.identity()) && o.supplier().equals(value.supplier()) && o.state().equals(value.state()));
      var same = next.stream().filter(o -> o.identity().equals(value.identity())).toList();
      if (same.size() >= 8) next.remove(same.get(0));
      if (next.stream().noneMatch(o -> o.identity().equals(value.identity()))
          && next.stream().map(Observation::identity).distinct().count() >= ReplicaCatalog.MAX_FILES)
        throw new IllegalArgumentException("Replica observation identity limit reached");
      next.add(value); return new State(schemaVersion, pending, retired, receipts, confirmations, next, coverage);
    }
    State coverage(Coverage value) { return new State(schemaVersion, pending, retired, receipts, confirmations, observations, value); }
  }
  static State read(Path root, StoreFiles io) throws IOException {
    var path = io.check(root.resolve(JOURNAL));
    return Files.exists(path) ? io.read(path, State.class) : new State(1, null, List.of(), List.of(), List.of());
  }
  static void write(Path root, StoreFiles io, State state) throws IOException { io.writeDurable(root.resolve(JOURNAL), state); }
  static boolean generation(String path) {
    var parts = path.split("/", -1);
    if (parts.length != 4 || !parts[0].equals(".replicas") || !parts[1].equals("objects")) return false;
    try { uuid(parts[2]); StoreFiles.component(parts[3]); return true; } catch (RuntimeException invalid) { return false; }
  }
  static List<Path> owned(Path root, StoreFiles io, State state) throws IOException {
    var paths = new ArrayList<Path>();
    if (Files.exists(root.resolve(JOURNAL))) paths.add(io.check(root.resolve(JOURNAL)));
    if (Files.exists(root.resolve(".replicas/owner.lock"))) paths.add(io.check(root.resolve(".replicas/owner.lock")));
    for (var entry : state.retired()) paths.add(io.resolve(root, entry.path()));
    if (state.pending() != null && state.pending().operation().equals("transfer")) {
      paths.add(io.resolve(root, state.pending().staging()));
      var stage = io.resolve(root, state.pending().staging()); var destination = io.resolve(root, state.pending().target().path());
      if (Files.exists(stage) && Files.exists(destination) && Files.isSameFile(stage, destination)) paths.add(destination);
    }
    return paths;
  }
  static void uuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid canonical UUID");
  }
  static void hash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256"); }
}

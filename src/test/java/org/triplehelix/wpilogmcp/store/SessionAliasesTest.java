/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

class SessionAliasesTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

  @Test void aDurableManifestWritePublishesAnOffQueueWakeToken() throws Exception {
    var constructor = org.triplehelix.wpilogmcp.log.LogManager.class.getDeclaredConstructor(); constructor.setAccessible(true);
    var manager = constructor.newInstance();
    try {
      manager.addAllowedDirectory(temp);
      var store = manager.stores().store(temp.resolve("store"));
      store.importPaths(new LogStore.Request(List.of(), false, null), progress -> {}).get();
      long before = manager.catalogPublication();
      store.capture(io -> { var header = io.read(store.root().resolve("store.json"), StoreManifest.Header.class); io.write(store.root().resolve("store.json"), header); return null; });
      assertTrue(manager.catalogPublication() > before, "Followers need to see a durable metadata-only store publication");
    } finally { manager.shutdown(); }
  }
  @Test void aliasesSurviveBothManifestCodecsAndCopyHelpers() {
    var json = StoreFiles.JSON.toJsonTree(new Session("canonical", "2026-01-01T00:00:00Z",
        "2026-01-01T00:01:00Z", "pit_clock", null, null, null, null, List.of())).getAsJsonObject();
    json.add("merged_session_ids", StoreFiles.JSON.toJsonTree(List.of("original", "earlier")));
    var session = StoreFiles.JSON.fromJson(json, Session.class);
    for (var copy : List.of(session, session.withFiles(List.of()), session.withSystemLogs(SystemLogState.EMPTY))) {
      assertEquals(json.get("merged_session_ids"), StoreFiles.JSON.toJsonTree(copy).getAsJsonObject().get("merged_session_ids"));
      assertEquals(json.get("merged_session_ids"), StoreJson.JSON.toJsonTree(copy).getAsJsonObject().get("merged_session_ids"));
    }
  }

  @Test void oldManifestsHaveAnEmptyAliasList() {
    var session = StoreFiles.JSON.fromJson("{\"id\":\"original\",\"files\":[]}", Session.class);
    assertEquals(StoreFiles.JSON.toJsonTree(List.of()),
        StoreFiles.JSON.toJsonTree(session).getAsJsonObject().get("merged_session_ids"));
  }

  @Test void everyManifestReconstructionCarriesTheAliases() throws Exception {
    var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src/main/java/org/triplehelix/wpilogmcp/store"));
         var manager = compiler.getStandardFileManager(null, null, null)) {
      var sources = manager.getJavaFileObjectsFromPaths(files.filter(p -> p.toString().endsWith(".java")).toList());
      var parse = (com.sun.source.util.JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, sources);
      var checked = new java.util.ArrayList<String>();
      for (var tree : parse.parse()) new com.sun.source.util.TreeScanner<Void, Void>() {
        @Override public Void visitNewClass(com.sun.source.tree.NewClassTree node, Void ignored) {
          String type = node.getIdentifier().toString();
          var args = node.getArguments();
          if ((type.equals("Session") || type.equals("StoreManifest.Session")) && args.size() >= 9
              && args.get(0).toString().endsWith(".id()")) {
            checked.add(tree.getSourceFile().getName());
            assertEquals(17, args.size(), "A manifest copy lost fields: " + node);
            assertTrue(args.get(16).toString().endsWith(".mergedSessionIds()"), "A copy dropped its aliases: " + node);
          }
          return super.visitNewClass(node, ignored);
        }
      }.scan(tree, null);
      assertTrue(checked.size() >= 9, "The guard must inspect actual store mutation paths");
    }
  }
}

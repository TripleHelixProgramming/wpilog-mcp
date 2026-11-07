/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A remote reader keeps the server's stated basis, without trusting transmitted byte sizes. */
class StructSchemaDescriptionsTest {
  @Test void roundTripPreservesEveryBasisAndAddsNoLocalFallbacks() {
    var original = StructSchemas.of(Map.of("Sample", "double value;"),
        Map.of("Sample", "/.schema/struct:Sample"));
    var descriptions = original.allStructs().stream().map(n -> original.info(n).orElseThrow()).toList();
    var restored = StructSchemas.fromDescriptions(descriptions);
    assertEquals(original.allStructs(), restored.allStructs());
    for (var expected : descriptions) assertEquals(expected, restored.info(expected.name()).orElseThrow());
    assertTrue(descriptions.stream().anyMatch(d -> d.source() == StructSchemas.Source.ASSUMED));
    assertTrue(descriptions.stream().anyMatch(d -> d.source() == StructSchemas.Source.WPILIB));
    assertTrue(StructSchemas.fromDescriptions(List.of()).allStructs().isEmpty());
  }

  @Test void layoutIsRecompiledInsteadOfTrustingTheRemoteSizeAndFields() {
    var lie = new StructSchemas.StructInfo("Sample", StructSchemas.Source.LOGGED,
        "double value;", Integer.MAX_VALUE, false, "not trusted", List.of(), "/.schema/struct:Sample");
    var actual = StructSchemas.fromDescriptions(List.of(lie)).info("Sample").orElseThrow();
    assertEquals(8, actual.size());
    assertTrue(actual.valid());
    assertEquals("value", actual.fields().get(0).name());
  }

  @Test void duplicateAndMissingBasisAreRefused() {
    var info = StructSchemas.of(Map.of("Sample", "double value;"), Map.of()).info("Sample").orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> StructSchemas.fromDescriptions(List.of(info, info)));
    var unlabelled = new StructSchemas.StructInfo("Sample", null, "double value;", 8, true, null, List.of(), null);
    assertThrows(IllegalArgumentException.class, () -> StructSchemas.fromDescriptions(List.of(unlabelled)));
  }

  @Test void remoteSchemaTextHasABoundBeforeCompilation() {
    var huge = new StructSchemas.StructInfo("Huge", StructSchemas.Source.LOGGED,
        " ".repeat((4 << 20) + 1), 0, true, null, List.of(), null);
    var error = assertThrows(IllegalArgumentException.class,
        () -> StructSchemas.fromDescriptions(List.of(huge)));
    assertTrue(error.getMessage().contains("schema"), error.getMessage());
  }
}

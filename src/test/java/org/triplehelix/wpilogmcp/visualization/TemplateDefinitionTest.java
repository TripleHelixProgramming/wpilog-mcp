/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TemplateDefinitionTest {
  @Test void canonicalNumbersAndUtf16PropertyOrderMatchRfc8785() throws Exception {
    var input = VisualizationContracts.parse("{\"z\":[333333333.33333329,1E30,4.50,2e-3,1e-27,-0.0],\"a\":\"\\u20ac\\n\"}");
    String expected = "{\"a\":\"€\\n\",\"z\":[333333333.3333333,1e+30,4.5,0.002,1e-27,0]}";
    assertEquals(expected, TemplateDefinition.canonical(input));
    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected.getBytes(StandardCharsets.UTF_8))), TemplateDefinition.digest(input));
    var unicode = VisualizationContracts.parse("{\"\\ufb33\":1,\"\\ud83d\\ude00\":2,\"\\u20ac\":3,\"\\r\":4}");
    assertEquals("{\"\\r\":4,\"€\":3,\"😀\":2,\"דּ\":1}", TemplateDefinition.canonical(unicode));
  }
  @Test void portableEnvelopeRejectsTamperingDuplicateKeysAndNonportableFields() throws Exception {
    var template = BundledTemplates.all().get(0); var envelope = TemplateDefinition.export(template);
    assertEquals(template, TemplateDefinition.imported(envelope.toString()));
    var reordered = new JsonObject(); envelope.entrySet().stream().sorted((a,b) -> b.getKey().compareTo(a.getKey())).forEach(e -> reordered.add(e.getKey(), e.getValue()));
    assertEquals(template, TemplateDefinition.imported(reordered.toString()));
    assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.imported(envelope.toString().replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1")));
    envelope.getAsJsonObject("template").addProperty("name", "Tampered");
    assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.imported(envelope.toString()));
    for (String key : new String[] {"path", "samples", "credentials", "source_ref", "url"}) {
      var bad = template.deepCopy(); bad.addProperty(key, "synthetic"); assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.export(bad));
    }
    var future = template.deepCopy(); future.addProperty("schema_version", 2); assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.export(future));
    var surrogate = template.deepCopy(); surrogate.addProperty("name", "\ud800"); assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.export(surrogate));
    var huge = template.deepCopy(); huge.addProperty("description", "x".repeat(1048577)); assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.export(huge));
    var revision = template.deepCopy(); revision.addProperty("revision", 9007199254740992L); assertThrows(IllegalArgumentException.class, () -> TemplateDefinition.export(revision));
  }
}

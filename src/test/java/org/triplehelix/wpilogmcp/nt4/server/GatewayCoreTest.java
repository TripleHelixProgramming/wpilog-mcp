/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;

class GatewayCoreTest {
  static Subscribe sub(int uid, String topic, String options) {
    return new Subscribe(List.of(topic), uid, JsonParser.parseString(options).getAsJsonObject());
  }
  static List<ValueFrame> values(List<GatewayCore.Delivery> deliveries, String client) {
    return deliveries.stream().filter(d -> d.client().equals(client)).flatMap(d -> d.values().stream()).toList();
  }
  static List<ControlMessage> controls(List<GatewayCore.Delivery> deliveries, String client) {
    return deliveries.stream().filter(d -> d.client().equals(client) && d.control() != null).map(GatewayCore.Delivery::control).toList();
  }

  @Test void allAndSampledClientsUsePeriodsAndOneOrderedStreamAcrossOverlaps() {
    var core = new GatewayCore(); core.connect("all"); core.connect("sampled");
    core.announce("/x", "int", new JsonObject()); core.announce("/y", "int", new JsonObject());
    var announcements = core.receive("all", sub(0, "", "{\"prefix\":true,\"all\":true,\"periodic\":0.01}"), 0);
    assertEquals(List.of("/x", "/y"), controls(announcements, "all").stream().map(m -> ((Announce) m).name()).toList());
    core.receive("all", sub(1, "/x", "{\"all\":true,\"periodic\":0.02}"), 0);
    core.receive("sampled", sub(0, "", "{\"prefix\":true,\"periodic\":0.02}"), 0);
    core.value("/x", 10, 2, 1L); core.value("/y", 11, 2, 4L); core.value("/x", 12, 2, 2L);
    assertTrue(core.tick(9999).isEmpty());
    var first = core.tick(10000);
    assertEquals(List.of(1L, 4L, 2L), values(first, "all").stream().map(ValueFrame::value).toList());
    assertEquals(List.of(10L, 11L, 12L), values(first, "all").stream().map(ValueFrame::timestampUs).toList());
    assertTrue(values(first, "sampled").isEmpty());
    var second = core.tick(20000);
    assertEquals(List.of(4L, 2L), values(second, "sampled").stream().map(ValueFrame::value).toList());
    assertTrue(values(second, "all").isEmpty());
    assertTrue(core.tick(40000).isEmpty()); // No repeated unchanged values.
  }

  @Test void exactPrefixTopicsOnlyAndHiddenTopics() {
    var core = new GatewayCore(); core.connect("c");
    for (var name : List.of("/a", "/ab", "$clients", "/.schema/struct:S")) core.announce(name, "raw", new JsonObject());
    var exact = core.receive("c", sub(0, "/a", "{}"), 0);
    assertEquals(1, controls(exact, "c").size()); assertEquals("/a", ((Announce) controls(exact, "c").get(0)).name());
    var prefix = core.receive("c", sub(1, "", "{\"prefix\":true,\"topicsonly\":true,\"all\":true}"), 0);
    assertEquals(List.of("/ab", "/.schema/struct:S"), controls(prefix, "c").stream().map(m -> ((Announce) m).name()).toList());
    core.value("/ab", 2, 5, new byte[] {1}); core.value("/a", 3, 5, new byte[] {2});
    assertEquals(1, values(core.tick(100000), "c").size());
    var hidden = core.receive("c", sub(2, "$", "{\"prefix\":true}"), 0);
    assertEquals("$clients", ((Announce) controls(hidden, "c").get(0)).name());
    var newlyPublished = core.announce("/abc", "boolean", new JsonObject());
    assertEquals("/abc", ((Announce) controls(newlyPublished, "c").get(0)).name());
  }

  @Test void retainedValuesUseLargestTimestampAndCachedFalseSuppressesReplay() {
    var core = new GatewayCore(); core.connect("c");
    core.announce("/x", "int", new JsonObject());
    core.value("/x", 20, 2, 2L); core.value("/x", 10, 2, 1L);
    var initial = core.receive("c", sub(0, "", "{\"prefix\":true,\"all\":true}"), 0);
    assertEquals(2L, values(initial, "c").get(0).value());
    assertTrue(values(core.receive("c", sub(1, "/x", "{}"), 0), "c").isEmpty());
    core.value("/x", 5, 2, 3L);
    assertEquals(5, values(core.tick(100000), "c").get(0).timestampUs()); // Lossless stream accepts older timestamps.
    var update = JsonParser.parseString("{\"cached\":false}").getAsJsonObject();
    assertEquals(List.of(new Properties("/x", null, update)), controls(core.properties("/x", update), "c"));
    core.connect("later");
    assertTrue(values(core.receive("later", sub(0, "", "{\"prefix\":true}"), 0), "later").isEmpty());
    core.value("/x", 30, 2, 4L);
    assertEquals(4L, values(core.tick(200000), "later").get(0).value());
  }

  @Test void subscriptionReplacementKeepsUnspecifiedOptionsAndUnsubscribeDropsPending() {
    var core = new GatewayCore(); core.connect("c");
    core.announce("/a", "int", new JsonObject()); core.announce("/b", "int", new JsonObject());
    core.receive("c", sub(7, "/a", "{\"all\":true,\"periodic\":0.01,\"custom\":1}"), 0);
    core.value("/a", 1, 2, 1L);
    core.receive("c", sub(7, "/b", "{}"), 0);
    core.value("/b", 2, 2, 2L); core.value("/b", 3, 2, 3L);
    assertEquals(List.of(2L, 3L), values(core.tick(10000), "c").stream().map(ValueFrame::value).toList());
    core.value("/b", 4, 2, 4L); core.receive("c", new Unsubscribe(7), 0);
    assertTrue(core.tick(100000).isEmpty());
    core.disconnect("c"); assertEquals(0, core.clientCount());
    assertTrue(core.receive("c", sub(0, "", "{}"), 0).isEmpty());
  }

  @Test void replacingAllWithSampledCoalescesAlreadyQueuedChanges() {
    var core = new GatewayCore(); core.connect("c");
    core.announce("/x", "int", new JsonObject());
    core.receive("c", sub(0, "/x", "{\"all\":true,\"periodic\":0.01}"), 0);
    core.value("/x", 1, 2, 1L); core.value("/x", 2, 2, 2L);
    core.receive("c", sub(0, "/x", "{\"all\":false}"), 0);
    assertEquals(List.of(2L), values(core.tick(10000), "c").stream().map(ValueFrame::value).toList());
  }

  @Test void writesArePrivateSinksWithTruthfulAcknowledgements() {
    var core = new GatewayCore(); core.connect("writer"); core.connect("observer");
    core.announce("/x", "int", JsonParser.parseString("{\"retained\":true}").getAsJsonObject());
    core.receive("observer", sub(0, "", "{\"prefix\":true,\"all\":true}"), 0);
    core.value("/x", 1, 2, 42L);
    var published = controls(core.receive("writer", new Publish("/x", 19, "string", new JsonObject()), 0), "writer");
    assertEquals("int", ((Announce) published.get(0)).type()); assertEquals(19, ((Announce) published.get(0)).pubuid());
    assertTrue(core.receive("writer", new ValueFrame(19, 2, 2, 99L), 0).isEmpty());
    var requested = JsonParser.parseString("{\"retained\":false,\"new\":1}").getAsJsonObject();
    var reply = (Properties) controls(core.receive("writer", new SetProperties("/x", requested), 0), "writer").get(0);
    assertEquals(true, reply.ack()); assertEquals(JsonParser.parseString("{\"retained\":true,\"new\":null}"), reply.update());
    assertEquals(List.of(42L), values(core.tick(100000), "observer").stream().map(ValueFrame::value).toList());
    var privateReply = (Announce) controls(core.receive("writer", new Publish("/private", 20, "double", requested), 0), "writer").get(0);
    assertFalse(core.topics().containsKey("/private")); assertEquals(20, privateReply.pubuid());
    assertEquals(List.of(new Unannounce("/private", privateReply.id())), controls(core.receive("writer", new Unpublish(20), 0), "writer"));
    assertTrue(core.receive("writer", new SetProperties("/missing", requested), 0).isEmpty());
    assertTrue(core.firstWrite("writer")); assertFalse(core.firstWrite("writer")); assertTrue(core.firstWrite("observer"));
  }

  @Test void deletingTopicFlushesValuesBeforeUnannounceAndNeverReusesIds() {
    var core = new GatewayCore(); core.connect("c");
    core.receive("c", sub(0, "", "{\"prefix\":true,\"all\":true}"), 0);
    var before = (Announce) controls(core.announce("/x", "int", new JsonObject()), "c").get(0);
    core.value("/x", 1, 2, 2L); core.value("/x", 2, 2, 3L);
    var removed = core.unannounce("/x");
    assertEquals(List.of(2L, 3L), removed.get(0).values().stream().map(ValueFrame::value).toList());
    assertEquals(new Unannounce("/x", before.id()), removed.get(1).control());
    var after = (Announce) controls(core.announce("/x", "string", new JsonObject()), "c").get(0);
    assertNotEquals(before.id(), after.id()); assertTrue(core.tick(1000000).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> core.announce("/x", "int", new JsonObject()));
    assertThrows(IllegalArgumentException.class, () -> core.value("/missing", 0, 2, 1L));
  }

  @Test void timeSyncEchoesImplementationSelectedValueAndUsesSuppliedRobotClock() {
    var core = new GatewayCore(); core.connect("c");
    for (var request : List.of(new ValueFrame(-1, 0, 2, 100L), new ValueFrame(-1, 0, 1, 3.5))) {
      var response = values(core.receive("c", request, 1234567), "c").get(0);
      assertEquals(-1, response.topicId()); assertEquals(1234567, response.timestampUs());
      assertEquals(request.typeCode(), response.typeCode()); assertEquals(request.value(), response.value());
    }
    assertTrue(core.receive("missing", new ValueFrame(-1, 0, 2, 100L), 1).isEmpty());
  }
}

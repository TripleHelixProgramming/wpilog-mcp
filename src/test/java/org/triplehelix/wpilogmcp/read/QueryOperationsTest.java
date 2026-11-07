/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;

class QueryOperationsTest {
  private ReadQuery query(String suffix) {
    return ReadWire.query(JsonParser.parseString("{\"entry\":\"x\"," + suffix + "}").getAsJsonObject());
  }
  private List<Sample> read(ReadQuery query, long[] times, Object... values) throws Exception {
    var series = new RecordSeries() {
      public int size() { return times.length; }
      public long timestampUs(int i) { return times[i]; }
      public long offset(int i) { return 64 + i * 32; }
      public long payloadBytes(int i) { return 16; }
      public boolean monotonic() { return true; }
      public Object decode(int i) { return values[i]; }
      public String problemMessage(int i, Exception e) { return e.toString(); }
    };
    var result = new ArrayList<Sample>(); int visited = 0; long bytes = 0;
    try (var cursor = SampleCursors.open(series, query, null, 1024)) {
      SampleBatch batch;
      do {
        batch = cursor.next(); result.addAll(batch.samples()); visited += batch.recordsVisited(); bytes += batch.payloadBytes();
        assertTrue(batch.problems().isEmpty());
        var frames = ReadWire.wireBatches(batch);
        while (frames.hasNext()) assertDoesNotThrow(() -> ReadWire.batch(ReadWire.batch(frames.next())));
      } while (!batch.complete());
    }
    assertEquals(times.length, visited); assertEquals(times.length * 16L, bytes);
    return result;
  }
  @Test void projectionUsesTheSharedNestedAndArrayFieldPathsWithoutChangingCoordinates() throws Exception {
    var q = query("\"field\":\"[1].pose.x\"");
    var values = List.of(Map.of("pose", Map.of("x", 4L)), Map.of("pose", Map.of("x", 9L)));
    var result = read(q, new long[]{7}, values);
    assertEquals(9L, result.get(0).value()); assertEquals(7, result.get(0).timestampUs());
    assertEquals(64, result.get(0).recordOffset());
    assertEquals(List.of(4L, 9L), read(query("\"field\":\"[*].pose.x\""), new long[]{7}, values).get(0).value());
    assertEquals(q, ReadWire.query(ReadWire.query(q)));
  }
  @Test void bucketsKeepExtremesAndCountNonfiniteSamplesAcrossBatchBoundaries() throws Exception {
    var q = query("\"batch_records\":1,\"reduction\":{\"max_points\":3},\"windows\":[{\"start_us\":\"0\",\"end_us\":\"6\"}]");
    var result = read(q, new long[]{0,1,2,3,4,5}, 1.,99.,3.,5.,Double.NaN,9.);
    assertEquals(3, result.size());
    var first = (Map<?, ?>) result.get(0).value();
    assertEquals(1., first.get("min")); assertEquals(99., first.get("max")); assertEquals(50., first.get("mean"));
    assertEquals(2L, first.get("count")); assertEquals(0L, first.get("start_us")); assertEquals(2L, first.get("end_us"));
    var last = (Map<?, ?>) result.get(2).value();
    assertEquals(2L, last.get("count")); assertEquals(1L, last.get("finite_count")); assertEquals(9., last.get("mean"));
    assertEquals(q, ReadWire.query(ReadWire.query(q)));
  }
  @Test void bucketsNeverBridgeWindowsAndKeepMicrosecondsAboveDoublePrecision() throws Exception {
    long base = 9_007_199_254_740_993L;
    var q = query("\"reduction\":{\"max_points\":1},\"windows\":[{\"start_us\":\""+base+"\",\"end_us\":\""+(base+2)+"\"},{\"start_us\":\""+(base+4)+"\",\"end_us\":\""+(base+6)+"\"}]");
    var result = read(q, new long[]{base,base+1,base+4,base+5},1.,3.,7.,9.);
    assertEquals(2, result.size()); assertEquals(base, result.get(0).timestampUs());
    assertEquals(base+4, result.get(1).timestampUs());
    assertEquals(2., ((Map<?, ?>) result.get(0).value()).get("mean"));
    assertEquals(8., ((Map<?, ?>) result.get(1).value()).get("mean"));
  }
  @Test void reductionDoesNotTurnAnOverflowedMeanIntoAnInfiniteMeasurement() throws Exception {
    var result = read(ReadQuery.all("x").reduce(1), new long[]{0, 1}, Double.MAX_VALUE, Double.MAX_VALUE);
    var bucket = (Map<?, ?>) result.get(0).value();
    assertEquals(2L, bucket.get("finite_count")); assertEquals(Double.MAX_VALUE, bucket.get("max"));
    assertNull(bucket.get("mean"));
  }
  @Test void reductionRefusesFileOrderAndUnboundedOrNonpositiveBuckets() {
    for (String suffix : List.of("\"order\":\"file\",\"reduction\":{\"max_points\":1}",
        "\"reduction\":{\"max_points\":0}", "\"reduction\":{\"max_points\":999999999}"))
      assertThrows(IllegalArgumentException.class, () -> query(suffix));
  }
}

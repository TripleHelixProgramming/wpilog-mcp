/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Exact integer fixtures distinguish a measured UTC mapping from a first-offset estimate.
 * HTTP, CSV parsing and the independent Arrow reader exercise the actual transport contract.
 */
class DataEndpointUtcTest {
  @TempDir Path directory;
  private static final long W = 1_767_225_600_000_000L; // 2026-01-01T00:00:00.000000Z
  private static final String VALUE = "/Value";
  private static final String ROUNDING = "nearest_microsecond_ties_to_even";
  private static final String BEFORE = "Before the first valid wall-clock reading";
  private final LogManager logs = LogManager.getInstance();
  private Set<Path> previous;
  private HttpTransport transport;
  private HttpClient client;
  private int nextFixture;

  private record Anchor(long logUs, long epochUs) {}
  private record Sample(long logUs, Object value) {}
  private record Series(String name, String type, List<Sample> samples) {}
  private record CsvSection(List<String> columns, List<List<String>> rows) {}
  private record Payload(String format, ArrowSpecReader.Stream arrow,
      Map<String, CsvSection> csv, JsonArray entries) {}

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    previous = logs.getAllowedDirectories();
    logs.clearAllowedDirectories();
    logs.addAllowedDirectory(directory);
    transport = new HttpTransport(new ToolRegistry(), 0);
    transport.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach void stop() {
    if (transport != null) transport.stop();
    logs.unloadAllLogs();
    logs.clearAllowedDirectories();
    previous.forEach(logs::addAllowedDirectory);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void measuredInterpolationAndLastOffsetCarryTheirExactBasis(String format) throws Exception {
    var path = fixture("systemTime", workedAnchors(),
        numbers(VALUE, new long[] {4_000_000, 15_000_000, 25_000_000}, 2, 4, 8));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {4_000_000, 15_000_000, 25_000_000},
        new Long[] {null, W + 6_000_000, W + 17_000_000});
    assertBasis(result, VALUE, "systemTime", 2, "1767225590000000", "1767225592000000",
        "2000000", 1, BEFORE);
    if (format.equals("csv")) {
      assertEquals(List.of("timestamp_sec", "timestamp_utc", "value"),
          result.csv().get(VALUE).columns());
      assertEquals(List.of("", "2026-01-01T00:00:06.000000Z", "2026-01-01T00:00:17.000000Z"),
          result.csv().get(VALUE).rows().stream().map(row -> row.get(1)).toList());
    }
    assertEquals(List.of(2.0, 4.0, 8.0), numericColumn(result, VALUE, "value"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void oneReadingSetsOnlySamplesAtOrAfterItsRecordedTime(String format) throws Exception {
    var path = fixture("NT:systemTime", List.of(new Anchor(10_000_000, W)),
        numbers(VALUE, new long[] {0, 9_999_999, 10_000_000, 15_000_000}, 1, 2, 3, 4));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {0, 9_999_999, 10_000_000, 15_000_000},
        new Long[] {null, null, W, W + 5_000_000});
    assertBasis(result, VALUE, "NT:systemTime", 1, "1767225590000000", "1767225590000000",
        "0", 2, BEFORE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void noWallClockEntryReportsNullsWithoutGuessingFromTheFile(String format) throws Exception {
    var path = fixture(null, List.of(), numbers(VALUE, new long[] {0, 10_000_000}, 1, 2));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {0, 10_000_000}, new Long[] {null, null});
    assertBasis(result, VALUE, null, 0, null, null, null, 2, "No wall-clock entry");
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void anUnsetClockIsDistinctFromAnAbsentClock(String format) throws Exception {
    var path = fixture("systemTime", List.of(new Anchor(10_000_000, 5)),
        numbers(VALUE, new long[] {0, 15_000_000}, 1, 2));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {0, 15_000_000}, new Long[] {null, null});
    assertBasis(result, VALUE, "systemTime", 0, null, null, null, 2,
        "No valid wall-clock readings");
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void conflictingEpochReadingsAtOneLogTimeMakeTheMappingUnavailable(String format)
      throws Exception {
    var path = fixture("systemTime", List.of(new Anchor(10, W), new Anchor(10, W + 1)),
        numbers(VALUE, new long[] {10, 11}, 2, 4));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {10, 11}, new Long[] {null, null});
    assertBasis(result, VALUE, "systemTime", 2, "1767225599999990", "1767225599999991",
        "1", 2, "Ambiguous wall-clock mapping: one log timestamp has conflicting epoch readings");
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void oneEpochAtDifferentLogTimesMakesTheMappingUnavailable(String format) throws Exception {
    var path = fixture("systemTime", List.of(new Anchor(10, W), new Anchor(12, W)),
        numbers(VALUE, new long[] {10, 11, 12}, 2, 4, 6));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {10, 11, 12}, new Long[] {null, null, null});
    assertBasis(result, VALUE, "systemTime", 2, "1767225599999990", "1767225599999988",
        "2", 3, "Ambiguous wall-clock mapping: one epoch reading names multiple log timestamps");
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void backwardEpochReadingsMakeTheMappingUnavailable(String format) throws Exception {
    var path = fixture("systemTime", List.of(new Anchor(10, W), new Anchor(12, W - 1)),
        numbers(VALUE, new long[] {10, 11, 12}, 2, 4, 6));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {10, 11, 12}, new Long[] {null, null, null});
    assertBasis(result, VALUE, "systemTime", 2, "1767225599999990", "1767225599999987",
        "3", 3, "Ambiguous wall-clock mapping: epoch readings move backwards");
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void identicalRepeatedReadingsCountAsEvidenceWithoutMakingTheMappingAmbiguous(String format)
      throws Exception {
    var path = fixture("systemTime",
        List.of(new Anchor(10, W), new Anchor(10, W), new Anchor(12, W + 2)),
        numbers(VALUE, new long[] {10, 11, 12}, 2, 4, 6));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {10, 11, 12}, new Long[] {W, W + 1, W + 2});
    assertBasis(result, VALUE, "systemTime", 3, "1767225599999990", "1767225599999990",
        "0", 0, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void eachNullReasonRetainsItsOwnCountWithinOneSeries(String format) throws Exception {
    var path = fixture("systemTime", List.of(new Anchor(1, Long.MAX_VALUE)),
        numbers(VALUE, new long[] {0, 1, 2, 3}, 2, 4, 6, 8));
    var result = read(path, VALUE, format, Map.of());
    // One sample precedes the anchor; the last two exceed a signed 64-bit UTC coordinate.
    assertUtc(result, VALUE, new long[] {0, 1, 2, 3},
        new Long[] {null, Long.MAX_VALUE, null, null});
    assertEquals(JsonParser.parseString("""
        {
          "entry": "systemTime",
          "reading_count": 1,
          "first_offset_us": "9223372036854775806",
          "last_offset_us": "9223372036854775806",
          "max_offset_change_us": "0",
          "rounding": "nearest_microsecond_ties_to_even",
          "null_sample_count": 3,
          "null_reasons": [
            {"reason": "Before the first valid wall-clock reading", "count": 1},
            {"reason": "Mapped UTC timestamp is outside the signed 64-bit microsecond range", "count": 2}
          ]
        }
        """), basis(result, VALUE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void halfMicrosecondsRoundTheFinalEpochToEvenInBothDirections(String format) throws Exception {
    var path = fixture("systemTime",
        List.of(new Anchor(10, W), new Anchor(12, W + 3), new Anchor(14, W + 6)),
        numbers(VALUE, new long[] {11, 13}, 1, 2));
    var result = read(path, VALUE, format, Map.of());
    // W is even: W + 1.5 rounds up to W + 2, W + 4.5 rounds down to W + 4.
    assertUtc(result, VALUE, new long[] {11, 13}, new Long[] {W + 2, W + 4});
    assertBasis(result, VALUE, "systemTime", 3, "1767225599999990", "1767225599999992",
        "1", 0, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void adjacentLargeLogCoordinatesKeepTheirOwnUtcMicroseconds(String format) throws Exception {
    long first = 9_007_199_254_740_992L;
    var path = fixture("/AdvantageKit/SystemStats/EpochTimeMicros",
        List.of(new Anchor(first, W), new Anchor(first + 2, W + 2)),
        numbers(VALUE, new long[] {first, first + 1, first + 2}, 1, 2, 3));
    var result = read(path, VALUE, format, Map.of());
    assertUtc(result, VALUE, new long[] {first, first + 1, first + 2},
        new Long[] {W, W + 1, W + 2});
    assertBasis(result, VALUE, "/AdvantageKit/SystemStats/EpochTimeMicros", 2,
        "-7239973654740992", "-7239973654740992", "0", 0, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void eachSeriesCountsItsOwnNullSamplesAndTheWindowKeepsBothClockAnchors(String format)
      throws Exception {
    var path = fixture("systemTime", workedAnchors(),
        numbers(VALUE, new long[] {4_000_000, 15_000_000, 25_000_000}, 2, 4, 8),
        numbers("/Late", new long[] {15_000_000, 25_000_000}, 10, 20));
    var all = read(path, VALUE + ",/Late", format, Map.of());
    assertEquals(1, basis(all, VALUE).get("null_sample_count").getAsInt());
    assertEquals(0, basis(all, "/Late").get("null_sample_count").getAsInt());
    assertUtc(all, "/Late", new long[] {15_000_000, 25_000_000},
        new Long[] {W + 6_000_000, W + 17_000_000});
    var window = read(path, VALUE, format, Map.of("start_time", "14", "end_time", "16"));
    assertUtc(window, VALUE, new long[] {15_000_000}, new Long[] {W + 6_000_000});
    assertBasis(window, VALUE, "systemTime", 2, "1767225590000000", "1767225592000000",
        "2000000", 0, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void reducedRowsMapExactBucketStartsAndPreserveEveryStatistic(String format) throws Exception {
    var path = fixture("systemTime", workedAnchors(),
        numbers(VALUE, new long[] {4_000_000, 5_000_000, 14_000_000, 15_000_000, 24_000_000, 25_000_000},
            1, 3, 5, 7, 9, 11));
    var result = read(path, VALUE, format,
        Map.of("start_time", "0", "end_time", "30", "max_points", "3"));
    assertUtc(result, VALUE, new long[] {0, 10_000_000, 20_000_000},
        new Long[] {null, W, W + 12_000_000});
    assertEquals(List.of(2.0, 2.0, 2.0), numericColumn(result, VALUE, "count"));
    assertEquals(List.of(1.0, 5.0, 9.0), numericColumn(result, VALUE, "min"));
    assertEquals(List.of(3.0, 7.0, 11.0), numericColumn(result, VALUE, "max"));
    assertEquals(List.of(2.0, 6.0, 10.0), numericColumn(result, VALUE, "mean"));
    assertEquals(List.of(1.0, 5.0, 9.0), numericColumn(result, VALUE, "first"));
    assertEquals(List.of(3.0, 7.0, 11.0), numericColumn(result, VALUE, "last"));
    assertBasis(result, VALUE, "systemTime", 2, "1767225590000000", "1767225592000000",
        "2000000", 1, BEFORE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void exactSamplesRequestedAsBucketsStillReceiveUtc(String format) throws Exception {
    var path = fixture("systemTime", workedAnchors(),
        numbers(VALUE, new long[] {15_000_000, 25_000_000}, 2, 4));
    var result = read(path, VALUE, format, Map.of("max_points", "10"));
    assertUtc(result, VALUE, new long[] {15_000_000, 25_000_000},
        new Long[] {W + 6_000_000, W + 17_000_000});
    assertEquals(List.of(1.0, 1.0), numericColumn(result, VALUE, "count"));
    for (String column : List.of("min", "max", "mean", "first", "last"))
      assertEquals(List.of(2.0, 4.0), numericColumn(result, VALUE, column), column);
    assertEquals(0, basis(result, VALUE).get("null_sample_count").getAsInt());
  }

  @Test void arrayRowsRepeatUtcWithoutLosingIndexesOrInflatingTheNullSampleCount() throws Exception {
    var path = fixture("systemTime", workedAnchors(),
        new Series(VALUE, "double[]", List.of(
            new Sample(4_000_000, new double[] {2, 4}),
            new Sample(15_000_000, new double[] {6, 8}))));
    var arrow = read(path, VALUE, "arrow", Map.of());
    assertUtc(arrow, VALUE, new long[] {4_000_000, 15_000_000}, new Long[] {null, W + 6_000_000});
    assertEquals(List.of(List.of(2.0, 4.0), List.of(6.0, 8.0)), arrowColumn(arrow, VALUE, "value"));
    var csv = read(path, VALUE, "csv", Map.of());
    assertEquals(List.of("timestamp_sec", "timestamp_utc", "index", "value"),
        csv.csv().get(VALUE).columns());
    assertEquals(List.of(
        List.of("4.0", "", "0", "2.0"),
        List.of("4.0", "", "1", "4.0"),
        List.of("15.0", "2026-01-01T00:00:06.000000Z", "0", "6.0"),
        List.of("15.0", "2026-01-01T00:00:06.000000Z", "1", "8.0")),
        csv.csv().get(VALUE).rows());
    assertEquals(basis(arrow, VALUE), basis(csv, VALUE));
    assertEquals(1, basis(csv, VALUE).get("null_sample_count").getAsInt());
  }

  @Test void nullValiditySurvivesTheArrowBatchBoundary() throws Exception {
    var samples = new ArrayList<Sample>();
    for (int i = 0; i < 4098; i++) samples.add(new Sample(i, (double) i));
    var path = fixture("systemTime", List.of(new Anchor(4097, W)),
        new Series(VALUE, "double", samples));
    var result = read(path, VALUE, "arrow", Map.of());
    assertEquals(List.of(4096L, 2L),
        result.arrow().batches().stream().map(ArrowSpecReader.Batch::rows).toList());
    var actual = arrowColumn(result, VALUE, "timestamp_utc");
    assertEquals(4098, actual.size());
    for (int i = 0; i < 4097; i++) assertNull(actual.get(i), "sample " + i);
    assertEquals(W, actual.get(4097));
    assertBasis(result, VALUE, "systemTime", 1, "1767225599995903", "1767225599995903",
        "0", 4097, BEFORE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void anEmptyWindowRetainsUtcSchemaAndZeroNullCount(String format) throws Exception {
    var path = fixture("systemTime", workedAnchors(),
        numbers(VALUE, new long[] {15_000_000}, 2));
    var result = read(path, VALUE, format, Map.of("start_time", "40", "end_time", "50"));
    assertUtc(result, VALUE, new long[0], new Long[0]);
    assertBasis(result, VALUE, "systemTime", 2, "1767225590000000", "1767225592000000",
        "2000000", 0, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arrow", "csv"})
  void omittedAndFalseOptionsPreserveTheLegacyRepresentation(String format) throws Exception {
    var path = fixture("systemTime", workedAnchors(),
        numbers(VALUE, new long[] {4_000_000, 15_000_000}, 2, 4));
    var omitted = get(path, VALUE, format, "", Map.of());
    var disabled = get(path, VALUE, format, "&utc=false", Map.of());
    assertEquals(200, omitted.statusCode());
    assertEquals(200, disabled.statusCode());
    assertArrayEquals(omitted.body(), disabled.body(), "Opting out preserves every legacy column and fact");
    var result = payload(disabled, format);
    assertFalse(metadata(result, VALUE).has("utc_basis"));
    if (format.equals("arrow"))
      assertEquals(List.of("timestamp", "value"),
          result.arrow().fields().stream().map(ArrowSpecReader.FieldSpec::name).toList());
    else assertEquals(List.of("timestamp_sec", "value"), result.csv().get(VALUE).columns());
    assertEquals(List.of(2.0, 4.0), numericColumn(result, VALUE, "value"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "yes", "1", "true&utc=false"})
  void malformedOrRepeatedUtcIsRefused(String spelling) throws Exception {
    var path = fixture("systemTime", workedAnchors(), numbers(VALUE, new long[] {15_000_000}, 2));
    var response = get(path, VALUE, "csv", "&utc=" + spelling, Map.of());
    assertEquals(400, response.statusCode(), "utc=" + spelling + " must not be silently ignored");
    assertTrue(JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8))
        .getAsJsonObject().get("error").getAsString().contains("utc"));
  }

  @Test void utcHasItsOwnValidatorAndRepeatedRequestsKeepTheCachedRepresentation() throws Exception {
    var path = fixture("systemTime", workedAnchors(), numbers(VALUE, new long[] {15_000_000}, 2));
    var original = get(path, VALUE, "arrow", "", Map.of());
    String oldTag = original.headers().firstValue("ETag").orElseThrow();
    var enabled = get(path, VALUE, "arrow", "&utc=true", Map.of("If-None-Match", oldTag));
    var result = payload(enabled, "arrow");
    assertUtc(result, VALUE, new long[] {15_000_000}, new Long[] {W + 6_000_000});
    String utcTag = enabled.headers().firstValue("ETag").orElseThrow();
    assertNotEquals(oldTag, utcTag);
    var repeated = get(path, VALUE, "arrow", "&utc=true", Map.of("If-None-Match", utcTag));
    assertEquals(304, repeated.statusCode());
    assertEquals(0, repeated.body().length);
  }

  @Test void csvUtcSizeEstimateCountsEveryExpandedArrayCell() throws Exception {
    var path = fixture("systemTime", List.of(new Anchor(0, W)),
        new Series(VALUE, "double[]", List.of(new Sample(0, new double[] {2, 4, 6}))));
    transport.setDataMaxBytes(119);
    try {
      // Existing estimate: 8 timestamp + 4 array offset + 3*8 payload = 36 bytes.
      assertEquals(200, get(path, VALUE, "csv", "&utc=false", Map.of()).statusCode());
      var refused = get(path, VALUE, "csv", "&utc=true", Map.of());
      assertEquals(413, refused.statusCode(),
          "Three 27-character UTC cells and their commas add 84 bytes, giving 120");
      var body = JsonParser.parseString(new String(refused.body(), StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(120, body.get("bytes").getAsLong());
      assertEquals(119, body.get("max_bytes").getAsLong());
    } finally {
      transport.setDataMaxBytes(DataEndpoint.DEFAULT_MAX_BYTES);
    }
  }

  @Test void csvUtcSizeEstimateIncludesTheExtendedYear() throws Exception {
    var path = fixture("systemTime", List.of(new Anchor(0, Long.MAX_VALUE)),
        numbers(VALUE, new long[] {0}, 2));
    transport.setDataMaxBytes(46);
    try {
      assertEquals(200, get(path, VALUE, "csv", "&utc=false", Map.of()).statusCode());
      var refused = get(path, VALUE, "csv", "&utc=true", Map.of());
      // The signed six-digit year has three more characters than an ordinary four-digit year.
      // 16 legacy bytes + 30 UTC text characters + one comma = 47 bytes.
      assertEquals(413, refused.statusCode(), "Extended UTC years must count every character");
      var body = JsonParser.parseString(new String(refused.body(), StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(47, body.get("bytes").getAsLong());
      assertEquals(46, body.get("max_bytes").getAsLong());
    } finally {
      transport.setDataMaxBytes(DataEndpoint.DEFAULT_MAX_BYTES);
    }
  }

  private static List<Anchor> workedAnchors() {
    return List.of(new Anchor(10_000_000, W), new Anchor(20_000_000, W + 12_000_000));
  }

  private static Series numbers(String name, long[] times, double... values) {
    assertEquals(times.length, values.length);
    var samples = new ArrayList<Sample>();
    for (int i = 0; i < times.length; i++) samples.add(new Sample(times[i], values[i]));
    return new Series(name, "double", samples);
  }

  private Path fixture(String clockName, List<Anchor> anchors, Series... series) throws IOException {
    var path = directory.resolve("synthetic-utc-" + (++nextFixture) + ".wpilog");
    try (var writer = new WpilogWriter(path, "synthetic UTC transport contract")) {
      if (clockName != null) {
        int clock = writer.start(clockName, "int64", "", 0);
        for (var anchor : anchors)
          writer.append(clock, anchor.logUs(), WpilogWriter.encodeInt64(anchor.epochUs()));
      }
      for (var item : series) {
        int entry = writer.start(item.name(), item.type(), "", 0);
        for (var sample : item.samples()) {
          byte[] bytes = sample.value() instanceof double[] array
              ? WpilogWriter.encodeDoubleArray(array)
              : WpilogWriter.encodeDouble(((Number) sample.value()).doubleValue());
          writer.append(entry, sample.logUs(), bytes);
        }
      }
    }
    return path;
  }

  private Payload read(Path path, String names, String format, Map<String, String> query) throws Exception {
    StringBuilder suffix = new StringBuilder("&utc=true");
    query.forEach((key, value) -> suffix.append('&').append(encode(key)).append('=').append(encode(value)));
    return payload(get(path, names, format, suffix.toString(), Map.of()), format);
  }

  private HttpResponse<byte[]> get(Path path, String names, String format, String suffix,
      Map<String, String> headers) throws Exception {
    String url = "http://127.0.0.1:" + transport.getPort() + HttpTransport.DATA_PATH
        + "?path=" + encode(path.toString()) + "&names=" + encode(names) + "&format=" + format + suffix;
    var request = HttpRequest.newBuilder(URI.create(url)).GET();
    headers.forEach(request::header);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static Payload payload(HttpResponse<byte[]> response, String format) {
    assertEquals(200, response.statusCode(), () -> new String(response.body(), StandardCharsets.UTF_8));
    if (format.equals("arrow")) {
      var stream = ArrowSpecReader.read(response.body());
      assertTrue(stream.endMarker());
      return new Payload(format, stream, Map.of(),
          JsonParser.parseString(stream.metadata().get("entries")).getAsJsonArray());
    }
    var sections = new LinkedHashMap<String, CsvSection>();
    JsonArray metadata = null;
    String entry = null;
    List<String> columns = null;
    var rows = new ArrayList<List<String>>();
    // Fixtures contain numeric cells only; split with -1 retains the independently parsed null cells.
    for (String line : new String(response.body(), StandardCharsets.UTF_8).lines().toList()) {
      if (line.startsWith("# entries: "))
        metadata = JsonParser.parseString(line.substring("# entries: ".length())).getAsJsonArray();
      else if (line.startsWith("# entry: ")) {
        if (entry != null) sections.put(entry, new CsvSection(columns, List.copyOf(rows)));
        entry = line.substring("# entry: ".length());
        columns = null;
        rows.clear();
      } else if (!line.isBlank() && !line.startsWith("#")) {
        var cells = Arrays.asList(line.split(",", -1));
        if (columns == null) columns = cells; else rows.add(cells);
      }
    }
    if (entry != null) sections.put(entry, new CsvSection(columns, List.copyOf(rows)));
    assertNotNull(metadata, "CSV must carry the per-series facts");
    return new Payload(format, null, sections, metadata);
  }

  private static void assertUtc(Payload result, String entry, long[] original, Long[] expected) {
    assertEquals(original.length, expected.length);
    if (result.format().equals("arrow")) {
      var fields = result.arrow().fields();
      assertTrue(fields.size() >= 2);
      assertEquals("timestamp", fields.get(0).name());
      assertEquals("timestamp[us]", fields.get(0).type());
      assertEquals("timestamp_utc", fields.get(1).name(),
          "utc=true must add its nullable UTC column instead of being silently ignored");
      assertEquals("timestamp[us],UTC", fields.get(1).type());
      assertTrue(fields.get(1).nullable());
      assertEquals(Arrays.stream(original).boxed().toList(), arrowColumn(result, entry, "timestamp"));
      assertEquals(Arrays.asList(expected), arrowColumn(result, entry, "timestamp_utc"));
    } else {
      var section = result.csv().get(entry);
      assertNotNull(section, entry);
      assertTrue(section.columns().size() >= 2,
          "utc=true must add its UTC column even when the requested window is empty");
      assertEquals("timestamp_sec", section.columns().get(0));
      assertEquals("timestamp_utc", section.columns().get(1),
          "utc=true must add its UTC column instead of being silently ignored");
      assertEquals(expected.length, section.rows().size());
      for (int i = 0; i < expected.length; i++) {
        assertEquals(original[i] / 1_000_000.0, Double.parseDouble(section.rows().get(i).get(0)));
        String cell = section.rows().get(i).get(1);
        if (expected[i] == null) assertEquals("", cell);
        else {
          assertTrue(cell.matches(".+\\.\\d{6}Z"), cell);
          var instant = Instant.parse(cell);
          long epochUs = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000),
              instant.getNano() / 1_000);
          assertEquals(expected[i].longValue(), epochUs, "CSV epoch at sample " + i);
        }
      }
    }
  }

  private static List<Object> arrowColumn(Payload result, String entry, String name) {
    int column = -1;
    for (int i = 0; i < result.arrow().fields().size(); i++)
      if (result.arrow().fields().get(i).name().equals(name)) column = i;
    assertTrue(column >= 0, "Arrow column " + name + " is required");
    var values = new ArrayList<Object>();
    for (var batch : result.arrow().batches())
      if (entry.equals(batch.metadata().get("entry"))) values.addAll(batch.columns().get(column));
    return values;
  }

  private static List<Double> numericColumn(Payload result, String entry, String column) {
    if (result.format().equals("arrow"))
      return arrowColumn(result, entry, column).stream().map(value -> ((Number) value).doubleValue()).toList();
    var section = result.csv().get(entry);
    int at = section.columns().indexOf(column);
    assertTrue(at >= 0, column);
    return section.rows().stream().map(row -> Double.valueOf(row.get(at))).toList();
  }

  private static JsonObject metadata(Payload result, String entry) {
    for (var element : result.entries()) {
      var value = element.getAsJsonObject();
      if (value.get("name").getAsString().equals(entry)) return value;
    }
    throw new AssertionError("Missing series metadata: " + entry);
  }

  private static JsonObject basis(Payload result, String entry) {
    var value = metadata(result, entry);
    assertTrue(value.has("utc_basis"), "UTC results must explain their wall-clock basis");
    return value.getAsJsonObject("utc_basis");
  }

  private static void assertBasis(Payload result, String entry, String clock, int readings,
      String first, String last, String change, int nulls, String reason) {
    var basis = basis(result, entry);
    if (clock == null) assertTrue(basis.get("entry").isJsonNull());
    else assertEquals(clock, basis.get("entry").getAsString());
    assertEquals(readings, basis.get("reading_count").getAsInt());
    assertStringOrNull(basis, "first_offset_us", first);
    assertStringOrNull(basis, "last_offset_us", last);
    assertStringOrNull(basis, "max_offset_change_us", change);
    assertEquals(nulls, basis.get("null_sample_count").getAsInt());
    assertEquals(ROUNDING, basis.get("rounding").getAsString());
    var reasons = basis.getAsJsonArray("null_reasons");
    assertNotNull(reasons);
    assertEquals(reason == null ? 0 : 1, reasons.size());
    if (reason != null) {
      assertEquals(reason, reasons.get(0).getAsJsonObject().get("reason").getAsString());
      assertEquals(nulls, reasons.get(0).getAsJsonObject().get("count").getAsInt());
    }
  }

  private static void assertStringOrNull(JsonObject basis, String name, String expected) {
    assertTrue(basis.has(name), name);
    if (expected == null) assertTrue(basis.get(name).isJsonNull(), name);
    else {
      assertTrue(basis.get(name).getAsJsonPrimitive().isString(), name + " must retain exact integer text");
      assertEquals(expected, basis.get(name).getAsString(), name);
    }
  }
}

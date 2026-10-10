/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.capture.context.ProviderStatus;
import org.triplehelix.wpilogmcp.capture.pull.PullCoordinator;
import org.triplehelix.wpilogmcp.config.ConfigLoader;
import org.triplehelix.wpilogmcp.config.MetricsConfig;
import org.triplehelix.wpilogmcp.mcp.MetricsEndpoint;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.LiveTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/** The manual's commands and names are executable claims, including names misspelled nowhere else. */
class OperationsClaimTest {
  private static final Path MANUAL = Path.of("doc/OPERATIONS.md");
  private static final Path SOURCE = Path.of("src/main/java/org/triplehelix/wpilogmcp");
  private static String manual;
  private static ToolRegistry registry;
  @TempDir Path temp;

  @BeforeAll static void readManualAndRegistry() throws Exception {
    manual = Files.readString(MANUAL);
    registry = new ToolRegistry();
    WpilogTools.registerAll(registry);
    LiveTools.registerAll(registry, null);
  }

  /** These are result fields, argument names, values and shell programs, not tool names.
   * Account for every bare identifier rather than scanning only names already in the registry:
   * that would silently skip a misspelled tool. New prose identifiers need this classification. */
  private static final Set<String> NON_TOOLS = Set.of(("""
      brownout_threshold_basis brownout_voltage_entry bus_name bytes_per_sec camera_settings
      capture cause channel_analysis chooser_entry complete confidence_level conflicts connect
      connected current_entry data_quality development df disabled diskcachesize dmesg end_reason end_time
      ended_at entries entry error event event_code field folder following gateway growing
      has_more high hint histogram host http imports inputs insufficient journalctl jvm jvm_crash
      kernel kind last_seconds last_sync limit listening log_directories looked_for low lvuser
      managed match_number match_type matching_reason measured_entry measurement_entry mechanism_name
      medium mirror name no_match not_applicable offline offset offset_ms ok origin partial path
      pattern pit port program rate_bytes reason records regex rio robot run sampling scatter
      scope server_analysis_directives servers setpoint_entry settle_sec sha256sum signal_key
      skipped source stall_current_threshold stand_down start start_time stated_robot status sync
      sync_status syslog tba tba_key team temperature_entry text_event_counts text_event_summary
      threshold threshold_ms time_series timeout_ms unassigned velocity_entry vision_entries
      voltage_analysis waiting warning windows year
      """).strip().split("\\s+"));

  private static List<String> code() {
    var result = new ArrayList<String>();
    var matcher = Pattern.compile("(?s)```[^\\r\\n]*\\R(.*?)```|(?<!`)`([^`\\r\\n]+)`(?!`)").matcher(manual);
    while (matcher.find()) result.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
    return result;
  }

  @Test void backtickedToolNamesExistInTheRegistry() {
    var mentioned = new HashSet<String>();
    for (String token : code()) {
      if (!token.matches("[a-z][a-z0-9_]+") || token.startsWith("wpilog_")) continue;
      if (NON_TOOLS.contains(token)) continue;
      assertNotNull(registry.getTool(token), "OPERATIONS.md: unknown tool `" + token + "`");
      mentioned.add(token);
    }
    assertFalse(mentioned.isEmpty(), "The manual must actually exercise the tool-name check");
  }

  @Test void commandsAreVerbsPrintedByUsage() throws Exception {
    String main = Files.readString(SOURCE.resolve("Main.java"));
    String usage = main.substring(main.indexOf("private static void printUsage()"));
    var verbs = matches("logger\\(\\)\\.info\\(\"  ([a-z][a-z-]*)\\s", usage);
    var shown = new HashSet<String>();
    for (String snippet : code()) shown.addAll(matches("\\bwpilog-mcp\"?[ \\t]+([a-z][a-z-]*)\\b", snippet));
    assertFalse(shown.isEmpty());
    for (String verb : shown) assertTrue(verbs.contains(verb), "OPERATIONS.md: verb absent from printUsage: " + verb);
  }

  @Test void yamlExamplesUseAcceptedKeysAndLoadAsWritten() throws Exception {
    var examples = new ArrayList<String>();
    var blocks = Pattern.compile("(?ms)^```yaml\\R(.*?)^```").matcher(manual);
    while (blocks.find()) {
      String block = blocks.group(1);
      // Section 7 adds an indented fragment to the preceding complete pit configuration.
      if (Character.isWhitespace(block.charAt(0))) {
        assertFalse(examples.isEmpty(), "YAML fragment needs its preceding configuration");
        int last = examples.size() - 1;
        examples.set(last, examples.get(last) + block);
      } else examples.add(block);
    }
    assertFalse(examples.isEmpty());
    String loader = Files.readString(SOURCE.resolve("config/ConfigLoader.java"));
    String parser = loader.substring(loader.indexOf("private ServerConfig parseServerBlock("),
        loader.indexOf("private void validate("));
    var keys = matches("\"([a-z][a-z_]*)\"", parser);
    var rootKeys = new HashSet<>(keys); rootKeys.addAll(List.of("servers", "defaults"));
    int index = 0;
    for (String example : examples) {
      Map<?, ?> root = new org.yaml.snakeyaml.Yaml().load(example);
      assertKeys(root, rootKeys, "root");
      if (root.get("defaults") instanceof Map<?, ?> defaults) assertKeys(defaults, keys, "defaults");
      Map<?, ?> servers = assertInstanceOf(Map.class, root.get("servers"));
      var file = Files.writeString(temp.resolve("example-" + index++ + ".yaml"), example);
      for (var entry : servers.entrySet()) {
        assertKeys(assertInstanceOf(Map.class, entry.getValue()), keys, "servers." + entry.getKey());
        // Nested capture/context/etc. parsers reject unknown keys themselves; this also
        // checks their types, bounds and the combined fragment, without starting a server.
        assertDoesNotThrow(() -> new ConfigLoader(name -> null).loadDetailed(entry.getKey().toString(), file));
      }
    }
  }

  private static void assertKeys(Map<?, ?> map, Set<String> accepted, String at) {
    for (Object key : map.keySet()) assertTrue(accepted.contains(key), "OPERATIONS.md: unknown YAML key " + at + "." + key);
  }

  @Test void providerNamesAndStatesComeFromTheirPublishedSnapshots() throws Exception {
    Path providers = SOURCE.resolve("capture/context");
    String context = Files.readString(providers.resolve("ContextProviders.java"));
    String photon = Files.readString(providers.resolve("PhotonVisionProvider.java"));
    String jvm = Files.readString(providers.resolve("JvmProvider.java"));
    String tail = Files.readString(providers.resolve("TailProvider.java"));
    for (var name : Map.of("roboRIO", context, "jvm", jvm).entrySet()) {
      assertTrue(manual.contains("`" + name.getKey() + "`"));
      assertTrue(name.getValue().contains("new ProviderStatus(\"" + name.getKey() + "\""), name.getKey());
    }
    assertTrue(manual.contains("`tail/robot/program_console`"));
    assertTrue(context.contains("new ProviderStatus(\"tail/\""));
    assertTrue(context.contains("tail.host == null ? \"robot\" : tail.host.address()"));
    assertTrue(context.contains("tail.config.role()"));
    String config = Files.readString(SOURCE.resolve("config/ProviderConfig.java"));
    assertTrue(config.contains("new Tail(null, pull.ssh(), CONSOLE, \"program_console\")"));
    assertTrue(manual.contains("`photonvision/<host>:5800`"));
    assertTrue(photon.contains("new ProviderStatus(\"photonvision/\" + address.getAuthority()"));
    String hosts = """
        servers:
          pit:
            transport: http
            capture: {robot: {host: example.invalid}, store: ./store}
            context: {photonvision: [example.invalid]}
        """;
    var path = Files.writeString(temp.resolve("photon.yaml"), hosts);
    assertEquals("example.invalid:5800", new ConfigLoader(name -> null).loadDetailed("pit", path)
        .config().context().photonvision().get(0).getAuthority());
    String source = context + photon + jvm + tail;
    var states = matches("\\bstate\\(\"([a-z_]+)\"", source);
    states.addAll(matches("\\b(?:state|statsState)\\s*=\\s*\"([a-z_]+)\"", source));
    for (String state : List.of("sampling", "following", "waiting", "offline", "stand_down")) {
      assertTrue(manual.contains("`" + state + "`"), state);
      assertTrue(states.contains(state), "OPERATIONS.md: state never published by providers: " + state);
    }
  }

  @Test void metricNamesAreEmittedByTheEndpoint() {
    var provider = new ProviderStatus("synthetic", "offline", null, 1, null, null, 0, 0, 0, 0, 0, 0);
    var live = new LiveCapture.Metrics(false, "", null, Map.of(), null, null,
        Map.of("synthetic", new PullCoordinator.Progress(0, 0, 0, 0)), List.of(provider));
    String rendered = MetricsEndpoint.render(MetricsConfig.DEFAULT, live, MetricsEndpoint.Components.NONE,
        new MetricsEndpoint.Jvm(List.of(), List.of()));
    var exposed = matches("(?m)^(wpilog_[a-z_]+)(?:\\{| )", rendered);
    var mentioned = matches("\\b(wpilog_[a-z_]+)\\b", manual);
    assertFalse(mentioned.isEmpty());
    for (String metric : mentioned) assertTrue(exposed.contains(metric), "OPERATIONS.md: metric not exposed: " + metric);
  }

  @Test void relativeLinksResolveToDocumentHeadings() throws Exception {
    var links = Pattern.compile("\\[[^\\]]+\\]\\(([^\\s)]+)\\)").matcher(manual);
    int checked = 0;
    while (links.find()) {
      String link = links.group(1);
      if (link.matches("[a-zA-Z][a-zA-Z0-9+.-]*:.*")) continue;
      String[] parts = link.split("#", 2);
      Path target = parts[0].isEmpty() ? MANUAL : MANUAL.getParent().resolve(parts[0]).normalize();
      assertTrue(Files.isRegularFile(target), "OPERATIONS.md: missing link target " + link);
      var headings = headings(Files.readString(target));
      assertFalse(headings.isEmpty(), "OPERATIONS.md: target has no heading " + link);
      if (parts.length == 2) assertTrue(headings.contains(parts[1]), "OPERATIONS.md: missing heading " + link);
      checked++;
    }
    assertTrue(checked > 0, "The manual must actually exercise the relative-link check");
  }

  private static Set<String> headings(String markdown) {
    var result = new HashSet<String>(); var counts = new HashMap<String, Integer>();
    String prose = markdown.replaceAll("(?ms)^ *```[^\\r\\n]*\\R.*?^ *```[^\\r\\n]*(?:\\R|$)", "");
    var matcher = Pattern.compile("(?m)^#{1,6} +(.+)").matcher(prose);
    while (matcher.find()) {
      String slug = matcher.group(1).strip().replaceAll("<[^>]+>", "")
          .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_ -]", "").replace(' ', '-');
      int duplicate = counts.merge(slug, 1, Integer::sum) - 1;
      result.add(slug + (duplicate == 0 ? "" : "-" + duplicate));
    }
    return result;
  }

  private static Set<String> matches(String regex, String text) {
    var values = new HashSet<String>(); var matcher = Pattern.compile(regex).matcher(text);
    while (matcher.find()) values.add(matcher.group(1));
    return values;
  }
}

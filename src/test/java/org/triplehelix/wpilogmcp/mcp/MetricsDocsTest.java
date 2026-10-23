/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.config.MetricsConfig;

class MetricsDocsTest {
  @Test void documentationNamesEveryKeyAndMetricAndProvisioningConnectsTheDashboard() throws Exception {
    String guide = Files.readString(Path.of("doc/pit-server/STANDALONE.md"));
    for (String key : MetricsConfig.KEYS) assertTrue(guide.contains("`metrics." + key + "`"), key);
    var names = java.util.regex.Pattern.compile("\"(wpilog_[a-z_]+)\"").matcher(
        Files.readString(Path.of("src/main/java/org/triplehelix/wpilogmcp/mcp/MetricsEndpoint.java")));
    while (names.find()) assertTrue(guide.contains("`" + names.group(1) + "`"), names.group(1));
    assertTrue(guide.contains("sampled view, not the record"));
    var yaml = new org.yaml.snakeyaml.Yaml(); var base = Path.of("doc/pit-server/metrics");
    Map<String, Object> compose = yaml.load(Files.readString(base.resolve("compose.yaml")));
    var services = (Map<?, ?>) compose.get("services");
    for (String service : List.of("prometheus", "grafana")) {
      var spec = (Map<?, ?>) services.get(service);
      assertFalse(spec.get("image").toString().endsWith(":latest"));
      assertTrue(((List<?>) spec.get("ports")).stream().allMatch(p -> p.toString().startsWith("127.0.0.1:")));
    }
    Map<String, Object> prometheus = yaml.load(Files.readString(base.resolve("prometheus.yml")));
    var scrape = (Map<?, ?>) ((List<?>) prometheus.get("scrape_configs")).get(0);
    assertEquals("/metrics", scrape.get("metrics_path")); assertEquals("pit", scrape.get("job_name"));
    Map<String, Object> datasource = yaml.load(Files.readString(base.resolve("provisioning/datasources/pit.yaml")));
    var source = (Map<?, ?>) ((List<?>) datasource.get("datasources")).get(0);
    assertEquals("http://prometheus:9090", source.get("url")); assertEquals("prometheus", source.get("type"));
    Map<String, Object> provision = yaml.load(Files.readString(base.resolve("provisioning/dashboards/pit.yaml")));
    var provider = (Map<?, ?>) ((List<?>) provision.get("providers")).get(0);
    assertEquals("/etc/grafana/dashboards", ((Map<?, ?>) provider.get("options")).get("path"));
    var dashboard = JsonParser.parseString(Files.readString(base.resolve("pit-dashboard.json"))).getAsJsonObject();
    for (var panel : dashboard.getAsJsonArray("panels")) {
      var value = panel.getAsJsonObject();
      assertEquals(source.get("uid"), value.getAsJsonObject("datasource").get("uid").getAsString());
      String expression = value.getAsJsonArray("targets").get(0).getAsJsonObject().get("expr").getAsString();
      assertTrue(expression.contains("job=\"pit\""));
      assertTrue(value.get("description").getAsString().contains("capture file is the record"));
    }
    var variables = dashboard.getAsJsonObject("templating").getAsJsonArray("list");
    assertEquals(5, variables.size());
    variables.forEach(v -> assertEquals("", v.getAsJsonObject().getAsJsonObject("current").get("value").getAsString()));
    assertEquals(12, dashboard.getAsJsonArray("panels").size());
    assertTrue(Files.readString(Path.of(".github/workflows/ci.yml")).contains("python3 ci/check_metrics.py"));
  }

  /**
   * The recorded dashboard reads what the server records, through a pinned Infinity whose base
   * URL is the server: every panel asks for the open capture by Grafana's own time range, in
   * buckets, and reads the CSV the endpoint writes, so a panel cannot drift from the route.
   */
  @Test void theRecordedDashboardReadsTheOpenCaptureThroughAPinnedInfinity() throws Exception {
    var yaml = new org.yaml.snakeyaml.Yaml(); var base = Path.of("doc/pit-server/metrics");
    Map<String, Object> compose = yaml.load(Files.readString(base.resolve("compose.yaml")));
    var grafana = (Map<?, ?>) ((Map<?, ?>) compose.get("services")).get("grafana");
    var plugin = ((Map<?, ?>) grafana.get("environment")).get("GF_PLUGINS_PREINSTALL_SYNC").toString();
    assertTrue(plugin.matches("yesoreyeram-infinity-datasource@\\d+\\.\\d+\\.\\d+"), "Infinity pinned to a release: " + plugin);
    assertTrue(((List<?>) grafana.get("extra_hosts")).contains("host.docker.internal:host-gateway"), "Grafana reaches the server as Prometheus does");
    assertTrue(((List<?>) grafana.get("volumes")).contains("./pit-recording-dashboard.json:/etc/grafana/dashboards/pit-recording-dashboard.json:ro"));
    Map<String, Object> provisioned = yaml.load(Files.readString(base.resolve("provisioning/datasources/pit.yaml")));
    var infinity = ((List<?>) provisioned.get("datasources")).stream().map(d -> (Map<?, ?>) d)
        .filter(d -> "yesoreyeram-infinity-datasource".equals(d.get("type"))).findFirst().orElseThrow();
    assertEquals("http://host.docker.internal:2363", infinity.get("url"), "The data source's base URL is the server");
    assertEquals(List.of(infinity.get("url")), ((Map<?, ?>) infinity.get("jsonData")).get("allowedHosts"));
    var dashboard = JsonParser.parseString(Files.readString(base.resolve("pit-recording-dashboard.json"))).getAsJsonObject();
    assertEquals("5s", dashboard.get("refresh").getAsString());
    assertEquals("now-5m", dashboard.getAsJsonObject("time").get("from").getAsString());
    var variables = new java.util.HashSet<String>();
    for (var variable : dashboard.getAsJsonObject("templating").getAsJsonArray("list")) {
      var value = variable.getAsJsonObject();
      assertEquals("textbox", value.get("type").getAsString());
      assertEquals("", value.getAsJsonObject("current").get("value").getAsString(), "An entry box starts empty: the server proposes no entry");
      variables.add(value.get("name").getAsString());
    }
    int plots = 0;
    for (var panel : dashboard.getAsJsonArray("panels")) {
      var value = panel.getAsJsonObject();
      if (value.get("type").getAsString().equals("text")) continue;
      plots++;
      assertEquals(infinity.get("uid"), value.getAsJsonObject("datasource").get("uid").getAsString());
      var target = value.getAsJsonArray("targets").get(0).getAsJsonObject();
      assertEquals("csv", target.get("type").getAsString());
      assertEquals("backend", target.get("parser").getAsString());
      assertEquals(HttpTransport.DATA_PATH, target.get("url").getAsString());
      assertEquals("#", target.getAsJsonObject("csv_options").get("comment").getAsString(), "The endpoint's metadata lines drop out");
      var params = new java.util.HashMap<String, String>();
      target.getAsJsonObject("url_options").getAsJsonArray("params")
          .forEach(p -> params.put(p.getAsJsonObject().get("key").getAsString(), p.getAsJsonObject().get("value").getAsString()));
      assertEquals("current", params.get("capture"));
      assertEquals("csv", params.get("format"));
      assertEquals("${__from:date:iso}", params.get("start_utc"));
      assertEquals("${__to:date:iso}", params.get("end_utc"));
      assertTrue(Integer.parseInt(params.get("max_points")) > 0, "A panel always asks for buckets");
      assertTrue(variables.contains(params.get("names").substring(1)), "Each panel's entry comes from its box");
      var columns = new java.util.HashMap<String, String>();
      target.getAsJsonArray("columns").forEach(c -> columns.put(c.getAsJsonObject().get("selector").getAsString(), c.getAsJsonObject().get("type").getAsString()));
      assertEquals(Map.of("timestamp_utc", "timestamp", "min", "number", "max", "number", "mean", "number"), columns);
    }
    assertEquals(variables.size(), plots, "One panel for each entry box");
    assertTrue(Files.readString(Path.of("doc/pit-server/STANDALONE.md")).contains("metrics/pit-recording-dashboard.json"));
  }
}

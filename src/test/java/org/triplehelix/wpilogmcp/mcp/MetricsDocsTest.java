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
    String guide = Files.readString(Path.of("doc/STANDALONE.md"));
    for (String key : MetricsConfig.KEYS) assertTrue(guide.contains("`metrics." + key + "`"), key);
    var names = java.util.regex.Pattern.compile("\"(wpilog_[a-z_]+)\"").matcher(
        Files.readString(Path.of("src/main/java/org/triplehelix/wpilogmcp/mcp/MetricsEndpoint.java")));
    while (names.find()) assertTrue(guide.contains("`" + names.group(1) + "`"), names.group(1));
    assertTrue(guide.contains("sampled view, not the record"));
    var yaml = new org.yaml.snakeyaml.Yaml(); var base = Path.of("doc/metrics");
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
}

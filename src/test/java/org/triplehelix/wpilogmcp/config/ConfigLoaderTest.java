package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ConfigLoader")
class ConfigLoaderTest {

  @TempDir
  Path tempDir;

  private Path writeJson(String json) throws IOException {
    var file = tempDir.resolve("servers.json");
    Files.writeString(file, json);
    return file;
  }

  private Path writeYaml(String yaml) throws IOException {
    var file = tempDir.resolve("servers.yaml");
    Files.writeString(file, yaml);
    return file;
  }

  private Path writeFile(String name, String content) throws IOException {
    var file = tempDir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  // ==================== JSON Parsing ====================

  @Nested
  @DisplayName("JSON parsing")
  class JsonParsingTests {

    @Test
    @DisplayName("loads a minimal valid config")
    void loadsMinimalConfig() throws Exception {
      var file = writeJson("""
          {
            "servers": {
              "dev": { "logdir": "/logs", "transport": "stdio" }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals("dev", config.name());
      assertEquals("/logs", config.logdir());
      assertEquals("stdio", config.effectiveTransport());
    }

    @Test
    @DisplayName("loads a config with all fields")
    void loadsFullConfig() throws Exception {
      var file = writeJson("""
          {
            "servers": {
              "comp": {
                "logdir": "/media/usb",
                "team": 2363,
                "tba_key": "test_key",
                "transport": "http",
                "port": 8080,
                "diskcachedir": "/cache",
                "diskcachesize": 16384,
                "diskcachedisable": false,
                "debug": true,
                "scandepth": 10,
                "exportdir": "/exports"
              }
            }
          }
          """);

      var config = new ConfigLoader().load("comp", file);

      assertEquals("comp", config.name());
      assertEquals("/media/usb", config.logdir());
      assertEquals(2363, config.team());
      assertEquals("test_key", config.tbaKey());
      assertTrue(config.isHttp());
      assertEquals(8080, config.effectivePort());
      assertEquals("/cache", config.diskcachedir());
      assertEquals(16384L, config.diskcachesize());
      assertFalse(config.diskcachedisable());
      assertTrue(config.debug());
      assertEquals(10, config.scandepth());
      assertEquals("/exports", config.exportdir());
    }

    @Test
    @DisplayName("unset fields are null")
    void unsetFieldsAreNull() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs" } } }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertNull(config.team());
      assertNull(config.tbaKey());
      assertNull(config.transport());
      assertNull(config.port());
      assertNull(config.diskcachedir());
      assertNull(config.diskcachesize());
      assertNull(config.diskcachedisable());
      assertNull(config.debug());
      assertNull(config.scandepth());
      assertNull(config.exportdir());
    }

    @Test
    @DisplayName("explicit JSON null values are treated as unset")
    void jsonNullTreatedAsUnset() throws Exception {
      var file = writeJson("""
          {
            "servers": {
              "dev": { "logdir": "/logs", "team": null, "tba_key": null }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertNull(config.team());
      assertNull(config.tbaKey());
    }

    @Test
    @DisplayName("throws for unknown config name with available list")
    void throwsForUnknownName() throws Exception {
      var file = writeJson("""
          {
            "servers": {
              "alpha": { "logdir": "/a" },
              "beta": { "logdir": "/b" }
            }
          }
          """);

      var ex = assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("gamma", file));
      assertTrue(ex.getMessage().contains("Unknown server configuration 'gamma'"));
      assertTrue(ex.getMessage().contains("alpha"));
      assertTrue(ex.getMessage().contains("beta"));
    }

    @Test
    @DisplayName("throws for missing servers section")
    void throwsForMissingServers() throws Exception {
      var file = writeJson("""
          { "defaults": { "team": 1 } }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws when servers is not an object")
    void throwsWhenServersIsNotObject() throws Exception {
      var file = writeJson("""
          { "servers": ["not", "an", "object"] }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws for empty servers section")
    void throwsForEmptyServers() throws Exception {
      var file = writeJson("""
          { "servers": {} }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws for malformed JSON")
    void throwsForMalformedJson() throws Exception {
      var file = writeJson("{ not valid json }}}");

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws when JSON root is an array")
    void throwsWhenJsonRootIsArray() throws Exception {
      var file = writeJson("[1, 2, 3]");

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws for non-existent explicit config file")
    void throwsForMissingFile() {
      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", tempDir.resolve("nonexistent.json")));
    }

    @Test
    @DisplayName("ignores unknown top-level keys")
    void ignoresUnknownKeys() throws Exception {
      var file = writeJson("""
          {
            "some_future_field": "ignored",
            "servers": {
              "dev": { "logdir": "/logs" }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }
  }

  // ==================== YAML Parsing ====================

  @Nested
  @DisplayName("YAML parsing")
  class YamlParsingTests {

    @Test
    @DisplayName("loads a minimal YAML config")
    void loadsMinimalYaml() throws Exception {
      var file = writeYaml("""
          servers:
            dev:
              logdir: /logs
              transport: stdio
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals("dev", config.name());
      assertEquals("/logs", config.logdir());
      assertEquals("stdio", config.effectiveTransport());
    }

    @Test
    @DisplayName("loads YAML with all fields")
    void loadsFullYaml() throws Exception {
      var file = writeYaml("""
          servers:
            comp:
              logdir: /media/usb
              team: 2363
              tba_key: test_key
              transport: http
              port: 8080
              diskcachedir: /cache
              diskcachesize: 16384
              diskcachedisable: false
              debug: true
              scandepth: 10
              exportdir: /exports
          """);

      var config = new ConfigLoader().load("comp", file);

      assertEquals("/media/usb", config.logdir());
      assertEquals(2363, config.team());
      assertEquals("test_key", config.tbaKey());
      assertTrue(config.isHttp());
      assertEquals(8080, config.effectivePort());
      assertEquals("/cache", config.diskcachedir());
      assertEquals(16384L, config.diskcachesize());
      assertFalse(config.diskcachedisable());
      assertTrue(config.debug());
      assertEquals(10, config.scandepth());
      assertEquals("/exports", config.exportdir());
    }

    @Test
    @DisplayName("supports line comments")
    void supportsLineComments() throws Exception {
      var file = writeYaml("""
          # This is a full-line comment
          servers:
            dev:
              logdir: /logs
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }

    @Test
    @DisplayName("supports inline comments")
    void supportsInlineComments() throws Exception {
      var file = writeYaml("""
          servers:
            dev:
              logdir: /logs  # log directory
              team: 2363     # our team
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
      assertEquals(2363, config.team());
    }

    @Test
    @DisplayName("handles .yml extension")
    void handlesYmlExtension() throws Exception {
      var file = writeFile("config.yml", """
          servers:
            dev:
              logdir: /logs
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }

    @Test
    @DisplayName("throws for empty YAML file")
    void throwsForEmptyYaml() throws Exception {
      var file = writeYaml("");

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws for YAML that is only comments")
    void throwsForCommentsOnlyYaml() throws Exception {
      var file = writeYaml("""
          # just comments
          # nothing else
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws for non-mapping YAML (list)")
    void throwsForListYaml() throws Exception {
      var file = writeYaml("- just\n- a\n- list\n");

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws for non-mapping YAML (scalar)")
    void throwsForScalarYaml() throws Exception {
      var file = writeYaml("just a string");

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("throws for invalid YAML syntax")
    void throwsForInvalidYaml() throws Exception {
      var file = writeYaml("servers: [not: valid: yaml: {{{}}}");

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("YAML null values are treated as unset")
    void yamlNullTreatedAsUnset() throws Exception {
      var file = writeYaml("""
          servers:
            dev:
              logdir: /logs
              team: ~
              tba_key: null
          """);

      var config = new ConfigLoader().load("dev", file);
      assertNull(config.team());
      assertNull(config.tbaKey());
    }

    @Test
    @DisplayName("YAML boolean values are parsed correctly")
    void yamlBooleanValues() throws Exception {
      var file = writeYaml("""
          servers:
            dev:
              logdir: /logs
              debug: true
              diskcachedisable: false
          """);

      var config = new ConfigLoader().load("dev", file);
      assertTrue(config.debug());
      assertFalse(config.diskcachedisable());
    }

    @Test
    @DisplayName("YAML preserves quoted strings that look like booleans")
    void yamlQuotedStringsPreserved() throws Exception {
      var file = writeYaml("""
          servers:
            dev:
              logdir: /logs
              tba_key: "true"
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("true", config.tbaKey());
    }

    @Test
    @DisplayName("handles multiline YAML with blank lines")
    void handlesBlankLines() throws Exception {
      var file = writeYaml("""
          team: 2363

          logdir: /logs

          servers:

            dev:
              transport: stdio
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(2363, config.team());
    }
  }

  // ==================== Format Detection ====================

  @Nested
  @DisplayName("Format detection")
  class FormatDetectionTests {

    @Test
    @DisplayName(".json extension uses JSON parser")
    void jsonExtension() throws Exception {
      var file = writeFile("test.json", """
          { "servers": { "dev": { "logdir": "/logs" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }

    @Test
    @DisplayName(".yaml extension uses YAML parser")
    void yamlExtension() throws Exception {
      var file = writeFile("test.yaml", """
          servers:
            dev:
              logdir: /logs
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }

    @Test
    @DisplayName(".yml extension uses YAML parser")
    void ymlExtension() throws Exception {
      var file = writeFile("test.yml", """
          servers:
            dev:
              logdir: /logs
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }

    @Test
    @DisplayName("extension detection is case-insensitive")
    void caseInsensitiveExtension() throws Exception {
      var file = writeFile("test.YAML", """
          servers:
            dev:
              logdir: /logs
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }

    @Test
    @DisplayName("unknown extension falls back to JSON parser")
    void unknownExtensionDefaultsToJson() throws Exception {
      var file = writeFile("test.conf", """
          { "servers": { "dev": { "logdir": "/logs" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }
  }

  // ==================== JSON/YAML Parity ====================

  @Nested
  @DisplayName("JSON/YAML parity")
  class ParityTests {

    @Test
    @DisplayName("same config produces identical results in both formats")
    void identicalResults() throws Exception {
      var jsonFile = writeFile("parity.json", """
          {
            "team": 2363,
            "logdir": "/shared",
            "servers": {
              "dev": {
                "transport": "stdio",
                "tba_key": "key123"
              },
              "http": {
                "transport": "http",
                "port": 8080
              }
            }
          }
          """);

      var yamlFile = writeFile("parity.yaml", """
          team: 2363
          logdir: /shared
          servers:
            dev:
              transport: stdio
              tba_key: key123
            http:
              transport: http
              port: 8080
          """);

      var loader = new ConfigLoader();
      var jsonDev = loader.load("dev", jsonFile);
      var yamlDev = loader.load("dev", yamlFile);

      assertEquals(jsonDev.name(), yamlDev.name());
      assertEquals(jsonDev.logdir(), yamlDev.logdir());
      assertEquals(jsonDev.team(), yamlDev.team());
      assertEquals(jsonDev.tbaKey(), yamlDev.tbaKey());
      assertEquals(jsonDev.effectiveTransport(), yamlDev.effectiveTransport());

      var jsonHttp = loader.load("http", jsonFile);
      var yamlHttp = loader.load("http", yamlFile);

      assertEquals(jsonHttp.effectivePort(), yamlHttp.effectivePort());
      assertEquals(jsonHttp.team(), yamlHttp.team());
      assertEquals(jsonHttp.logdir(), yamlHttp.logdir());
    }

    @Test
    @DisplayName("env var interpolation works identically in both formats")
    void envVarParity() throws Exception {
      var jsonFile = writeFile("env.json", """
          {
            "servers": {
              "dev": { "logdir": "/logs", "tba_key": "${MY_KEY}" }
            }
          }
          """);

      var yamlFile = writeFile("env.yaml", """
          servers:
            dev:
              logdir: /logs
              tba_key: ${MY_KEY}
          """);

      var loader = new ConfigLoader(name -> "MY_KEY".equals(name) ? "secret" : null);

      assertEquals(
          loader.load("dev", jsonFile).tbaKey(),
          loader.load("dev", yamlFile).tbaKey());
    }
  }

  // ==================== Defaults Merging (3-layer) ====================

  @Nested
  @DisplayName("Defaults merging (3-layer)")
  class DefaultsMergingTests {

    @Test
    @DisplayName("server inherits from explicit defaults block")
    void inheritsFromExplicitDefaults() throws Exception {
      var file = writeJson("""
          {
            "defaults": { "team": 2363, "tba_key": "default_key" },
            "servers": {
              "dev": { "logdir": "/logs", "transport": "stdio" }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals("/logs", config.logdir());
      assertEquals(2363, config.team());
      assertEquals("default_key", config.tbaKey());
    }

    @Test
    @DisplayName("server overrides explicit defaults")
    void serverOverridesExplicitDefaults() throws Exception {
      var file = writeJson("""
          {
            "defaults": { "team": 2363 },
            "servers": {
              "comp": { "logdir": "/logs", "team": 9999 }
            }
          }
          """);

      var config = new ConfigLoader().load("comp", file);
      assertEquals(9999, config.team());
    }

    @Test
    @DisplayName("top-level properties act as defaults")
    void topLevelDefaults() throws Exception {
      var file = writeJson("""
          {
            "team": 2363,
            "logdir": "/shared",
            "tba_key": "top_key",
            "servers": {
              "dev": { "transport": "stdio" }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals("/shared", config.logdir());
      assertEquals(2363, config.team());
      assertEquals("top_key", config.tbaKey());
    }

    @Test
    @DisplayName("explicit defaults block overrides top-level for same field")
    void explicitDefaultsOverrideTopLevel() throws Exception {
      var file = writeJson("""
          {
            "team": 1111,
            "tba_key": "top_key",
            "defaults": { "team": 2363 },
            "servers": {
              "dev": { "logdir": "/logs" }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals(2363, config.team(), "explicit defaults block wins over top-level");
      assertEquals("top_key", config.tbaKey(), "top-level fills gaps in explicit defaults");
    }

    @Test
    @DisplayName("server block overrides both top-level and explicit defaults")
    void serverOverridesAllDefaults() throws Exception {
      var file = writeJson("""
          {
            "team": 1111,
            "defaults": { "team": 2222 },
            "servers": {
              "dev": { "logdir": "/logs", "team": 3333 }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(3333, config.team());
    }

    @Test
    @DisplayName("full 3-layer merge with all fields")
    void fullThreeLayerMerge() throws Exception {
      var file = writeJson("""
          {
            "team": 1000,
            "logdir": "/top-level",
            "tba_key": "top_key",
            "scandepth": 3,
            "defaults": {
              "team": 2000,
              "exportdir": "/default-exports",
              "diskcachesize": 8192
            },
            "servers": {
              "comp": {
                "team": 3000,
                "transport": "http",
                "port": 8080
              }
            }
          }
          """);

      var config = new ConfigLoader().load("comp", file);

      assertEquals(3000, config.team(), "server wins");
      assertEquals("/top-level", config.logdir(), "from top-level");
      assertEquals("top_key", config.tbaKey(), "from top-level");
      assertEquals("/default-exports", config.exportdir(), "from explicit defaults");
      assertEquals(8192L, config.diskcachesize(), "from explicit defaults");
      assertEquals(3, config.scandepth(), "from top-level");
      assertEquals(8080, config.effectivePort(), "from server");
      assertTrue(config.isHttp(), "from server");
    }

    @Test
    @DisplayName("works without any defaults")
    void worksWithoutDefaults() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs" } } }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals("/logs", config.logdir());
      assertNull(config.team());
      assertNull(config.tbaKey());
    }

    @Test
    @DisplayName("multiple servers share defaults independently")
    void multipleServersShareDefaults() throws Exception {
      var file = writeJson("""
          {
            "team": 2363,
            "tba_key": "shared_key",
            "servers": {
              "comp": { "logdir": "/comp", "transport": "http", "port": 2363 },
              "dev": { "logdir": "/dev", "transport": "stdio" }
            }
          }
          """);

      var loader = new ConfigLoader();
      var comp = loader.load("comp", file);
      var dev = loader.load("dev", file);

      assertEquals("/comp", comp.logdir());
      assertEquals("/dev", dev.logdir());
      assertEquals(2363, comp.team());
      assertEquals(2363, dev.team());
      assertEquals("shared_key", comp.tbaKey());
      assertEquals("shared_key", dev.tbaKey());
      assertTrue(comp.isHttp());
      assertFalse(dev.isHttp());
    }

    @Test
    @DisplayName("defaults merge all config fields")
    void defaultsMergeAllFields() throws Exception {
      var file = writeJson("""
          {
            "logdir": "/default-logs",
            "team": 100,
            "tba_key": "dkey",
            "transport": "http",
            "port": 9090,
            "diskcachedir": "/dcache",
            "diskcachesize": 4096,
            "diskcachedisable": true,
            "debug": true,
            "exportdir": "/dexport",
            "scandepth": 5,
            "servers": {
              "bare": {}
            }
          }
          """);

      var config = new ConfigLoader().load("bare", file);

      assertEquals("/default-logs", config.logdir());
      assertEquals(100, config.team());
      assertEquals("dkey", config.tbaKey());
      assertEquals("http", config.effectiveTransport());
      assertEquals(9090, config.effectivePort());
      assertEquals("/dcache", config.diskcachedir());
      assertEquals(4096L, config.diskcachesize());
      assertTrue(config.diskcachedisable());
      assertTrue(config.debug());
      assertEquals("/dexport", config.exportdir());
      assertEquals(5, config.scandepth());
    }

    @Test
    @DisplayName("YAML 3-layer merge works identically to JSON")
    void yamlThreeLayerMerge() throws Exception {
      var file = writeYaml("""
          team: 1111
          tba_key: top_key
          defaults:
            team: 2222
          servers:
            dev:
              logdir: /logs
              team: 3333
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals(3333, config.team(), "server wins");
      assertEquals("top_key", config.tbaKey(), "top-level fills gap");
    }

    @Test
    @DisplayName("merge preserves scandepth and exportdir from defaults")
    void mergePreservesScandepthAndExportdir() throws Exception {
      var file = writeJson("""
          {
            "defaults": { "scandepth": 8, "exportdir": "/default/exports" },
            "servers": {
              "dev": { "logdir": "/logs", "transport": "stdio" }
            }
          }
          """);

      var config = new ConfigLoader().load("dev", file);

      assertEquals(8, config.scandepth());
      assertEquals("/default/exports", config.exportdir());
    }
  }

  // ==================== Environment Variable Interpolation ====================

  @Nested
  @DisplayName("Environment variable interpolation")
  class InterpolationTests {

    @Test
    @DisplayName("interpolates single ${VAR}")
    void interpolatesSingleVar() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "tba_key": "${MY_KEY}" } } }
          """);

      var config = new ConfigLoader(n -> "MY_KEY".equals(n) ? "secret123" : null)
          .load("dev", file);

      assertEquals("secret123", config.tbaKey());
    }

    @Test
    @DisplayName("preserves literal ${VAR} when env var is unset")
    void preservesLiteralWhenUnset() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "tba_key": "${UNSET_VAR}" } } }
          """);

      var config = new ConfigLoader(n -> null).load("dev", file);

      assertEquals("${UNSET_VAR}", config.tbaKey());
    }

    @Test
    @DisplayName("interpolates multiple vars in one string")
    void interpolatesMultipleVars() {
      var loader = new ConfigLoader(n -> switch (n) {
        case "HOME" -> "/home/user";
        case "TEAM" -> "2363";
        default -> null;
      });

      assertEquals("/home/user/logs/2363", loader.interpolate("${HOME}/logs/${TEAM}"));
    }

    @Test
    @DisplayName("handles mixed resolved and unresolved vars")
    void mixedResolvedAndUnresolved() {
      var loader = new ConfigLoader(n -> "A".equals(n) ? "resolved" : null);

      assertEquals("resolved-${B}", loader.interpolate("${A}-${B}"));
    }

    @Test
    @DisplayName("handles null input")
    void handlesNull() {
      assertNull(new ConfigLoader().interpolate(null));
    }

    @Test
    @DisplayName("handles empty string")
    void handlesEmptyString() {
      assertEquals("", new ConfigLoader().interpolate(""));
    }

    @Test
    @DisplayName("passes through string without template markers")
    void passesPlainString() {
      assertEquals("plain string", new ConfigLoader().interpolate("plain string"));
    }

    @Test
    @DisplayName("var at start of string")
    void varAtStart() {
      var loader = new ConfigLoader(n -> "X".equals(n) ? "val" : null);
      assertEquals("val/rest", loader.interpolate("${X}/rest"));
    }

    @Test
    @DisplayName("var at end of string")
    void varAtEnd() {
      var loader = new ConfigLoader(n -> "X".equals(n) ? "val" : null);
      assertEquals("prefix/val", loader.interpolate("prefix/${X}"));
    }

    @Test
    @DisplayName("adjacent vars with no separator")
    void adjacentVars() {
      var loader = new ConfigLoader(n -> switch (n) {
        case "A" -> "hello";
        case "B" -> "world";
        default -> null;
      });
      assertEquals("helloworld", loader.interpolate("${A}${B}"));
    }

    @Test
    @DisplayName("handles env value containing special regex characters")
    void specialRegexCharsInValue() {
      var loader = new ConfigLoader(n -> "KEY".equals(n) ? "$100.00 (USD)" : null);
      assertEquals("$100.00 (USD)", loader.interpolate("${KEY}"));
    }

    @Test
    @DisplayName("handles env value containing ${} syntax")
    void envValueContainingDollarBrace() {
      var loader = new ConfigLoader(n -> "KEY".equals(n) ? "${NOT_A_VAR}" : null);
      assertEquals("${NOT_A_VAR}", loader.interpolate("${KEY}"));
    }

    @Test
    @DisplayName("interpolates in explicit defaults section")
    void interpolatesInDefaults() throws Exception {
      var file = writeJson("""
          {
            "defaults": { "tba_key": "${MY_KEY}" },
            "servers": { "dev": { "logdir": "/logs" } }
          }
          """);

      var config = new ConfigLoader(n -> "MY_KEY".equals(n) ? "resolved" : null)
          .load("dev", file);

      assertEquals("resolved", config.tbaKey());
    }

    @Test
    @DisplayName("interpolates in top-level defaults")
    void interpolatesInTopLevelDefaults() throws Exception {
      var file = writeJson("""
          {
            "tba_key": "${MY_KEY}",
            "servers": { "dev": { "logdir": "/logs" } }
          }
          """);

      var config = new ConfigLoader(n -> "MY_KEY".equals(n) ? "resolved" : null)
          .load("dev", file);

      assertEquals("resolved", config.tbaKey());
    }

    @Test
    @DisplayName("interpolates in logdir path")
    void interpolatesInLogdir() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "${LOG_BASE}/frc" } } }
          """);

      var config = new ConfigLoader(n -> "LOG_BASE".equals(n) ? "/mnt/data" : null)
          .load("dev", file);

      assertEquals("/mnt/data/frc", config.logdir());
    }

    @Test
    @DisplayName("interpolates in diskcachedir")
    void interpolatesInDiskcachedir() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "diskcachedir": "${CACHE}/.wpilog" } } }
          """);

      var config = new ConfigLoader(n -> "CACHE".equals(n) ? "/tmp" : null)
          .load("dev", file);

      assertEquals("/tmp/.wpilog", config.diskcachedir());
    }

    @Test
    @DisplayName("interpolates in exportdir")
    void interpolatesInExportdir() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "exportdir": "${OUT}/csv" } } }
          """);

      var config = new ConfigLoader(n -> "OUT".equals(n) ? "/output" : null)
          .load("dev", file);

      assertEquals("/output/csv", config.exportdir());
    }

    @Test
    @DisplayName("YAML env var interpolation works")
    void yamlInterpolation() throws Exception {
      var file = writeYaml("""
          servers:
            dev:
              logdir: /logs
              tba_key: ${MY_KEY}
          """);

      var config = new ConfigLoader(n -> "MY_KEY".equals(n) ? "secret" : null)
          .load("dev", file);

      assertEquals("secret", config.tbaKey());
    }
  }

  // ==================== Path Expansion ====================

  @Nested
  @DisplayName("Path expansion")
  class PathExpansionTests {

    private final String home = System.getProperty("user.home");

    @Test
    @DisplayName("expands ~/path")
    void expandsTildeSlash() {
      assertEquals(home + "/frc-logs", ConfigLoader.expandPath("~/frc-logs"));
    }

    @Test
    @DisplayName("expands bare ~")
    void expandsBareTilde() {
      assertEquals(home, ConfigLoader.expandPath("~"));
    }

    @Test
    @DisplayName("does not expand tilde in middle of path")
    void noMidPathTilde() {
      assertEquals("/foo/~/bar", ConfigLoader.expandPath("/foo/~/bar"));
    }

    @Test
    @DisplayName("does not expand ~user syntax")
    void noTildeUser() {
      assertEquals("~user/path", ConfigLoader.expandPath("~user/path"));
    }

    @Test
    @DisplayName("returns absolute paths unchanged")
    void absolutePathUnchanged() {
      assertEquals("/absolute/path", ConfigLoader.expandPath("/absolute/path"));
    }

    @Test
    @DisplayName("returns relative paths unchanged")
    void relativePathUnchanged() {
      assertEquals("relative/path", ConfigLoader.expandPath("relative/path"));
    }

    @Test
    @DisplayName("handles null")
    void handlesNull() {
      assertNull(ConfigLoader.expandPath(null));
    }

    @Test
    @DisplayName("handles empty string")
    void handlesEmptyString() {
      assertEquals("", ConfigLoader.expandPath(""));
    }

    @Test
    @DisplayName("expands tilde in logdir through full load")
    void expandsInLogdir() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "~/riologs" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(home + "/riologs", config.logdir());
    }

    @Test
    @DisplayName("expands tilde in diskcachedir through full load")
    void expandsInDiskcachedir() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "diskcachedir": "~/cache" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(home + "/cache", config.diskcachedir());
    }

    @Test
    @DisplayName("expands tilde in exportdir through full load")
    void expandsInExportdir() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "exportdir": "~/exports" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(home + "/exports", config.exportdir());
    }

    @Test
    @DisplayName("expands tilde in defaults too")
    void expandsInDefaults() throws Exception {
      var file = writeJson("""
          {
            "logdir": "~/default-logs",
            "servers": { "dev": {} }
          }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(home + "/default-logs", config.logdir());
    }

    @Test
    @DisplayName("interpolation happens before tilde expansion")
    void interpolationBeforeTildeExpansion() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "${PREFIX}/logs" } } }
          """);

      var config = new ConfigLoader(n -> "PREFIX".equals(n) ? "~" : null)
          .load("dev", file);

      assertEquals(home + "/logs", config.logdir());
    }
  }

  // ==================== Validation ====================

  @Nested
  @DisplayName("Validation")
  class ValidationTests {

    @Test
    @DisplayName("accepts stdio transport")
    void acceptsStdio() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "stdio" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("stdio", config.effectiveTransport());
    }

    @Test
    @DisplayName("accepts http transport")
    void acceptsHttp() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "http" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("http", config.effectiveTransport());
    }

    @Test
    @DisplayName("accepts null transport (defaults to stdio)")
    void acceptsNullTransport() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs" } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("stdio", config.effectiveTransport());
      assertEquals(2363, config.effectivePort());
    }

    @Test
    @DisplayName("rejects invalid transport")
    void rejectsInvalidTransport() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "websocket" } } }
          """);

      var ex = assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
      assertTrue(ex.getMessage().contains("Invalid transport"));
      assertTrue(ex.getMessage().contains("websocket"));
    }

    @Test
    @DisplayName("rejects empty string transport")
    void rejectsEmptyTransport() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "" } } }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("accepts port 1 (lower boundary)")
    void acceptsPort1() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "http", "port": 1 } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(1, config.effectivePort());
    }

    @Test
    @DisplayName("accepts port 65535 (upper boundary)")
    void acceptsPort65535() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "http", "port": 65535 } } }
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals(65535, config.effectivePort());
    }

    @Test
    @DisplayName("rejects port 0")
    void rejectsPort0() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "http", "port": 0 } } }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("rejects negative port")
    void rejectsNegativePort() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "http", "port": -1 } } }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("rejects port above 65535")
    void rejectsPortAbove65535() throws Exception {
      var file = writeJson("""
          { "servers": { "dev": { "logdir": "/logs", "transport": "http", "port": 99999 } } }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }

    @Test
    @DisplayName("validation happens after merge (invalid transport from defaults)")
    void validatesAfterMerge() throws Exception {
      var file = writeJson("""
          {
            "transport": "grpc",
            "servers": { "dev": { "logdir": "/logs" } }
          }
          """);

      assertThrows(ConfigException.class,
          () -> new ConfigLoader().load("dev", file));
    }
  }

  // ==================== Config File Discovery ====================

  @Nested
  @DisplayName("Config file discovery")
  class DiscoveryTests {

    @Test
    @DisplayName("explicit path is used directly")
    void explicitPathUsed() throws Exception {
      var file = writeFile("custom.yaml", """
          servers:
            dev:
              logdir: /logs
          """);

      var config = new ConfigLoader().load("dev", file);
      assertEquals("/logs", config.logdir());
    }

    @Test
    @DisplayName("throws for non-existent explicit path")
    void throwsForMissingExplicitPath() {
      var loader = new ConfigLoader();
      var ex = assertThrows(ConfigException.class,
          () -> loader.discoverConfigFile(tempDir.resolve("missing.yaml")));
      assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    @DisplayName("throws when no config file found in search paths")
    void throwsWhenNoneFound() {
      var loader = new ConfigLoader();
      // Without writing any files, auto-discovery should fail
      // (unless user happens to have one, so we can't fully test this without mocking)
      // But we can at least verify the search paths are correct
      var paths = loader.configSearchPaths();
      assertEquals(4, paths.size());
    }

    @Test
    @DisplayName("search paths are in correct order")
    void searchPathOrder() {
      var paths = new ConfigLoader().configSearchPaths();
      var home = System.getProperty("user.home");

      assertEquals(Path.of(".wpilog-mcp.yaml"), paths.get(0));
      assertEquals(Path.of(".wpilog-mcp.json"), paths.get(1));
      assertEquals(Path.of(home, ".wpilog-mcp", "servers.yaml"), paths.get(2));
      assertEquals(Path.of(home, ".wpilog-mcp", "servers.json"), paths.get(3));
    }

    @Test
    @DisplayName("YAML is preferred over JSON at project-local level")
    void yamlPreferredOverJsonLocal() throws Exception {
      // Write both files in a controlled temp dir and test discoverConfigFile
      var yamlFile = tempDir.resolve(".wpilog-mcp.yaml");
      var jsonFile = tempDir.resolve(".wpilog-mcp.json");
      Files.writeString(yamlFile, "servers:\n  dev:\n    logdir: /yaml\n");
      Files.writeString(jsonFile, "{\"servers\":{\"dev\":{\"logdir\":\"/json\"}}}");

      // We can't easily test auto-discovery without controlling CWD,
      // but we can test explicit path with both
      var yamlConfig = new ConfigLoader().load("dev", yamlFile);
      var jsonConfig = new ConfigLoader().load("dev", jsonFile);

      assertEquals("/yaml", yamlConfig.logdir());
      assertEquals("/json", jsonConfig.logdir());
    }
  }

  // ==================== listConfigs ====================

  @Nested
  @DisplayName("listConfigs")
  class ListConfigsTests {

    @Test
    @DisplayName("lists all server names")
    void listsAll() throws Exception {
      var file = writeJson("""
          {
            "servers": {
              "comp": { "logdir": "/a" },
              "dev": { "logdir": "/b" },
              "test": { "logdir": "/c" }
            }
          }
          """);

      var names = new ConfigLoader().listConfigs(file);
      assertEquals(3, names.size());
      assertTrue(names.containsAll(List.of("comp", "dev", "test")));
    }

    @Test
    @DisplayName("returns empty list when no servers section")
    void emptyWhenNoServers() throws Exception {
      var file = writeJson("{}");

      var names = new ConfigLoader().listConfigs(file);
      assertTrue(names.isEmpty());
    }

    @Test
    @DisplayName("returns empty list when servers is not an object")
    void emptyWhenServersNotObject() throws Exception {
      var file = writeJson("{\"servers\": \"not_an_object\"}");

      var names = new ConfigLoader().listConfigs(file);
      assertTrue(names.isEmpty());
    }

    @Test
    @DisplayName("works with YAML")
    void worksWithYaml() throws Exception {
      var file = writeYaml("""
          servers:
            alpha:
              logdir: /a
            beta:
              logdir: /b
          """);

      var names = new ConfigLoader().listConfigs(file);
      assertEquals(2, names.size());
      assertTrue(names.containsAll(List.of("alpha", "beta")));
    }

    @Test
    @DisplayName("returns single-element list for single server")
    void singleServer() throws Exception {
      var file = writeJson("""
          { "servers": { "only": { "logdir": "/logs" } } }
          """);

      var names = new ConfigLoader().listConfigs(file);
      assertEquals(List.of("only"), names);
    }
  }

  // ==================== mapToJsonObject ====================

  @Nested
  @DisplayName("mapToJsonObject")
  class MapToJsonObjectTests {

    @Test
    @DisplayName("converts string values")
    void convertsStrings() {
      var result = ConfigLoader.mapToJsonObject(Map.of("key", "value"));
      assertEquals("value", result.get("key").getAsString());
    }

    @Test
    @DisplayName("converts boolean values")
    void convertsBooleans() {
      var result = ConfigLoader.mapToJsonObject(Map.of("flag", true));
      assertTrue(result.get("flag").getAsBoolean());
    }

    @Test
    @DisplayName("converts integer values")
    void convertsIntegers() {
      var result = ConfigLoader.mapToJsonObject(Map.of("num", 42));
      assertEquals(42, result.get("num").getAsInt());
    }

    @Test
    @DisplayName("converts long values")
    void convertsLongs() {
      var result = ConfigLoader.mapToJsonObject(Map.of("big", Long.MAX_VALUE));
      assertEquals(Long.MAX_VALUE, result.get("big").getAsLong());
    }

    @Test
    @DisplayName("converts double values")
    void convertsDoubles() {
      var result = ConfigLoader.mapToJsonObject(Map.of("pi", 3.14));
      assertEquals(3.14, result.get("pi").getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("converts null values to JsonNull")
    void convertsNulls() {
      var map = new LinkedHashMap<String, Object>();
      map.put("absent", null);
      var result = ConfigLoader.mapToJsonObject(map);
      assertTrue(result.get("absent").isJsonNull());
    }

    @Test
    @DisplayName("converts nested maps recursively")
    void convertsNestedMaps() {
      var inner = Map.of("nested_key", "nested_value");
      var outer = Map.<String, Object>of("outer", inner);
      var result = ConfigLoader.mapToJsonObject(outer);

      assertTrue(result.get("outer").isJsonObject());
      assertEquals("nested_value",
          result.getAsJsonObject("outer").get("nested_key").getAsString());
    }

    @Test
    @DisplayName("converts empty map to empty JsonObject")
    void convertsEmptyMap() {
      var result = ConfigLoader.mapToJsonObject(Map.of());
      assertEquals(0, result.size());
    }

    @Test
    @DisplayName("coerces unknown types to string")
    void coercesUnknownTypes() {
      // Use a type that is not explicitly handled (not Map, List, String, Boolean, or Number)
      var unknown = new Object() {
        @Override public String toString() { return "custom-object"; }
      };
      var result = ConfigLoader.mapToJsonObject(Map.of("obj", unknown));
      assertEquals("custom-object", result.get("obj").getAsString());
    }

    @Test
    @DisplayName("handles deeply nested maps")
    void deeplyNested() {
      Map<String, Object> level3 = Map.of("val", "deep");
      Map<String, Object> level2 = Map.of("l3", level3);
      Map<String, Object> level1 = Map.of("l2", level2);
      Map<String, Object> root = Map.of("l1", level1);

      var result = ConfigLoader.mapToJsonObject(root);
      assertEquals("deep",
          result.getAsJsonObject("l1")
              .getAsJsonObject("l2")
              .getAsJsonObject("l3")
              .get("val").getAsString());
    }

    @Test
    @DisplayName("converts Number subtype (Float)")
    void convertsFloat() {
      var map = Map.<String, Object>of("f", Float.valueOf(1.5f));
      var result = ConfigLoader.mapToJsonObject(map);
      assertTrue(result.get("f").isJsonPrimitive());
    }

    @Test
    @DisplayName("converts List values to JsonArray")
    void testMapToJsonObjectWithListValues() {
      var map = Map.<String, Object>of("items", List.of("a", "b", "c"));
      var result = ConfigLoader.mapToJsonObject(map);

      assertTrue(result.get("items").isJsonArray());
      var arr = result.getAsJsonArray("items");
      assertEquals(3, arr.size());
      assertEquals("a", arr.get(0).getAsString());
      assertEquals("b", arr.get(1).getAsString());
      assertEquals("c", arr.get(2).getAsString());
    }
  }

  // ==================== listToJsonArray ====================

  @Nested
  @DisplayName("listToJsonArray")
  class ListToJsonArrayTests {

    @Test
    @DisplayName("converts simple string list")
    void testListToJsonArraySimpleStrings() {
      var result = ConfigLoader.listToJsonArray(List.of("a", "b"));
      assertEquals(2, result.size());
      assertEquals("a", result.get(0).getAsString());
      assertEquals("b", result.get(1).getAsString());
    }

    @Test
    @DisplayName("converts empty list to empty JsonArray")
    void testListToJsonArrayEmptyList() {
      var result = ConfigLoader.listToJsonArray(List.of());
      assertEquals(0, result.size());
    }

    @Test
    @DisplayName("converts null elements to JsonNull")
    void testListToJsonArrayNullElements() {
      var list = new ArrayList<>();
      list.add("before");
      list.add(null);
      list.add("after");
      var result = ConfigLoader.listToJsonArray(list);

      assertEquals(3, result.size());
      assertEquals("before", result.get(0).getAsString());
      assertTrue(result.get(1).isJsonNull());
      assertEquals("after", result.get(2).getAsString());
    }

    @Test
    @DisplayName("converts mixed types correctly")
    void testListToJsonArrayMixedTypes() {
      var list = new ArrayList<>();
      list.add("hello");
      list.add(42);
      list.add(true);
      list.add(3.14);
      var result = ConfigLoader.listToJsonArray(list);

      assertEquals(4, result.size());
      assertEquals("hello", result.get(0).getAsString());
      assertEquals(42, result.get(1).getAsInt());
      assertTrue(result.get(2).getAsBoolean());
      assertEquals(3.14, result.get(3).getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("converts nested map inside list to JsonObject")
    void testListToJsonArrayNestedMap() {
      var innerMap = Map.<String, Object>of("key", "value", "num", 7);
      var result = ConfigLoader.listToJsonArray(List.of(innerMap));

      assertEquals(1, result.size());
      assertTrue(result.get(0).isJsonObject());
      var obj = result.get(0).getAsJsonObject();
      assertEquals("value", obj.get("key").getAsString());
      assertEquals(7, obj.get("num").getAsInt());
    }

    @Test
    @DisplayName("converts nested list to nested JsonArray")
    void testListToJsonArrayNestedList() {
      var inner = List.of("x", "y");
      var outer = List.<Object>of(inner);
      var result = ConfigLoader.listToJsonArray(outer);

      assertEquals(1, result.size());
      assertTrue(result.get(0).isJsonArray());
      var nested = result.get(0).getAsJsonArray();
      assertEquals(2, nested.size());
      assertEquals("x", nested.get(0).getAsString());
      assertEquals("y", nested.get(1).getAsString());
    }
  }

  // ==================== ServerConfig Record ====================

  @Nested
  @DisplayName("ServerConfig")
  class ServerConfigTests {

    @Test
    @DisplayName("isHttp returns true for 'http'")
    void isHttpTrue() {
      var config = new ServerConfig("s", null, null, null, "http",
          null, null, null, null, null, null, null);
      assertTrue(config.isHttp());
    }

    @Test
    @DisplayName("isHttp is case-insensitive")
    void isHttpCaseInsensitive() {
      assertTrue(new ServerConfig("s", null, null, null, "HTTP",
          null, null, null, null, null, null, null).isHttp());
      assertTrue(new ServerConfig("s", null, null, null, "Http",
          null, null, null, null, null, null, null).isHttp());
    }

    @Test
    @DisplayName("isHttp returns false for 'stdio'")
    void isHttpFalseStdio() {
      var config = new ServerConfig("s", null, null, null, "stdio",
          null, null, null, null, null, null, null);
      assertFalse(config.isHttp());
    }

    @Test
    @DisplayName("isHttp returns false for null transport")
    void isHttpFalseNull() {
      var config = new ServerConfig("s", null, null, null, null,
          null, null, null, null, null, null, null);
      assertFalse(config.isHttp());
    }

    @Test
    @DisplayName("effectivePort returns port when set")
    void effectivePortSet() {
      var config = new ServerConfig("s", null, null, null, null,
          8080, null, null, null, null, null, null);
      assertEquals(8080, config.effectivePort());
    }

    @Test
    @DisplayName("effectivePort defaults to 2363 when null")
    void effectivePortDefault() {
      var config = new ServerConfig("s", null, null, null, null,
          null, null, null, null, null, null, null);
      assertEquals(2363, config.effectivePort());
    }

    @Test
    @DisplayName("effectiveTransport returns transport when set")
    void effectiveTransportSet() {
      var config = new ServerConfig("s", null, null, null, "http",
          null, null, null, null, null, null, null);
      assertEquals("http", config.effectiveTransport());
    }

    @Test
    @DisplayName("effectiveTransport defaults to 'stdio' when null")
    void effectiveTransportDefault() {
      var config = new ServerConfig("s", null, null, null, null,
          null, null, null, null, null, null, null);
      assertEquals("stdio", config.effectiveTransport());
    }

    @Test
    @DisplayName("mergeWithDefaults returns this when defaults is null")
    void mergeNullDefaults() {
      var server = new ServerConfig("dev", "/logs", null, null, null,
          null, null, null, null, null, null, null);
      assertSame(server, server.mergeWithDefaults(null));
    }

    @Test
    @DisplayName("mergeWithDefaults preserves server name, not defaults name")
    void mergePreservesServerName() {
      var defaults = new ServerConfig("_defaults", null, null, null, null,
          null, null, null, null, null, null, null);
      var server = new ServerConfig("myserver", null, null, null, null,
          null, null, null, null, null, null, null);

      assertEquals("myserver", server.mergeWithDefaults(defaults).name());
    }

    @Test
    @DisplayName("mergeWithDefaults: every non-null server field wins")
    void mergeServerFieldsWin() {
      var defaults = new ServerConfig("_d", "/d-logs", 1, "d-key", "stdio",
          3000, "/d-cache", 100L, false, false, "/d-export", 1);
      var server = new ServerConfig("s", "/s-logs", 2, "s-key", "http",
          8080, "/s-cache", 200L, true, true, "/s-export", 10);

      var merged = server.mergeWithDefaults(defaults);

      assertEquals("/s-logs", merged.logdir());
      assertEquals(2, merged.team());
      assertEquals("s-key", merged.tbaKey());
      assertEquals("http", merged.transport());
      assertEquals(8080, merged.port());
      assertEquals("/s-cache", merged.diskcachedir());
      assertEquals(200L, merged.diskcachesize());
      assertTrue(merged.diskcachedisable());
      assertTrue(merged.debug());
      assertEquals("/s-export", merged.exportdir());
      assertEquals(10, merged.scandepth());
    }

    @Test
    @DisplayName("mergeWithDefaults: every null server field falls through")
    void mergeNullFieldsFallThrough() {
      var defaults = new ServerConfig("_d", "/d-logs", 1, "d-key", "stdio",
          3000, "/d-cache", 100L, false, true, "/d-export", 5);
      var server = new ServerConfig("s", null, null, null, null,
          null, null, null, null, null, null, null);

      var merged = server.mergeWithDefaults(defaults);

      assertEquals("/d-logs", merged.logdir());
      assertEquals(1, merged.team());
      assertEquals("d-key", merged.tbaKey());
      assertEquals("stdio", merged.transport());
      assertEquals(3000, merged.port());
      assertEquals("/d-cache", merged.diskcachedir());
      assertEquals(100L, merged.diskcachesize());
      assertFalse(merged.diskcachedisable());
      assertTrue(merged.debug());
      assertEquals("/d-export", merged.exportdir());
      assertEquals(5, merged.scandepth());
    }

    @Test
    @DisplayName("mergeWithDefaults: each field independently merges")
    void mergeFieldByField() {
      // Server has only team and port; everything else should come from defaults
      var defaults = new ServerConfig("_d", "/logs", 1, "key", "stdio",
          3000, "/cache", 100L, false, true, "/export", 5);
      var server = new ServerConfig("s", null, 99, null, null,
          8080, null, null, null, null, null, null);

      var merged = server.mergeWithDefaults(defaults);

      assertEquals("/logs", merged.logdir());  // from defaults
      assertEquals(99, merged.team());         // from server
      assertEquals("key", merged.tbaKey());    // from defaults
      assertEquals("stdio", merged.transport()); // from defaults
      assertEquals(8080, merged.port());       // from server
      assertEquals("/cache", merged.diskcachedir()); // from defaults
    }

    @Test
    @DisplayName("record equality works")
    void recordEquality() {
      var a = new ServerConfig("dev", "/logs", 2363, null, "stdio",
          null, null, null, null, null, null, null);
      var b = new ServerConfig("dev", "/logs", 2363, null, "stdio",
          null, null, null, null, null, null, null);

      assertEquals(a, b);
      assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("record inequality when fields differ")
    void recordInequality() {
      var a = new ServerConfig("dev", "/logs", 2363, null, null,
          null, null, null, null, null, null, null);
      var b = new ServerConfig("dev", "/logs", 9999, null, null,
          null, null, null, null, null, null, null);

      assertNotEquals(a, b);
    }

    @Test
    @DisplayName("toString includes field values")
    void toStringIncludesFields() {
      var config = new ServerConfig("dev", "/logs", 2363, null, "stdio",
          null, null, null, null, null, null, null);
      var str = config.toString();

      assertTrue(str.contains("dev"));
      assertTrue(str.contains("/logs"));
      assertTrue(str.contains("2363"));
      assertTrue(str.contains("stdio"));
    }
  }

  // ==================== ConfigException ====================

  @Nested
  @DisplayName("ConfigException")
  class ConfigExceptionTests {

    @Test
    @DisplayName("message constructor")
    void messageConstructor() {
      var ex = new ConfigException("test error");
      assertEquals("test error", ex.getMessage());
      assertNull(ex.getCause());
    }

    @Test
    @DisplayName("message + cause constructor")
    void messageCauseConstructor() {
      var cause = new IOException("io fail");
      var ex = new ConfigException("wrapped", cause);
      assertEquals("wrapped", ex.getMessage());
      assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("is a checked exception")
    void isCheckedException() {
      var ex = new ConfigException("test");
      assertInstanceOf(Exception.class, ex);
      assertFalse(RuntimeException.class.isAssignableFrom(ex.getClass()));
    }
  }

  // ==================== Default Config File (install task output) ====================

  @Nested
  @DisplayName("Default config file")
  class DefaultConfigTests {

    /**
     * Verifies that the YAML config generated by the install task is parseable
     * and produces the expected configuration.
     */
    @Test
    @DisplayName("generated servers.yaml is valid and has expected structure")
    void generatedServersYamlIsValid() throws Exception {
      // This matches the YAML generated by the install task in build.gradle
      var file = writeYaml("""
          # wpilog-mcp server configuration
          # Documentation: https://github.com/TripleHelixProgramming/wpilog-mcp#configuration

          # Your FRC team number
          team: 0

          # Directory containing .wpilog files
          logdir: ~/riologs

          servers:
            default:
              transport: stdio

            http:
              transport: http
              port: 2363

            stresstest:
              tba_key: ${TBA_API_KEY}
          """);

      var loader = new ConfigLoader(n -> "TBA_API_KEY".equals(n) ? "test_key" : null);

      // Verify all configs are loadable
      var names = loader.listConfigs(file);
      assertEquals(3, names.size());
      assertTrue(names.containsAll(List.of("default", "http", "stresstest")));

      // Verify default config
      var home = System.getProperty("user.home");
      var defaultConfig = loader.load("default", file);
      assertEquals("stdio", defaultConfig.effectiveTransport());
      assertEquals(0, defaultConfig.team());
      assertEquals(home + "/riologs", defaultConfig.logdir());

      // Verify http config
      var httpConfig = loader.load("http", file);
      assertEquals("http", httpConfig.effectiveTransport());
      assertEquals(2363, httpConfig.effectivePort());
      assertEquals(0, httpConfig.team());

      // Verify stresstest config
      var stressConfig = loader.load("stresstest", file);
      assertEquals("test_key", stressConfig.tbaKey());
      assertEquals(0, stressConfig.team());
    }

    @Test
    @DisplayName("legacy JSON config is still loadable")
    void legacyJsonConfigStillWorks() throws Exception {
      var file = writeJson("""
          {
            "defaults": {
              "team": 2363,
              "tba_key": "old_key"
            },
            "servers": {
              "default": {
                "logdir": "~/wpilib/logs",
                "transport": "stdio"
              }
            }
          }
          """);

      var config = new ConfigLoader().load("default", file);
      assertEquals(2363, config.team());
      assertEquals("old_key", config.tbaKey());
      assertEquals("stdio", config.effectiveTransport());
    }
  }
}

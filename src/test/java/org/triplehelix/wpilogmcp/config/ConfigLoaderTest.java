package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ConfigLoader")
class ConfigLoaderTest {

  @TempDir
  Path tempDir;

  private Path writeConfig(String json) throws IOException {
    var file = tempDir.resolve("servers.json");
    Files.writeString(file, json);
    return file;
  }

  // ==================== Parsing ====================

  @Nested
  @DisplayName("Parsing")
  class ParsingTests {

    @Test
    @DisplayName("loads a minimal valid config")
    void loadsMinimalConfig() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": {
                "logdir": "/logs",
                "transport": "stdio"
              }
            }
          }
          """);

      var loader = new ConfigLoader();
      var config = loader.load("dev", file);

      assertEquals("dev", config.name());
      assertEquals("/logs", config.logdir());
      assertEquals("stdio", config.effectiveTransport());
    }

    @Test
    @DisplayName("loads a config with all fields")
    void loadsFullConfig() throws Exception {
      var file = writeConfig("""
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

      var loader = new ConfigLoader();
      var config = loader.load("comp", file);

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
    @DisplayName("throws for unknown config name")
    void throwsForUnknownName() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": { "logdir": "/logs" }
            }
          }
          """);

      var loader = new ConfigLoader();
      var ex = assertThrows(ConfigException.class, () -> loader.load("prod", file));
      assertTrue(ex.getMessage().contains("Unknown server configuration 'prod'"));
      assertTrue(ex.getMessage().contains("dev"));
    }

    @Test
    @DisplayName("throws for missing servers section")
    void throwsForMissingServers() throws Exception {
      var file = writeConfig("""
          { "defaults": { "team": 1 } }
          """);

      var loader = new ConfigLoader();
      assertThrows(ConfigException.class, () -> loader.load("dev", file));
    }

    @Test
    @DisplayName("throws for malformed JSON")
    void throwsForMalformedJson() throws Exception {
      var file = writeConfig("{ not valid json }}}");

      var loader = new ConfigLoader();
      assertThrows(ConfigException.class, () -> loader.load("dev", file));
    }

    @Test
    @DisplayName("throws for non-existent explicit config file")
    void throwsForMissingFile() {
      var loader = new ConfigLoader();
      assertThrows(ConfigException.class,
          () -> loader.load("dev", tempDir.resolve("nonexistent.json")));
    }
  }

  // ==================== Defaults Merging ====================

  @Nested
  @DisplayName("Defaults merging")
  class DefaultsMergingTests {

    @Test
    @DisplayName("server inherits defaults for unset fields")
    void inheritsDefaults() throws Exception {
      var file = writeConfig("""
          {
            "defaults": {
              "team": 2363,
              "tba_key": "default_key"
            },
            "servers": {
              "dev": {
                "logdir": "/logs",
                "transport": "stdio"
              }
            }
          }
          """);

      var loader = new ConfigLoader();
      var config = loader.load("dev", file);

      assertEquals("/logs", config.logdir());
      assertEquals(2363, config.team());
      assertEquals("default_key", config.tbaKey());
      assertEquals("stdio", config.effectiveTransport());
    }

    @Test
    @DisplayName("server overrides defaults for set fields")
    void overridesDefaults() throws Exception {
      var file = writeConfig("""
          {
            "defaults": {
              "team": 2363
            },
            "servers": {
              "comp": {
                "logdir": "/logs",
                "team": 9999
              }
            }
          }
          """);

      var loader = new ConfigLoader();
      var config = loader.load("comp", file);

      assertEquals(9999, config.team());
    }

    @Test
    @DisplayName("merge preserves scandepth and exportdir from defaults")
    void mergePreservesScandepthAndExportdir() throws Exception {
      var file = writeConfig("""
          {
            "defaults": {
              "scandepth": 8,
              "exportdir": "/default/exports"
            },
            "servers": {
              "dev": {
                "logdir": "/logs",
                "transport": "stdio"
              }
            }
          }
          """);

      var loader = new ConfigLoader();
      var config = loader.load("dev", file);

      assertEquals(8, config.scandepth(), "scandepth should be inherited from defaults");
      assertEquals("/default/exports", config.exportdir(), "exportdir should be inherited from defaults");
    }

    @Test
    @DisplayName("works without defaults section")
    void worksWithoutDefaults() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": { "logdir": "/logs" }
            }
          }
          """);

      var loader = new ConfigLoader();
      var config = loader.load("dev", file);

      assertEquals("/logs", config.logdir());
      assertNull(config.team());
      assertNull(config.tbaKey());
    }

    @Test
    @DisplayName("multiple servers share defaults independently")
    void multipleServersShareDefaults() throws Exception {
      var file = writeConfig("""
          {
            "defaults": {
              "team": 2363,
              "tba_key": "shared_key"
            },
            "servers": {
              "comp": {
                "logdir": "/comp",
                "transport": "http",
                "port": 2363
              },
              "dev": {
                "logdir": "/dev",
                "transport": "stdio"
              }
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
  }

  // ==================== Environment Variable Interpolation ====================

  @Nested
  @DisplayName("Environment variable interpolation")
  class InterpolationTests {

    @Test
    @DisplayName("interpolates ${VAR} with env value")
    void interpolatesEnvVar() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": {
                "logdir": "/logs",
                "tba_key": "${MY_TBA_KEY}"
              }
            }
          }
          """);

      var loader = new ConfigLoader(name -> "MY_TBA_KEY".equals(name) ? "secret123" : null);
      var config = loader.load("dev", file);

      assertEquals("secret123", config.tbaKey());
    }

    @Test
    @DisplayName("preserves literal ${VAR} when env var is unset")
    void preservesLiteralWhenUnset() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": {
                "logdir": "/logs",
                "tba_key": "${UNSET_VAR}"
              }
            }
          }
          """);

      var loader = new ConfigLoader(name -> null);
      var config = loader.load("dev", file);

      assertEquals("${UNSET_VAR}", config.tbaKey());
    }

    @Test
    @DisplayName("interpolates multiple vars in one string")
    void interpolatesMultipleVars() {
      var loader = new ConfigLoader(name -> switch (name) {
        case "HOME" -> "/home/user";
        case "TEAM" -> "2363";
        default -> null;
      });

      var result = loader.interpolate("${HOME}/logs/${TEAM}");
      assertEquals("/home/user/logs/2363", result);
    }

    @Test
    @DisplayName("handles null and non-template strings")
    void handlesNullAndPlain() {
      var loader = new ConfigLoader();
      assertNull(loader.interpolate(null));
      assertEquals("plain string", loader.interpolate("plain string"));
    }

    @Test
    @DisplayName("interpolates env vars in defaults section too")
    void interpolatesInDefaults() throws Exception {
      var file = writeConfig("""
          {
            "defaults": {
              "tba_key": "${MY_KEY}"
            },
            "servers": {
              "dev": { "logdir": "/logs" }
            }
          }
          """);

      var loader = new ConfigLoader(name -> "MY_KEY".equals(name) ? "resolved" : null);
      var config = loader.load("dev", file);

      assertEquals("resolved", config.tbaKey());
    }
  }

  // ==================== Path Expansion ====================

  @Nested
  @DisplayName("Path expansion")
  class PathExpansionTests {

    @Test
    @DisplayName("expands tilde in logdir")
    void expandsTildeInLogdir() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": { "logdir": "~/frc-logs" }
            }
          }
          """);

      var loader = new ConfigLoader();
      var config = loader.load("dev", file);

      var home = System.getProperty("user.home");
      assertEquals(home + "/frc-logs", config.logdir());
    }

    @Test
    @DisplayName("does not expand tilde in the middle of a path")
    void noMidPathTilde() {
      assertEquals("/foo/~/bar", ConfigLoader.expandPath("/foo/~/bar"));
    }

    @Test
    @DisplayName("handles null path")
    void handlesNull() {
      assertNull(ConfigLoader.expandPath(null));
    }
  }

  // ==================== Validation ====================

  @Nested
  @DisplayName("Validation")
  class ValidationTests {

    @Test
    @DisplayName("rejects invalid transport")
    void rejectsInvalidTransport() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": { "logdir": "/logs", "transport": "websocket" }
            }
          }
          """);

      var loader = new ConfigLoader();
      var ex = assertThrows(ConfigException.class, () -> loader.load("dev", file));
      assertTrue(ex.getMessage().contains("Invalid transport"));
    }

    @Test
    @DisplayName("rejects invalid port")
    void rejectsInvalidPort() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": { "logdir": "/logs", "transport": "http", "port": 99999 }
            }
          }
          """);

      var loader = new ConfigLoader();
      assertThrows(ConfigException.class, () -> loader.load("dev", file));
    }

    @Test
    @DisplayName("rejects unknown server name from empty servers section")
    void rejectsUnknownServerFromEmptyServers() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
            }
          }
          """);

      var loader = new ConfigLoader();
      assertThrows(ConfigException.class, () -> loader.load("dev", file));
    }

    @Test
    @DisplayName("accepts valid stdio config")
    void acceptsValidStdio() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "dev": { "logdir": "/logs" }
            }
          }
          """);

      var loader = new ConfigLoader();
      var config = loader.load("dev", file);
      assertEquals("stdio", config.effectiveTransport());
      assertEquals(2363, config.effectivePort());
    }
  }

  // ==================== List Configs ====================

  @Nested
  @DisplayName("listConfigs")
  class ListConfigsTests {

    @Test
    @DisplayName("lists all server names")
    void listsAll() throws Exception {
      var file = writeConfig("""
          {
            "servers": {
              "comp": { "logdir": "/a" },
              "dev": { "logdir": "/b" },
              "test": { "logdir": "/c" }
            }
          }
          """);

      var loader = new ConfigLoader();
      var names = loader.listConfigs(file);
      assertEquals(3, names.size());
      assertTrue(names.contains("comp"));
      assertTrue(names.contains("dev"));
      assertTrue(names.contains("test"));
    }

    @Test
    @DisplayName("returns empty list when no servers section")
    void emptyWhenNoServers() throws Exception {
      var file = writeConfig("{}");

      var loader = new ConfigLoader();
      var names = loader.listConfigs(file);
      assertTrue(names.isEmpty());
    }
  }

  // ==================== ServerConfig Record ====================

  @Nested
  @DisplayName("ServerConfig")
  class ServerConfigTests {

    @Test
    @DisplayName("mergeWithDefaults preserves non-null server values")
    void mergePreservesServerValues() {
      var defaults = new ServerConfig("_defaults", "/default", 1, "key", "stdio",
          3000, null, null, false, false, null, null);
      var server = new ServerConfig("comp", "/server", null, null, "http",
          8080, null, null, null, null, null, null);

      var merged = server.mergeWithDefaults(defaults);

      assertEquals("/server", merged.logdir());
      assertEquals(1, merged.team());  // from defaults
      assertEquals("key", merged.tbaKey());  // from defaults
      assertEquals("http", merged.transport());  // from server
      assertEquals(8080, merged.port());  // from server
    }

    @Test
    @DisplayName("mergeWithDefaults handles null defaults")
    void mergeHandlesNullDefaults() {
      var server = new ServerConfig("dev", "/logs", null, null, null,
          null, null, null, null, null, null, null);

      var merged = server.mergeWithDefaults(null);
      assertSame(server, merged);
    }

    @Test
    @DisplayName("effectiveTransport defaults to stdio")
    void defaultTransport() {
      var config = new ServerConfig("dev", null, null, null, null,
          null, null, null, null, null, null, null);
      assertEquals("stdio", config.effectiveTransport());
      assertFalse(config.isHttp());
    }

    @Test
    @DisplayName("effectivePort defaults to 2363")
    void defaultPort() {
      var config = new ServerConfig("dev", null, null, null, null,
          null, null, null, null, null, null, null);
      assertEquals(2363, config.effectivePort());
    }
  }
}

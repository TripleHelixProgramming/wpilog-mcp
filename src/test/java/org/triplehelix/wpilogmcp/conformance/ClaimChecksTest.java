/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry.Tool;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Claims the code makes about itself, checked against the code (review 6, section 6.2): a
 * tool's schema declares exactly the parameters its code reads; the catalog, the README table,
 * and doc/TOOLS.md name the registered tools and their parameters; the verified-elsewhere map
 * names tests that exist.
 */
@DisplayName("Claim checks: schemas, catalog, documentation")
class ClaimChecksTest {
  static final Path TOOLS_SRC = Path.of("src/main/java/org/triplehelix/wpilogmcp/tools");
  static final Path TEST_SRC = Path.of("src/test/java");
  static List<Tool> tools;
  static ToolRegistry registry;

  @BeforeAll
  static void registerTools() {
    var captured = new ArrayList<Tool>();
    registry = new ToolRegistry() {
      @Override
      public void registerTool(Tool tool) {
        captured.add(tool);
        super.registerTool(tool);
      }
    };
    WpilogTools.registerAll(registry);
    captured.sort(Comparator.comparing(Tool::name));
    tools = List.copyOf(captured);
  }

  // ==================== schema versus code ====================

  /** A key read from the arguments as a whole literal: helper-style or member-style. */
  static final Pattern READ = Pattern.compile(
      "(?:get(?:Opt|Required)\\w*|has)\\(\\s*(?:arguments|args)\\s*,\\s*\"([a-z][a-z0-9_]*)\"\\s*[,)]"
      + "|\\b(?:arguments|args)\\s*\\.\\s*(?:get|has|remove)\\(\\s*\"([a-z][a-z0-9_]*)\"\\s*\\)");
  /** A key built from a literal prefix ("name" + i) or suffix (role + "_entry"). */
  static final Pattern PREFIX = Pattern.compile(
      "(?:get\\w*|has)\\(\\s*(?:arguments|args)\\s*,\\s*\"([a-z][a-z0-9_]*)\"\\s*\\+");
  static final Pattern SUFFIX = Pattern.compile(
      "(?:get\\w*|has)\\(\\s*(?:arguments|args)\\s*,\\s*[\\w.()]+\\s*\\+\\s*\"([a-z0-9_]+)\"");
  /** A method call: an optional class, the method, and the open parenthesis. */
  static final Pattern CALL = Pattern.compile("(?:\\b([A-Z]\\w*)\\.)?\\b([a-z]\\w*)\\(");
  static final Set<String> ACCESSORS = Set.of("has", "get", "remove", "equals", "contains");
  /**
   * Parameters read by a key held in data rather than written as a literal, which no static
   * scan can see: tool to the suffix its keys share (profile_mechanism reads
   * {@code getOptString(arguments, role.param)} for each mechanism role).
   */
  static final Map<String, String> COMPUTED_SUFFIX = Map.of("profile_mechanism", "_entry");

  /** The keys a piece of code reads: whole literals, plus prefix/suffix patterns as "name*" / "*_entry". */
  record Reads(Set<String> keys, Set<String> prefixes, Set<String> suffixes) {
    Reads() {
      this(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
    }

    void addAll(Reads other) {
      keys.addAll(other.keys);
      prefixes.addAll(other.prefixes);
      suffixes.addAll(other.suffixes);
    }

    boolean covers(String key) {
      return keys.contains(key)
          || prefixes.stream().anyMatch(p -> key.startsWith(p) && key.length() > p.length())
          || suffixes.stream().anyMatch(s -> key.endsWith(s) && key.length() > s.length());
    }
  }

  static Reads reads(String source) {
    var r = new Reads();
    var m = READ.matcher(source);
    while (m.find()) r.keys.add(m.group(1) != null ? m.group(1) : m.group(2));
    var p = PREFIX.matcher(source);
    while (p.find()) r.prefixes.add(p.group(1));
    var s = SUFFIX.matcher(source);
    while (s.find()) r.suffixes.add(s.group(1));
    return r;
  }

  /** Comments carry examples ({@code getRequiredString(arguments, "path")}) that are not reads. */
  static String stripComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(^|\\s)//[^\\n]*", "$1");
  }

  /** Tool sources by class name, with LF line endings (a Windows checkout has CRLF). */
  static Map<String, String> sources() throws IOException {
    var map = new HashMap<String, String>();
    try (Stream<Path> files = Files.list(TOOLS_SRC)) {
      for (var f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        map.put(f.getFileName().toString().replace(".java", ""),
            stripComments(Files.readString(f).replace("\r\n", "\n")));
      }
    }
    return map;
  }

  /** The tool class's source: a nested class's body, or the whole file of a top-level one. */
  static String region(Map<String, String> sources, Class<?> cls) {
    var enclosing = cls.getEnclosingClass();
    var file = sources.get((enclosing != null ? enclosing : cls).getSimpleName());
    assertNotNull(file, "no source for " + cls);
    if (enclosing == null) return file;
    var header = Pattern.compile("\\n  (?:public |private |protected )?(?:static |final |abstract )*"
        + "class " + cls.getSimpleName() + "\\b").matcher(file);
    assertTrue(header.find(), "class " + cls.getSimpleName() + " not found in its file");
    // The nested class ends at its closing brace, at the file's two-space indentation; the
    // outer class's own helper methods that follow are not the tool's
    int end = file.indexOf("\n  }\n", header.end());
    return file.substring(header.start(), end < 0 ? file.length() : end);
  }

  /** The bodies of every method named {@code method} in {@code source}. */
  static List<String> bodies(String source, String method) {
    var out = new ArrayList<String>();
    var m = Pattern.compile("\\b" + method + "\\s*\\([^)]*\\)\\s*(?:throws [\\w., ]+)?\\{")
        .matcher(source);
    while (m.find()) {
      int depth = 1;
      int i = m.end();
      while (i < source.length() && depth > 0) {
        char c = source.charAt(i++);
        if (c == '{') depth++;
        else if (c == '}') depth--;
      }
      out.add(source.substring(m.end(), i));
    }
    return out;
  }

  /**
   * The keys a piece of code reads, including those read by the methods it hands its arguments
   * to (a helper's static method, a method of its own file, a static import, an instance method
   * such as withAngleArgument), followed up to {@code depth} levels. A string literal passed
   * beside the arguments ({@code signal(log, arguments, "name", "field")}) is a key the callee
   * reads on the caller's behalf.
   */
  static Reads readsOf(String code, String container, Map<String, String> sources, int depth,
      Set<String> visited) {
    var all = reads(code);
    if (depth == 0) return all;
    var m = CALL.matcher(code);
    while (m.find()) {
      var owner = m.group(1);
      var method = m.group(2);
      if (ACCESSORS.contains(method) || method.startsWith("getOpt")
          || method.startsWith("getRequired") || method.startsWith("validate")) {
        continue;
      }
      // The call's top-level argument text, nested calls removed
      int level = 1;
      int i = m.end();
      var top = new StringBuilder();
      while (i < code.length() && level > 0) {
        char c = code.charAt(i++);
        if (c == '(') level++;
        else if (c == ')') level--;
        else if (level == 1) top.append(c);
      }
      if (!Pattern.compile("\\b(?:arguments|args)\\b").matcher(top).find()) continue;
      // A declaration, not a call: the name follows a type ("JsonObject arguments")
      if (Pattern.compile("[\\w>\\]]\\s+(?:arguments|args)\\b").matcher(top).find()) continue;
      var literal = Pattern.compile("\"([a-z][a-z0-9_]*)\"").matcher(top);
      while (literal.find()) all.keys().add(literal.group(1));
      var key = (owner == null ? "" : owner + ".") + method;
      if (!visited.add(key)) continue;
      var candidates = new ArrayList<String>();
      if (owner != null && sources.containsKey(owner)) {
        candidates.add(sources.get(owner));
      } else {
        if (container != null) candidates.add(container);
        candidates.addAll(sources.values());
      }
      for (var src : candidates) {
        var found = bodies(src, method);
        if (found.isEmpty()) continue;
        for (var body : found) all.addAll(readsOf(body, src, sources, depth - 1, visited));
        break;
      }
    }
    return all;
  }

  @Test
  @DisplayName("every tool's schema declares exactly the parameters its code reads")
  void schemasMatchCode() throws IOException {
    var sources = sources();
    var problems = new TreeSet<String>();
    for (var tool : tools) {
      var cls = tool.getClass();
      var containerName = (cls.getEnclosingClass() != null ? cls.getEnclosingClass() : cls)
          .getSimpleName();
      var container = sources.get(containerName);
      var read = readsOf(region(sources, cls), container, sources, 4, new TreeSet<>());
      // The base classes read on the tool's behalf (LogRequiringTool reads path)
      for (Class<?> c = cls.getSuperclass(); c != null; c = c.getSuperclass()) {
        var s = sources.get(c.getSimpleName());
        if (s != null) read.addAll(reads(s));
      }
      var props = tool.inputSchema().getAsJsonObject("properties");
      var declared = new TreeSet<String>(props == null ? Set.of() : props.keySet());
      var undeclared = new TreeSet<>(read.keys());
      undeclared.removeAll(declared);
      var unread = new TreeSet<String>();
      var computed = COMPUTED_SUFFIX.get(tool.name());
      for (var key : declared) {
        if (!read.covers(key) && (computed == null || !key.endsWith(computed))) unread.add(key);
      }
      if (!undeclared.isEmpty()) problems.add(tool.name() + " reads undeclared " + undeclared);
      if (!unread.isEmpty()) problems.add(tool.name() + " declares but never reads " + unread);
    }
    assertTrue(problems.isEmpty(), "Schema versus code:\n" + String.join("\n", problems));
  }

  // ==================== catalog, README, TOOLS.md ====================

  @Test
  @DisplayName("the catalog (get_server_guide) and the README table name exactly the registered tools")
  void catalogAndReadmeMatchRegistry() throws Exception {
    var registered = new TreeSet<String>();
    tools.forEach(t -> registered.add(t.name()));
    var guide = registry.getTool("get_server_guide").execute(new JsonObject()).getAsJsonObject();
    var cataloged = new TreeSet<String>();
    for (var category : guide.getAsJsonArray("categories")) {
      for (var t : category.getAsJsonObject().getAsJsonArray("tools")) {
        cataloged.add(t.getAsJsonObject().get("name").getAsString());
      }
    }
    assertEquals(registered, cataloged, "get_server_guide catalog versus the registry");
    var readme = new TreeSet<String>();
    boolean inTable = false;
    for (var line : Files.readAllLines(Path.of("README.md"))) {
      if (line.startsWith("| **") && line.contains("`")) {
        inTable = true;
        var m = Pattern.compile("`([a-z_0-9]+)`").matcher(line);
        while (m.find()) readme.add(m.group(1));
      } else if (inTable && !line.startsWith("|")) {
        break;
      }
    }
    assertEquals(registered, readme, "README tool table versus the registry");
  }

  /** Tool name to the parameters doc/TOOLS.md lists under its "Parameters:" heading. */
  static Map<String, Set<String>> documentedParameters() throws IOException {
    var result = new TreeMap<String, Set<String>>();
    var heading = Pattern.compile("^### `([a-z_0-9]+)`");
    var name = Pattern.compile("`([a-z][a-z0-9_]*)`");
    String current = null;
    boolean inParameters = false;
    for (var line : Files.readAllLines(Path.of("doc/TOOLS.md"))) {
      var h = heading.matcher(line);
      if (h.find()) {
        current = h.group(1);
        inParameters = false;
        result.put(current, new TreeSet<>());
        continue;
      }
      if (current == null) continue;
      if (line.startsWith("**Parameters:**")) {
        inParameters = !line.contains("None");
        continue;
      }
      if (line.startsWith("**") || line.startsWith("## ")) {
        inParameters = false;
        continue;
      }
      if (inParameters && line.startsWith("- `")) {
        // The names come before the first "(" (the required/optional note) or ":"
        var head = line.split("[(:]", 2)[0];
        var m = name.matcher(head);
        while (m.find()) result.get(current).add(m.group(1));
      }
    }
    return result;
  }

  @Test
  @DisplayName("doc/TOOLS.md lists exactly each tool's schema parameters")
  void toolsDocMatchesSchemas() throws IOException {
    var documented = documentedParameters();
    var problems = new TreeSet<String>();
    for (var tool : tools) {
      var doc = documented.get(tool.name());
      if (doc == null) {
        problems.add(tool.name() + ": no section in doc/TOOLS.md");
        continue;
      }
      var props = tool.inputSchema().getAsJsonObject("properties");
      var declared = new TreeSet<String>(props == null ? Set.of() : props.keySet());
      var undocumented = new TreeSet<>(declared);
      undocumented.removeAll(doc);
      var extra = new TreeSet<>(doc);
      extra.removeAll(declared);
      if (!undocumented.isEmpty()) problems.add(tool.name() + ": undocumented " + undocumented);
      if (!extra.isEmpty()) problems.add(tool.name() + ": documented but not in the schema " + extra);
    }
    assertTrue(problems.isEmpty(), "doc/TOOLS.md versus schemas:\n" + String.join("\n", problems));
  }

  @Test
  @DisplayName("every verified-elsewhere entry names a test that exists")
  void verifiedElsewhereNamesRealTests() throws IOException {
    var problems = new TreeSet<String>();
    var ref = Pattern.compile("^([A-Z][A-Za-z0-9]*)(?:\\.([a-zA-Z0-9]+))?");
    for (var e : DescriptionOutputs.VERIFIED_ELSEWHERE.entrySet()) {
      var m = ref.matcher(e.getValue());
      assertTrue(m.find(), e.getValue());
      var cls = m.group(1);
      var method = m.group(2);
      Path file;
      try (Stream<Path> s = Files.walk(TEST_SRC)) {
        file = s.filter(p -> p.getFileName().toString().equals(cls + ".java")).findFirst()
            .orElse(null);
      }
      if (file == null) {
        problems.add(e.getKey() + ": no test class " + cls);
        continue;
      }
      if (method != null && !Files.readString(file).contains("void " + method + "(")) {
        problems.add(e.getKey() + ": no test " + cls + "." + method);
      }
    }
    assertTrue(problems.isEmpty(), problems.toString());
  }
}

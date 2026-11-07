/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

/** JMX counters remain cumulative. Uptime is a JVM timeline; start time is identity, never a clock. */
final class JvmSample {
  record Reading(Map<String, Number> values, long startTimeMs, JsonObject runtime) {}
  private final MBeanServerConnection connection;
  private final List<ObjectName> collectors;
  private final long startTimeMs;
  private final JsonObject runtime;
  private final boolean processCpu;

  JvmSample(MBeanServerConnection connection) throws Exception {
    this.connection = connection;
    var bean = new ObjectName("java.lang:type=Runtime");
    var attrs = attributes(bean, "StartTime", "VmName", "VmVersion", "InputArguments");
    startTimeMs = ((Number) attrs.get("StartTime")).longValue();
    runtime = new JsonObject(); runtime.addProperty("vm_name", (String) attrs.get("VmName"));
    runtime.addProperty("vm_version", (String) attrs.get("VmVersion"));
    var arguments = new JsonArray();
    for (String arg : (String[]) attrs.get("InputArguments")) arguments.add(redact(arg));
    runtime.add("input_arguments", arguments);
    collectors = connection.queryNames(new ObjectName("java.lang:type=GarbageCollector,name=*"), null)
        .stream().sorted(java.util.Comparator.comparing(ObjectName::getCanonicalName)).toList();
    processCpu = java.util.Arrays.stream(connection.getMBeanInfo(new ObjectName("java.lang:type=OperatingSystem")).getAttributes())
        .anyMatch(a -> a.getName().equals("ProcessCpuTime"));
  }
  Reading read(boolean includeRuntime) throws Exception {
    var values = new LinkedHashMap<String, Number>();
    var memory = attributes(new ObjectName("java.lang:type=Memory"), "HeapMemoryUsage", "NonHeapMemoryUsage");
    for (String kind : List.of("Heap", "NonHeap")) {
      var usage = (CompositeData) memory.get(kind + "MemoryUsage");
      String prefix = kind.equals("Heap") ? "heap/" : "non_heap/";
      for (String field : List.of("used", "committed")) values.put(prefix + field + "_bytes", (Long) usage.get(field));
    }
    for (var collector : collectors) {
      var attrs = attributes(collector, "CollectionCount", "CollectionTime");
      String prefix = "gc/" + component(collector.getKeyProperty("name")) + "/";
      long count = ((Number) attrs.get("CollectionCount")).longValue(), time = ((Number) attrs.get("CollectionTime")).longValue();
      if (count >= 0) values.put(prefix + "count", count);
      if (time >= 0) values.put(prefix + "time_sec", time / 1000.0);
    }
    var threads = attributes(new ObjectName("java.lang:type=Threading"), "ThreadCount", "PeakThreadCount", "DaemonThreadCount");
    values.put("threads/live", ((Number) threads.get("ThreadCount")).longValue());
    values.put("threads/peak", ((Number) threads.get("PeakThreadCount")).longValue());
    values.put("threads/daemon", ((Number) threads.get("DaemonThreadCount")).longValue());
    var classes = attributes(new ObjectName("java.lang:type=ClassLoading"), "LoadedClassCount", "TotalLoadedClassCount");
    values.put("classes/loaded", ((Number) classes.get("LoadedClassCount")).longValue());
    values.put("classes/total", ((Number) classes.get("TotalLoadedClassCount")).longValue());
    if (processCpu) {
      long cpu = ((Number) connection.getAttribute(new ObjectName("java.lang:type=OperatingSystem"), "ProcessCpuTime")).longValue();
      if (cpu >= 0) values.put("process/cpu_sec", cpu / 1_000_000_000.0);
    }
    // Read uptime last: its uncertainty relative to receipt is bounded by the complete poll.
    long uptime = ((Number) connection.getAttribute(new ObjectName("java.lang:type=Runtime"), "Uptime")).longValue();
    values.put("uptime_sec", uptime / 1000.0);
    return new Reading(values, startTimeMs, includeRuntime ? runtime.deepCopy() : null);
  }
  private Map<String, Object> attributes(ObjectName name, String... names) throws Exception {
    var result = new LinkedHashMap<String, Object>();
    for (var attr : connection.getAttributes(name, names).asList()) result.put(attr.getName(), attr.getValue());
    for (String required : names) if (!result.containsKey(required)) throw new java.io.IOException("JMX bean " + name + " lacks " + required);
    return result;
  }
  static String component(String name) {
    // Escape '%' first so collector names cannot collide or introduce another entry path.
    return name.replace("%", "%25").replace("/", "%2F");
  }
  static String redact(String argument) {
    int equal = argument.indexOf('=');
    if (equal >= 0 && argument.substring(0, equal).toLowerCase(java.util.Locale.ROOT).matches(".*(?:password|passwd|secret|token|key).*"))
      return argument.substring(0, equal + 1) + "[redacted]";
    return argument;
  }
  static long payloadBytes(Map<String, Number> values, JsonObject runtime, JsonObject note) {
    return values.size() * 8L + (runtime == null ? 0 : runtime.toString().getBytes(StandardCharsets.UTF_8).length)
        + (note == null ? 0 : note.toString().getBytes(StandardCharsets.UTF_8).length);
  }
}

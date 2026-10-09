/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.util.List;

/**
 * Configuration for a named server instance.
 *
 * <p>All fields are nullable except {@code name}. Null fields indicate "use default."
 * The merge logic in {@link ConfigLoader} overlays per-server values onto the
 * {@code defaults} section, then applies built-in defaults for anything still null.
 * A per-server {@code logdirs} replaces the default list; the two are not combined.
 *
 * <p>Memory management is automatic — the server adapts to available JVM heap.
 * Users control capacity via {@code WPILOG_MAX_HEAP} environment variable.
 *
 * @param logdirs The log directories (the {@code logdir} key: one path or a list), in order
 * @since 0.8.0
 */
public record ServerConfig(
    String name,
    List<String> logdirs,
    Integer team,
    String tbaKey,
    String transport,
    Integer port,
    String diskcachedir,
    Long diskcachesize,
    Boolean diskcachedisable,
    Boolean debug,
    String exportdir,
    Integer scandepth,
    Integer idleExitMinutes,
    CaptureConfig capture,
    MirrorConfig mirror,
    MetricsConfig metrics,
    ContextConfig context
) {

  public ServerConfig {
    logdirs = logdirs == null ? null : List.copyOf(logdirs);
  }

  public ServerConfig(String name, List<String> logdirs, Integer team, String tbaKey,
      String transport, Integer port, String diskcachedir, Long diskcachesize,
      Boolean diskcachedisable, Boolean debug, String exportdir, Integer scandepth, Integer idleExitMinutes,
      CaptureConfig capture, MirrorConfig mirror, MetricsConfig metrics) {
    this(name, logdirs, team, tbaKey, transport, port, diskcachedir, diskcachesize,
        diskcachedisable, debug, exportdir, scandepth, idleExitMinutes, capture, mirror, metrics, null);
  }

  /** Context is configured beside capture, and resolved only after named-server defaults merge. */
  public CaptureConfig effectiveCapture() {
    if (capture == null || context == null) return capture;
    var old = capture.providers();
    return new CaptureConfig(capture.addresses(), capture.store(), capture.periodSeconds(), capture.policy(),
        capture.hotWindowUs(), capture.maxFileBytes(), capture.pull(), capture.gatewayPort(),
        new ProviderConfig(old.robotSsh(), old.stats(), old.tails(), context.photonvision(), context.jvm()));
  }

  public ServerConfig(String name, List<String> logdirs, Integer team, String tbaKey,
      String transport, Integer port, String diskcachedir, Long diskcachesize,
      Boolean diskcachedisable, Boolean debug, String exportdir, Integer scandepth, Integer idleExitMinutes,
      CaptureConfig capture, MirrorConfig mirror) {
    this(name, logdirs, team, tbaKey, transport, port, diskcachedir, diskcachesize,
        diskcachedisable, debug, exportdir, scandepth, idleExitMinutes, capture, mirror, null);
  }

  public ServerConfig(String name, List<String> logdirs, Integer team, String tbaKey,
      String transport, Integer port, String diskcachedir, Long diskcachesize,
      Boolean diskcachedisable, Boolean debug, String exportdir, Integer scandepth, Integer idleExitMinutes,
      CaptureConfig capture) {
    this(name, logdirs, team, tbaKey, transport, port, diskcachedir, diskcachesize,
        diskcachedisable, debug, exportdir, scandepth, idleExitMinutes, capture, null);
  }

  public ServerConfig(String name, List<String> logdirs, Integer team, String tbaKey,
      String transport, Integer port, String diskcachedir, Long diskcachesize,
      Boolean diskcachedisable, Boolean debug, String exportdir, Integer scandepth, Integer idleExitMinutes) {
    this(name, logdirs, team, tbaKey, transport, port, diskcachedir, diskcachesize,
        diskcachedisable, debug, exportdir, scandepth, idleExitMinutes, null);
  }

  /** Naming a capture store grants this server access to it, alongside its other directories. */
  public List<String> effectiveLogdirs() {
    var paths = new java.util.LinkedHashSet<String>(logdirs == null ? List.of() : logdirs);
    if (capture != null) paths.add(capture.store().toString());
    if (mirror != null) paths.add(mirror.folder().toString());
    return List.copyOf(paths);
  }

  /** A configuration without an idle exit, as every configuration was before the idle exit existed. */
  public ServerConfig(String name, List<String> logdirs, Integer team, String tbaKey,
      String transport, Integer port, String diskcachedir, Long diskcachesize,
      Boolean diskcachedisable, Boolean debug, String exportdir, Integer scandepth) {
    this(name, logdirs, team, tbaKey, transport, port, diskcachedir, diskcachesize,
        diskcachedisable, debug, exportdir, scandepth, null);
  }

  /**
   * How long an {@code http} server started in the background runs with no MCP session and no
   * request before it exits on its own, or null for never. Meant for the server the VS Code
   * extension manages, which nobody started by hand and nobody would think to stop; a server
   * someone started with {@code start} stays until {@code stop} unless its configuration says
   * otherwise.
   */
  public java.util.Optional<java.time.Duration> idleExit() {
    return idleExitMinutes == null || idleExitMinutes <= 0
        ? java.util.Optional.empty()
        : java.util.Optional.of(java.time.Duration.ofMinutes(idleExitMinutes));
  }

  /** Returns true if this config uses HTTP transport. */
  public boolean isHttp() {
    return "http".equalsIgnoreCase(transport);
  }

  /** Returns the effective port, defaulting to 2363. */
  public int effectivePort() {
    return port != null ? port : 2363;
  }

  /** Returns the effective transport, defaulting to "stdio". */
  public String effectiveTransport() {
    return transport != null ? transport : "stdio";
  }

  /**
   * Merges this config with a defaults config. Per-server values take priority;
   * null fields fall through to the default.
   */
  public ServerConfig mergeWithDefaults(ServerConfig defaults) {
    if (defaults == null) return this;
    return new ServerConfig(
        name,
        logdirs != null ? logdirs : defaults.logdirs(),
        team != null ? team : defaults.team(),
        tbaKey != null ? tbaKey : defaults.tbaKey(),
        transport != null ? transport : defaults.transport(),
        port != null ? port : defaults.port(),
        diskcachedir != null ? diskcachedir : defaults.diskcachedir(),
        diskcachesize != null ? diskcachesize : defaults.diskcachesize(),
        diskcachedisable != null ? diskcachedisable : defaults.diskcachedisable(),
        debug != null ? debug : defaults.debug(),
        exportdir != null ? exportdir : defaults.exportdir(),
        scandepth != null ? scandepth : defaults.scandepth(),
        idleExitMinutes != null ? idleExitMinutes : defaults.idleExitMinutes(),
        capture != null ? capture : defaults.capture(),
        mirror != null ? mirror : defaults.mirror(),
        metrics != null ? metrics : defaults.metrics(),
        context != null ? context : defaults.context()
    );
  }
}

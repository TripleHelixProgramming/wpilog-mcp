package org.triplehelix.wpilogmcp.config;

/**
 * Configuration for a named server instance.
 *
 * <p>All fields are nullable except {@code name}. Null fields indicate "use default."
 * The merge logic in {@link ConfigLoader} overlays per-server values onto the
 * {@code defaults} section, then applies built-in defaults for anything still null.
 *
 * @since 0.8.0
 */
public record ServerConfig(
    String name,
    String logdir,
    Integer team,
    String tbaKey,
    String transport,
    Integer port,
    Integer maxlogs,
    Long maxmemory,
    String diskcachedir,
    Long diskcachesize,
    Boolean diskcachedisable,
    Boolean debug,
    String exportdir,
    Integer scandepth
) {

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
   *
   * @param defaults The defaults config (nullable fields used as fallback)
   * @return A new ServerConfig with merged values
   */
  public ServerConfig mergeWithDefaults(ServerConfig defaults) {
    if (defaults == null) return this;
    return new ServerConfig(
        name,
        logdir != null ? logdir : defaults.logdir(),
        team != null ? team : defaults.team(),
        tbaKey != null ? tbaKey : defaults.tbaKey(),
        transport != null ? transport : defaults.transport(),
        port != null ? port : defaults.port(),
        maxlogs != null ? maxlogs : defaults.maxlogs(),
        maxmemory != null ? maxmemory : defaults.maxmemory(),
        diskcachedir != null ? diskcachedir : defaults.diskcachedir(),
        diskcachesize != null ? diskcachesize : defaults.diskcachesize(),
        diskcachedisable != null ? diskcachedisable : defaults.diskcachedisable(),
        debug != null ? debug : defaults.debug(),
        exportdir != null ? exportdir : defaults.exportdir(),
        scandepth != null ? scandepth : defaults.scandepth()
    );
  }
}

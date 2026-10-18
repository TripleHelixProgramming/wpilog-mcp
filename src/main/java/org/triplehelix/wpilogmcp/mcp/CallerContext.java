/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import java.net.InetAddress;
import java.nio.file.AccessDeniedException;
import java.util.Objects;

/** Request authority is transport evidence, never session identity or a tool argument.
 * Capture this immutable value before queuing work; the worker must not read a thread-local. */
public final class CallerContext {
  public enum Authority {
    UNTRUSTED, STDIO_LOCAL, HTTP_LOCAL;

    public void requireLocal() throws AccessDeniedException {
      if (this == UNTRUSTED) throw new AccessDeniedException("visualizations", null,
          "ACCESS_DENIED: this operation requires a trusted local caller");
    }
  }
  private static final ThreadLocal<Authority> CURRENT = new ThreadLocal<>();
  private CallerContext() {}
  public static Authority current() { return Objects.requireNonNullElse(CURRENT.get(), Authority.UNTRUSTED); }
  /** A same-host reverse proxy connects from loopback too, so a request it forwarded is never
   * local; a forwarding header can only withdraw trust. */
  public static Authority http(InetAddress listener, InetAddress peer, boolean allowedOrigin, boolean forwarded) {
    return listener != null && peer != null && listener.isLoopbackAddress()
        && peer.isLoopbackAddress() && allowedOrigin && !forwarded ? Authority.HTTP_LOCAL : Authority.UNTRUSTED;
  }
  static void set(Authority value) { CURRENT.set(Objects.requireNonNullElse(value, Authority.UNTRUSTED)); }
  static void clear() { CURRENT.remove(); }
}

/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** The public ID of a client window. A transport session ID is a bearer credential: presenting it
 * acts as that window for attachments, inventory handles, view follows and per-session quotas.
 * Capabilities once listed every window's session ID, and a second local client presented one to
 * read the first window's inventory. Replies therefore name a window by a keyed digest of its
 * session: one session has one window ID in capabilities and view attachments, so a caller can tell
 * which window holds what, but without this workspace's secret the ID can neither be presented as a
 * session nor reversed to one. The secret ends with the workspace, as every session, window report
 * and attachment does. Ownership checks keep comparing the session itself. */
public final class WindowIds {
  private final byte[] secret = new byte[32];
  public WindowIds() { new SecureRandom().nextBytes(secret); }
  public String forSession(String session) {
    try {
      var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return "window-" + HexFormat.of().formatHex(mac.doFinal(session.getBytes(StandardCharsets.UTF_8)), 0, 16);
    } catch (GeneralSecurityException impossible) { throw new AssertionError(impossible); }
  }
}

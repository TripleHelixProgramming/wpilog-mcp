/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import java.nio.file.AccessDeniedException;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;

/** API 2 permits remote visualization, without turning its caller into a local administrator.
 * Both adapters classify the operation before parsing or queuing its work. Services still
 * reject missing transport authority, and legacy store mutation retains its local gate. */
public final class VisualizationAccess {
  public enum Operation { READ, WORKING, LIBRARY, ADMINISTRATION }
  private VisualizationAccess() {}
  public static void require(Authority caller, Operation operation) throws AccessDeniedException {
    if (caller == null || caller == Authority.UNTRUSTED)
      throw new AccessDeniedException("visualizations", null, "ACCESS_DENIED: an admitted transport caller is required");
    if (operation == Operation.ADMINISTRATION) caller.requireLocal();
  }
  public static void transport(Authority caller, int version, Operation operation) throws AccessDeniedException {
    require(caller, operation);
    if (version != 2) caller.requireLocal();
  }
  public static Operation http(String route) {
    if (route.equals("/stores") || route.equals("/library/migrate")) return Operation.ADMINISTRATION;
    if (route.equals("/capabilities") || route.equals("/window") || route.equals("/source") || route.startsWith("/source/")) return Operation.READ;
    if (route.equals("/library/list") || route.startsWith("/templates/")) return Operation.LIBRARY;
    if (route.equals("/views") || route.startsWith("/views/") || route.equals("/saved/open")) return Operation.WORKING;
    return Operation.ADMINISTRATION;
  }
  public static Operation tool(String name) {
    if (name.equals("visualization_library_migrate")) return Operation.ADMINISTRATION;
    if (name.equals("visualization_capabilities_get") || name.startsWith("visualization_source_")) return Operation.READ;
    if (name.equals("visualization_library_list") || name.startsWith("visualization_template_")) return Operation.LIBRARY;
    if (name.startsWith("visualization_view_") || name.equals("visualization_saved_view_open")) return Operation.WORKING;
    return Operation.ADMINISTRATION;
  }
}

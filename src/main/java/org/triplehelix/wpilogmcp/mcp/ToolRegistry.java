/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for MCP tools.
 *
 * <p>Holds the tool definitions and provides lookup. Shared by both stdio and HTTP transports.
 */
public class ToolRegistry {
  private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

  private final Map<String, Tool> tools = new ConcurrentHashMap<>();
  private volatile org.triplehelix.wpilogmcp.visualization.ServerIdentity identity =
      org.triplehelix.wpilogmcp.visualization.ServerIdentity.ephemeral();
  public org.triplehelix.wpilogmcp.visualization.ServerIdentity serverIdentity() { return identity; }
  public void serverIdentity(org.triplehelix.wpilogmcp.visualization.ServerIdentity value) { identity = java.util.Objects.requireNonNull(value); }
  private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<
      org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace>> visualization = new java.util.concurrent.atomic.AtomicReference<>();
  @FunctionalInterface interface WorkspaceBuild {
    org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace build(org.triplehelix.wpilogmcp.visualization.ServerIdentity identity) throws java.io.IOException;
  }
  private volatile java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> capture = () -> null;
  public void captureSource(java.util.function.Supplier<org.triplehelix.wpilogmcp.capture.LiveCapture> value) { capture = java.util.Objects.requireNonNull(value); }
  /** The first caller builds the workspace; tests substitute a build that fails. */
  volatile WorkspaceBuild workspaceBuild = identity ->
      new org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace(identity, org.triplehelix.wpilogmcp.log.LogManager.getInstance(), () -> capture.get());
  /**
   * Set when a stop closes the workspace, cleared when a transport starts serving. A request still
   * running then could otherwise build a new one, which held the workspace's state lock until the
   * process exited, so a replacement server's visualizations stayed unavailable.
   */
  private volatile boolean visualizationsClosed;
  public void openVisualizations() { visualizationsClosed = false; }
  public org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace visualizations() throws java.io.IOException {
    if (visualizationsClosed) throw new java.io.IOException("Workspace closed: the server is stopping");
    var future = visualization.get();
    if (future == null) {
      var pending = new java.util.concurrent.CompletableFuture<org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace>();
      if (visualization.compareAndSet(null, pending)) {
        // A close between the check above and this claim found no workspace to close: give way to it.
        if (visualizationsClosed) {
          visualization.compareAndSet(pending, null);
          var closed = new java.io.IOException("Workspace closed: the server is stopping");
          pending.completeExceptionally(closed);
          throw closed;
        }
        // Every failure answers the callers waiting on this build, an Error too, and frees the slot
        // for the next caller to build again: an unfinished build would hold them all for good.
        try { pending.complete(workspaceBuild.build(identity)); }
        catch (Throwable e) {
          pending.completeExceptionally(e); visualization.compareAndSet(pending, null);
          if (e instanceof VirtualMachineError fatal) throw fatal;
        }
        future = pending;
      } else return visualizations();
    }
    try { return future.get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("Workspace admission interrupted", e); }
    catch (java.util.concurrent.ExecutionException e) {
      var cause = e.getCause();
      throw new java.io.IOException("Workspace unavailable: " + java.util.Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName()), cause);
    }
  }
  public void closeVisualizations() {
    visualizationsClosed = true;
    var pending = visualization.getAndSet(null);
    if (pending != null) pending.thenAccept(org.triplehelix.wpilogmcp.visualization.VisualizationWorkspace::close);
  }
  private volatile boolean managed;
  public void setManaged(boolean value) { managed = value; }
  public boolean isManaged() { return managed; }
  private volatile String serverInstructions;
  private volatile java.util.function.Supplier<JsonObject> currentSession = () -> CurrentSessionResource.read(null);
  public void setCurrentSession(java.util.function.Supplier<JsonObject> value) { currentSession = value; }
  public JsonObject currentSession() { return currentSession.get(); }
  public static final String LOCAL_LOCATION = "This server serves its configured files and mirrors. Other servers and copies are separate user-selected sources.";
  public static final String PIT_LOCATION = "This server serves the team's files and captures. Other servers and copies are separate user-selected sources.";
  private volatile String serverLocation = LOCAL_LOCATION;
  public void setServerLocation(String value) { serverLocation = value; }
  public String getServerLocation() { return serverLocation; }

  /**
   * Sets the server-level instructions returned to clients in the {@code initialize} response
   * (the MCP {@code instructions} field). Clients such as Claude Code and VS Code place this text
   * in the model's system prompt. {@code null} or blank means none.
   */
  public void setServerInstructions(String instructions) {
    this.serverInstructions = instructions;
  }

  /** Returns the server-level instructions, or {@code null} if none were set. */
  public String getServerInstructions() {
    return serverInstructions;
  }

  public void registerTool(Tool tool) {
    logger.debug("Registering tool: {}", tool.name());
    tools.put(tool.name(), tool);
  }

  public Tool getTool(String name) {
    return tools.get(name);
  }

  public Collection<String> getToolNames() {
    return tools.keySet();
  }

  public int size() {
    return tools.size();
  }

  public JsonArray toJsonArray() {
    return tools.values().stream()
        .map(tool -> {
          var toolObj = new JsonObject();
          toolObj.addProperty("name", tool.name());
          toolObj.addProperty("description", tool.description());
          toolObj.add("inputSchema", tool.inputSchema());
          var meta = tool.meta();
          if (meta != null) {
            toolObj.add("_meta", meta);
          }
          return toolObj;
        })
        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
  }

  /**
   * A tool that can be invoked via MCP.
   */
  public interface Tool {
    String name();

    String description();

    JsonObject inputSchema();

    JsonElement execute(JsonObject arguments) throws Exception;

    /**
     * Optional MCP {@code _meta} object attached to this tool's {@code tools/list} entry, for
     * client-specific hints such as {@code "anthropic/alwaysLoad": true}.
     *
     * @return The metadata object, or {@code null} for none (the default)
     */
    default JsonObject meta() {
      return null;
    }
  }

  /**
   * Fluent builder for JSON Schema objects used by tool input schemas.
   */
  public static class SchemaBuilder {
    private final JsonObject schema = new JsonObject();
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public SchemaBuilder() {
      schema.addProperty("type", "object");
      schema.add("properties", properties);
    }

    public SchemaBuilder addProperty(
        String name, String type, String description, boolean isRequired) {
      var prop = new JsonObject();
      prop.addProperty("type", type);
      prop.addProperty("description", description);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public SchemaBuilder addIntegerProperty(
        String name, String description, boolean isRequired, Integer defaultValue) {
      var prop = new JsonObject();
      prop.addProperty("type", "integer");
      prop.addProperty("description", description);
      if (defaultValue != null) prop.addProperty("default", defaultValue);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public SchemaBuilder addNumberProperty(
        String name, String description, boolean isRequired, Double defaultValue) {
      var prop = new JsonObject();
      prop.addProperty("type", "number");
      prop.addProperty("description", description);
      if (defaultValue != null) prop.addProperty("default", defaultValue);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    /**
     * An array property with an item schema.
     *
     * @since 0.9.0
     */
    public SchemaBuilder addArrayProperty(
        String name, String description, JsonObject items, boolean isRequired) {
      var prop = new JsonObject();
      prop.addProperty("type", "array");
      prop.addProperty("description", description);
      prop.add("items", items);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public JsonObject build() {
      if (required.size() > 0) schema.add("required", required);
      return schema;
    }
  }
}

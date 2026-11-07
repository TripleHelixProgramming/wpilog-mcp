/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.nio.file.Path;

/** The job and command expose the importer's records, with paths as strings on every JDK. */
public final class StoreJson {
  private StoreJson() {}

  public static final Gson JSON = new GsonBuilder().serializeNulls()
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
      .registerTypeHierarchyAdapter(Path.class,
          (JsonSerializer<Path>) (path, type, context) -> new JsonPrimitive(path.toString()))
      .create();
}

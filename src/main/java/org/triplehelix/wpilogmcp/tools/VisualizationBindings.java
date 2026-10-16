/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.*;
import java.util.*;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.visualization.VisualizationContracts;

/** Compatibility adapter beside the resolver and numeric-field owner. Exact selectors never
 * fall back to roles, and resolver candidates remain candidates. No browser infers bindings. */
public final class VisualizationBindings {
  private VisualizationBindings() {}
  public static JsonArray roles() { var out = new JsonArray(); for (var r : SignalResolver.Role.values()) out.add(r.name().toLowerCase(Locale.ROOT)); return out; }
  public static JsonObject check(LogData log, JsonObject configuration) {
    return check(log, configuration, false);
  }
  /** Native recording evidence uses the same selector/type/unit rules, with bounded witness
   * scans for dynamic fields instead of constructing every projected sample. */
  public static JsonObject check(LogData log, JsonObject configuration, boolean boundedEvidence) {
    var config = VisualizationContracts.configuration(configuration); var bindings = new JsonArray(); int resolved = 0;
    for (var requirement : config.getAsJsonArray("requirements")) {
      var r = requirement.getAsJsonObject(); var binding = new JsonObject(); binding.add("requirement_id", r.get("id")); binding.add("selector", r.get(r.get("selector_kind").getAsString()));
      binding.addProperty("state", "unresolved"); binding.add("entry", JsonNull.INSTANCE); binding.add("field_path", r.get("field_path"));
      var warnings = new JsonArray(); binding.add("warnings", warnings);
      try {
        String entry; SignalResolver.Role role = null;
        if (r.get("selector_kind").getAsString().equals("entry")) {
          entry = r.get("entry").getAsString(); binding.addProperty("basis", "exact entry selected by caller"); binding.add("candidates", new JsonArray());
        } else {
          role = SignalResolver.Role.fromWire(r.get("role").getAsString());
          var resolution = SignalResolver.resolve(log, role); binding.addProperty("role", r.get("role").getAsString());
          if (boundedEvidence && resolution.candidates().size() > 128)
            throw new org.triplehelix.wpilogmcp.visualization.BindingLimit("Role has more than 128 candidates; select an exact entry");
          binding.addProperty("basis", resolution.basis()); var candidates = new JsonArray(); resolution.candidates().forEach(candidates::add); binding.add("candidates", candidates);
          if (resolution.entries().size() != 1 || resolution.needsConfirmation() || resolution.ambiguous()) throw new IllegalArgumentException("Role has no unique published binding: " + resolution.basis());
          entry = resolution.entries().get(0);
          if (resolution.tier() != SignalResolver.Tier.CONVENTION) role = null;
        }
        var info = log.entries().get(entry); if (info == null) throw new IllegalArgumentException("Exact entry is missing: " + entry);
        binding.addProperty("entry", entry); binding.addProperty("declared_type", info.type());
        if (!r.getAsJsonArray("accepted_entry_types").contains(new JsonPrimitive(info.type()))) throw new IllegalArgumentException("Declared type " + info.type() + " is not in accepted_entry_types");
        if (boundedEvidence) {
          String schema = org.triplehelix.wpilogmcp.log.struct.StructSchemas.structName(info.type());
          if (schema != null && log.structSchemas().info(schema).filter(s -> s.valid()).isEmpty())
            throw new IllegalArgumentException("Selected struct has no valid schema: " + schema);
        }
        String field = r.get("field_path").isJsonNull() ? null : r.get("field_path").getAsString();
        String valueType = r.get("value_type").getAsString();
        if (field == null) {
          boolean valid = switch (valueType) {
            case "number" -> ToolUtils.isNumericType(info.type());
            case "boolean" -> info.type().equals("boolean");
            case "string" -> Set.of("string", "json").contains(info.type());
            case "raw" -> true;
            default -> false;
          };
          if (!valid) throw new IllegalArgumentException("Declared type does not supply " + valueType + " values");
        } else {
          var path = FieldPath.parse(field); if (path.hasWildcard()) throw new IllegalArgumentException("Choose a scalar field or array index for this preview");
          if (!valueType.equals("number")) throw new IllegalArgumentException("Structured preview fields currently require number values");
          if (boundedEvidence) NumericSignal.validateField(log, entry, field);
          else NumericSignal.resolve(log, entry, field);
        }
        var unit = SignalResolver.visualizationUnit(log, entry, role); binding.add("unit", unit);
        var expected = r.get("unit");
        if (!expected.isJsonNull() && !unit.get("symbol").isJsonNull()
            && !expected.getAsJsonObject().get("symbol").equals(unit.get("symbol"))) throw new IllegalArgumentException("Unit differs: expected " + expected.getAsJsonObject().get("symbol") + ", recorded/published " + unit.get("symbol") + "; no conversion");
        if (unit.get("symbol").isJsonNull()) warnings.add("Unit is unknown; any declared unit remains a caller assumption");
        binding.addProperty("state", "resolved"); binding.addProperty("reason", "Compatible declared type and selector"); resolved++;
      } catch (IllegalArgumentException problem) { binding.addProperty("reason", problem.getMessage()); }
      bindings.add(binding);
    }
    var result = new JsonObject(); result.add("bindings", bindings);
    result.addProperty("compatibility", resolved == bindings.size() ? "compatible" : resolved == 0 ? "incompatible" : "partial"); return result;
  }
  /** Statistics panels invoke the existing tool; plot buckets never feed numerical analysis. */
  public static JsonObject statistics(JsonObject arguments) throws Exception { return new StatisticsTools.GetStatisticsTool().execute(arguments).getAsJsonObject(); }
}

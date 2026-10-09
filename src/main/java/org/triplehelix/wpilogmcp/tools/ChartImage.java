/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** Headless drawing only. Every choice about clocks, fields, units and sampling is in the spec. */
final class ChartImage {
  private static final Color[] COLORS = {new Color(0x2166ac), new Color(0xb2182b), new Color(0x238b45), new Color(0x762a83), new Color(0xe08214)};
  private ChartImage() {}
  static byte[] draw(JsonObject spec) throws IOException {
    int width = spec.get("width").getAsInt(), height = spec.get("height").getAsInt();
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(Color.WHITE); g.fillRect(0, 0, width, height); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
      String kind = spec.get("kind").getAsString(); var series = spec.getAsJsonArray("series");
      var bounds = bounds(spec); int left = 58, top = 30, right = Math.max(left + 10, width - 18), bottom = Math.max(top + 10, height - 68);
      double xScale = (right - left) / (bounds[1] - bounds[0]), yScale = (bottom - top) / (bounds[3] - bounds[2]);
      if (kind.equals("field")) { double equal = Math.min(xScale, yScale); xScale = equal; yScale = equal; }
      var transform = new AffineTransform(xScale, 0, 0, -yScale, left - bounds[0] * xScale, bottom + bounds[2] * yScale);
      g.setColor(new Color(0xf2f4f6)); g.fillRect(left, top, right - left, bottom - top);
      if (kind.equals("time_series")) for (var value : spec.getAsJsonArray("phases")) {
        var phase = value.getAsJsonObject(); if (!phase.get("state").getAsString().equals("enabled")) continue;
        g.setColor(phase.has("mode") && phase.get("mode").getAsString().equals("auto") ? new Color(0xffe4ab) : new Color(0xddeedc));
        int x = (int) point(transform, phase.get("start").getAsDouble(), 0).getX();
        int end = (int) point(transform, phase.get("end").getAsDouble(), 0).getX(); g.fillRect(Math.max(left, x), top, Math.min(right, end) - Math.max(left, x), bottom - top);
      }
      if (kind.equals("field")) {
        var geometry = spec.getAsJsonObject("field_geometry");
        if (geometry.has("alliance_zone_depth_m")) {
          double zone = geometry.get("alliance_zone_depth_m").getAsDouble(); g.setColor(new Color(0xd0d7df));
          for (double x : new double[]{zone, bounds[1] - zone}) line(g, transform, x, 0, x, bounds[3]);
        }
      }
      g.setColor(Color.DARK_GRAY); g.drawRect(left, top, right - left, bottom - top);
      g.drawString(kind.replace('_', ' '), left, 18);
      String xLabel = kind.equals("time_series") ? "Robot time (s)" : kind.equals("field") ? "Field x (m), pose schema" : label(series.get(0).getAsJsonObject());
      String yLabel = kind.equals("histogram") ? "Sample count" : kind.equals("field") ? "Field y (m), pose schema" : kind.equals("scatter") ? label(series.get(1).getAsJsonObject()) : "Value (name-stated units in legend)";
      g.drawString(xLabel, left, bottom + 28); g.drawString(yLabel, left, top + 14);
      g.drawString(format(bounds[0]), left, bottom + 13); g.drawString(format(bounds[1]), Math.max(left + 40, right - 45), bottom + 13);
      g.drawString(format(bounds[2]), 2, bottom); g.drawString(format(bounds[3]), 2, top + 8);
      var clip = g.getClip(); g.clipRect(left, top, right - left + 1, bottom - top + 1); g.setStroke(new BasicStroke(1.5f));
      for (int i = 0; i < series.size(); i++) {
        var s = series.get(i).getAsJsonObject(); g.setColor(COLORS[i % COLORS.length]);
        if (kind.equals("histogram")) for (var b : s.getAsJsonArray("bins")) {
          var bin = b.getAsJsonObject(); var p = point(transform, bin.get("low").getAsDouble(), bin.get("count").getAsDouble()); var q = point(transform, bin.get("high").getAsDouble(), 0);
          g.draw(new Rectangle2D.Double(p.getX(), p.getY(), Math.max(1, q.getX() - p.getX()), Math.max(0, q.getY() - p.getY())));
        }
        else if (kind.equals("time_series")) {
          if (s.has("buckets")) for (var b : s.getAsJsonArray("buckets")) {
            var bucket = b.getAsJsonObject(); if (bucket.get("min").isJsonNull()) continue; double x = bucket.get("start").getAsDouble();
            line(g, transform, x, bucket.get("min").getAsDouble(), x, bucket.get("max").getAsDouble());
            dot(g, transform, x, bucket.get("first").getAsDouble()); dot(g, transform, x, bucket.get("last").getAsDouble());
          } else drawPath(g, transform, s.getAsJsonArray("points"), s.get("style").getAsString().equals("step_after"), s.getAsJsonArray("windows"));
        }
      }
      if (kind.equals("scatter") || kind.equals("field")) {
        int i = 0;
        for (var pair : spec.getAsJsonArray("pairs")) {
          g.setColor(COLORS[i++ % COLORS.length]); var points = pair.getAsJsonObject().getAsJsonArray("points");
          if (kind.equals("scatter")) for (var p : points) dot(g, transform, p.getAsJsonArray().get(0).getAsDouble(), p.getAsJsonArray().get(1).getAsDouble());
          else drawPath(g, transform, points, false, null);
        }
      }
      g.setClip(clip);
      for (int i = 0; i < series.size(); i++) { g.setColor(COLORS[i % COLORS.length]); g.drawString(label(series.get(i).getAsJsonObject()), left + (i % 2) * Math.max(60, (right - left) / 2), bottom + 44 + (i / 2) * 13); }
    } finally { g.dispose(); }
    var bytes = new ByteArrayOutputStream(); if (!ImageIO.write(image, "png", bytes)) throw new IOException("The runtime has no PNG writer"); return bytes.toByteArray();
  }
  private static String label(JsonObject s) { return s.get("name").getAsString() + (s.has("unit") && !s.get("unit").isJsonNull() ? " (" + s.get("unit").getAsString() + ")" : ""); }
  private static String format(double v) { return String.format(java.util.Locale.ROOT, "%.3g", v); }
  private static Point2D point(AffineTransform transform, double x, double y) { return transform.transform(new Point2D.Double(x, y), null); }
  private static void line(Graphics2D g, AffineTransform t, double x, double y, double xx, double yy) { g.draw(new Line2D.Double(point(t, x, y), point(t, xx, yy))); }
  private static void dot(Graphics2D g, AffineTransform t, double x, double y) { var p = point(t, x, y); g.fill(new Ellipse2D.Double(p.getX() - 2, p.getY() - 2, 4, 4)); }
  private static void drawPath(Graphics2D g, AffineTransform t, JsonArray points, boolean step, JsonArray windows) {
    JsonArray previous = null;
    for (var value : points) {
      var p = value.getAsJsonArray(); double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble(); dot(g, t, x, y);
      if (previous != null) {
        double xx = previous.get(0).getAsDouble(), yy = previous.get(1).getAsDouble();
        boolean same = windows == null || windows.asList().stream().anyMatch(w -> w.getAsJsonObject().get("start").getAsDouble() <= xx && w.getAsJsonObject().get("end").getAsDouble() >= x);
        if (same) { if (step) { line(g, t, xx, yy, x, yy); line(g, t, x, yy, x, y); } else line(g, t, xx, yy, x, y); }
      }
      previous = p;
    }
  }
  private static double[] bounds(JsonObject spec) {
    String kind = spec.get("kind").getAsString();
    if (kind.equals("field")) { var f = spec.getAsJsonObject("field_geometry"); return new double[]{0, f.get("field_length_m").getAsDouble(), 0, f.get("field_width_m").getAsDouble()}; }
    double[] b = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    if (kind.equals("scatter")) for (var pair : spec.getAsJsonArray("pairs")) for (var p : pair.getAsJsonObject().getAsJsonArray("points")) include(b, p.getAsJsonArray().get(0).getAsDouble(), p.getAsJsonArray().get(1).getAsDouble());
    else for (var entry : spec.getAsJsonArray("series")) {
      var s = entry.getAsJsonObject();
      if (s.has("bins")) for (var value : s.getAsJsonArray("bins")) { var bin = value.getAsJsonObject(); include(b, bin.get("low").getAsDouble(), 0); include(b, bin.get("high").getAsDouble(), bin.get("count").getAsDouble()); }
      else if (s.has("points")) for (var p : s.getAsJsonArray("points")) include(b, p.getAsJsonArray().get(0).getAsDouble(), p.getAsJsonArray().get(1).getAsDouble());
      else for (var value : s.getAsJsonArray("buckets")) { var bin = value.getAsJsonObject(); if (!bin.get("min").isJsonNull()) { include(b, bin.get("start").getAsDouble(), bin.get("min").getAsDouble()); include(b, bin.get("start").getAsDouble(), bin.get("max").getAsDouble()); } }
    }
    for (int i = 0; i < 4; i += 2) { if (!Double.isFinite(b[i])) { b[i] = 0; b[i + 1] = 1; } if (b[i] == b[i + 1]) { b[i] -= .5; b[i + 1] += .5; } }
    return b;
  }
  private static void include(double[] b, double x, double y) { b[0] = Math.min(b[0], x); b[1] = Math.max(b[1], x); b[2] = Math.min(b[2], y); b[3] = Math.max(b[3], y); }
}

/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
import java.nio.file.Path;
import java.util.List;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadQuery;

/** A bounded, server-free consumer. Values and exact coordinates are the same API over HTTP. */
public final class ReadLog {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("ReadLog <allowed-root> <file> <entry>");
    try (var access = LogAccess.local(List.of(Path.of(args[0])));
         var snapshot = access.open(Path.of(args[1]));
         var cursor = snapshot.read(ReadQuery.all(args[2]))) {
      long samples = 0;
      long problems = 0;
      for (;;) {
        var batch = cursor.next();
        samples += batch.samples().size();
        problems += batch.problems().size();
        if (batch.complete()) break;
      }
      System.out.println("samples=" + samples + " decode_problems=" + problems);
    }
  }
}

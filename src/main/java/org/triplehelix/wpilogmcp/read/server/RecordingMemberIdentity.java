/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

/** A detached member proof shared by continuation and fresh selected-entry inspection. A
 * final hash cannot replace a missing writer proof across a live-to-finalized transition. */
public record RecordingMemberIdentity(long generation, String hash, String writerIdentity) {
  public static RecordingMemberIdentity of(OrdinalFollow.Member member) {
    return new RecordingMemberIdentity(member.generation(), member.contentSha256(), member.writerIdentity());
  }
  public String changed(OrdinalFollow.Member next, boolean wasFinalized, boolean finalized) {
    if (generation != next.generation()) return "Recording member was replaced";
    if (writerIdentity != null && !writerIdentity.equals(next.writerIdentity())
        || writerIdentity == null && hash == null && (next.contentSha256() != null || !wasFinalized && finalized))
      return "Recording writer identity changed or its finalization proof is missing";
    if (hash != null && !hash.equals(next.contentSha256())) return "Finalized recording member hash changed";
    return null;
  }
}

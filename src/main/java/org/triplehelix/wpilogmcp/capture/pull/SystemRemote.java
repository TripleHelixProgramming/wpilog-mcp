/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import java.util.List;
import org.triplehelix.wpilogmcp.config.SystemPullConfig;
import org.triplehelix.wpilogmcp.ssh.SshConnection;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/** Extra read-only capabilities of a robot contact; files and commands share its SSH session. */
public interface SystemRemote {
  record SourceFile(RemoteFiles.File file, String source) {}
  List<SourceFile> systemFiles(SystemPullConfig config) throws IOException;
  SshConnection.Command systemCommand(String command) throws IOException;
}

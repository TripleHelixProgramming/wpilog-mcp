/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/** One read-only SSH contact supplies device evidence and the robot's file transport. */
public interface RobotRemote extends RemoteFiles {
  DeviceIdentity identity() throws IOException;
}

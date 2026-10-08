/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class SshPackagingTest {
  @Test void shippedJarCanInitializeEd25519AndRsaSha2WithoutOptionalProviders() throws Exception {
    var jar = Path.of(System.getProperty("install.testJar"));
    try (var loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      for (String name : new String[] {"SignatureEd25519", "SignatureRSASHA256", "SignatureRSASHA512"}) {
        var type = loader.loadClass("com.jcraft.jsch.jce." + name);
        var signature = type.getConstructor().newInstance();
        assertDoesNotThrow(() -> type.getMethod("init").invoke(signature), name);
      }
      assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.bouncycastle.jce.provider.BouncyCastleProvider"));
    }
  }
  @Test void licensesShipAndTheDependencyStaysInTheTransport() throws Exception {
    try (var jar = new JarFile(System.getProperty("install.testJar"))) {
      for (String name : new String[] {"jsch-LICENSE.txt", "jsch-JZlib-LICENSE.txt", "jsch-jBCrypt-LICENSE.txt"}) {
        var entry = jar.getJarEntry("META-INF/licenses/" + name); assertNotNull(entry, name);
        try (var input = jar.getInputStream(entry)) { assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("Copyright")); }
      }
    }
    try (var paths = Files.walk(Path.of("src/main/java"))) {
      for (var file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (Files.readString(file).contains("com.jcraft.jsch")) assertTrue(file.endsWith(Path.of("ssh", "JschConnection.java")), file.toString());
      }
    }
  }
}

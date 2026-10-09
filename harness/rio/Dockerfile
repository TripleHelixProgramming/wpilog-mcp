# syntax=docker/dockerfile:1
# Synthetic Linux environment, not an NI image or any file taken from a robot.
FROM eclipse-temurin:17-jdk-jammy AS runtime
# Exercise both module conclusions: management is present; JFR intentionally is not.
RUN jlink --add-modules java.base,java.desktop,java.logging,java.management,java.management.rmi,java.naming,java.net.http,jdk.management,jdk.management.agent \
    --strip-debug --no-header-files --no-man-pages --output /runtime

FROM ubuntu:22.04
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server coreutils procps util-linux libstdc++6 zlib1g libfreetype6 \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/ssh/ssh_host_* \
    && useradd --create-home --shell /bin/bash lvuser && passwd -d lvuser \
    && mkdir -p /run/sshd /home/lvuser/logs /var/local/natinst/log \
    && chown -R lvuser:lvuser /home/lvuser /var/local/natinst/log \
    && ! command -v journalctl
COPY --from=runtime /runtime /opt/java
COPY harness/robot/build/libs/shop-robot.jar /home/lvuser/frcUserProgram.jar
# The scripted robot uses no camera/OpenCV pipeline; avoid shipping those large native assets.
COPY harness/robot/build/jni/release/libntcore*.so harness/robot/build/jni/release/libwpiHal*.so harness/robot/build/jni/release/libwpiutil*.so harness/robot/build/jni/release/libwpimath*.so /opt/wpilib/
COPY harness/rio/sshd_config /etc/ssh/sshd_config
COPY --chmod=755 harness/rio/entrypoint harness/rio/robotCommand /usr/local/bin/
RUN cp /usr/local/bin/robotCommand /home/lvuser/robotCommand
ENV JAVA_HOME=/opt/java PATH=/opt/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8
ENTRYPOINT ["/usr/local/bin/entrypoint"]

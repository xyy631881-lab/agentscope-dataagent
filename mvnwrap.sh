#!/bin/bash
# Temporary Maven wrapper (local install had empty boot/ dir).
cd "E:/demo/agentscope-dataagent" || exit 1
exec java -classpath "D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar" \
  -Dclassworlds.conf="D:/apache-maven-3.9.16/bin/m2.conf" \
  -Dmaven.home="D:/apache-maven-3.9.16" \
  -Dmaven.multiModuleProjectDirectory="E:/demo/agentscope-dataagent" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"

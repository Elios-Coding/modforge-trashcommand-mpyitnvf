#!/usr/bin/env bash
# Lightweight gradlew bootstrapper for ModForge AI generated projects.
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
mkdir -p "$DIR/gradle/wrapper"
if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
  echo "Downloading gradle-wrapper.jar (Gradle 8.14.3)..."
  curl -fsSL -o "$WRAPPER_JAR" \
    https://raw.githubusercontent.com/gradle/gradle/v8.14.3/gradle/wrapper/gradle-wrapper.jar
fi
exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"

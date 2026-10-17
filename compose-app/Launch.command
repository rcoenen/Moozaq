#!/bin/zsh
set -e
cd "$(dirname "$0")"
export MOOZAQ_ROOT="$(cd .. && pwd)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
exec ./gradlew :composeApp:run

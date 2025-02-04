#!/bin/bash
set -e

echo "Starting $0 at $(date)"

androidxArguments="$*"

WORKING_DIR="$(pwd)"
SCRIPTS_DIR="$(cd $(dirname $0)/.. && pwd)"
cd "$SCRIPTS_DIR/../../.."
echo "Script running from $(pwd)"

# resolve DIST_DIR
if [ -z "$DIST_DIR" ]; then
  DIST_DIR="$WORKING_DIR/out/dist"
fi
mkdir -p "$DIST_DIR"

export OUT_DIR=$(pwd)/out
export DIST_DIR="$DIST_DIR"

if [ "$STUDIO_DIR" == "" ]; then
  STUDIO_DIR="$WORKING_DIR"
else
  STUDIO_DIR="$(cd $STUDIO_DIR && pwd)"
fi

TOOLS_DIR=$STUDIO_DIR/tools
gw="$TOOLS_DIR/gradlew -Dorg.gradle.jvmargs=-Xmx24g"

function buildStudio() {
  STUDIO_BUILD_LOG="$OUT_DIR/studio.log"
  if JAVA_HOME="$STUDIO_DIR/prebuilts/studio/jdk/jdk11/linux" $gw -p $TOOLS_DIR publishLocal --stacktrace > "$STUDIO_BUILD_LOG" 2>&1; then
    echo built studio successfully
  else
    cat "$STUDIO_BUILD_LOG" >&2
    echo failed to build studio
    return 1
  fi
}
buildStudio

export GRADLE_PLUGIN_VERSION=`grep -oP "(?<=buildVersion = ).*" $TOOLS_DIR/buildSrc/base/version.properties`
export GRADLE_PLUGIN_REPO="$STUDIO_DIR/out/repo:$STUDIO_DIR/prebuilts/tools/common/m2/repository"
export JAVA_HOME="$(pwd)/prebuilts/jdk/jdk11/linux-x86/"
export JAVA_TOOLS_JAR="$(pwd)/prebuilts/jdk/jdk8/linux-x86/lib/tools.jar"
export LINT_PRINT_STACKTRACE=true

function buildAndroidx() {
  LOG_PROCESSOR="$SCRIPTS_DIR/../development/build_log_processor.sh"
  "$LOG_PROCESSOR"                   $gw $properties -p frameworks/support    $androidxArguments --profile
  $SCRIPTS_DIR/impl/parse_profile_htmls.sh
}

buildAndroidx
echo "Completing $0 at $(date)"

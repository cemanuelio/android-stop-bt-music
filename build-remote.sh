#!/usr/bin/env bash
# Syncs this project to the remote build machine (batman@pc.lan, more RAM/CPU than the
# local dev box) and runs a Gradle task there. Avoids OOM-ing the local desktop.
#
# Usage: ./build-remote.sh [gradle task...]   (defaults to :app:assembleDebug)
set -euo pipefail

REMOTE="batman@pc.lan"
REMOTE_DIR="~/dev/stop-bl-music"
TASKS=("${@:-:app:assembleDebug}")

rsync -az --delete \
  --exclude='.git' --exclude='build' --exclude='.gradle' --exclude='local.properties' \
  "$(dirname "$0")/" "$REMOTE:$REMOTE_DIR/"

ssh "$REMOTE" "cd $REMOTE_DIR && chmod +x gradlew && \
  [ -f local.properties ] || echo 'sdk.dir=/home/batman/Android/Sdk' > local.properties; \
  ./gradlew ${TASKS[*]}"

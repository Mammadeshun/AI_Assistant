#!/usr/bin/env bash
# Builds the Android app and copies the APK to dist/.
#
# Needs: JDK 17+, Gradle, and an Android SDK with platform 35 + build-tools 35.
# Point ANDROID_HOME at your SDK, or let this script use the usual locations.
set -euo pipefail

cd "$(dirname "$0")"

if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Android/Sdk" "/opt/android-sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "Could not find an Android SDK. Install one and set ANDROID_HOME." >&2
  exit 1
fi

export ANDROID_SDK_ROOT="$ANDROID_HOME"
echo "sdk.dir=$ANDROID_HOME" > android/local.properties

VARIANT="${1:-release}"
case "$VARIANT" in
  release) TASK="assembleRelease"; OUT="android/app/build/outputs/apk/release/app-release.apk" ;;
  debug)   TASK="assembleDebug";   OUT="android/app/build/outputs/apk/debug/app-debug.apk" ;;
  *) echo "usage: $0 [release|debug]" >&2; exit 1 ;;
esac

echo "→ building $VARIANT with SDK at $ANDROID_HOME"
(cd android && gradle --no-daemon "$TASK")

mkdir -p dist
cp "$OUT" "dist/financial-manager-$VARIANT.apk"
echo "→ dist/financial-manager-$VARIANT.apk"
ls -lh "dist/financial-manager-$VARIANT.apk"

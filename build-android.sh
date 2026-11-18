#!/usr/bin/env bash
# Builds dist/CallBell.apk inside Docker. Nothing Android-related is installed on the Mac.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=callbell-android-builder
RUN=(docker run --rm --platform linux/amd64
     -v "$PWD/android":/project
     -v callbell-gradle-cache:/root/.gradle
     "$IMAGE")

echo "==> Building Docker image (first time takes a few minutes)"
docker build --platform linux/amd64 -t "$IMAGE" -f tools/android-builder.Dockerfile tools

if [ ! -f android/keystore/callbell.jks ]; then
  echo "==> Creating signing key (kept in android/keystore so updates install cleanly)"
  mkdir -p android/keystore
  "${RUN[@]}" keytool -genkeypair -keystore /project/keystore/callbell.jks \
      -storepass callbell -keypass callbell -alias callbell \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=CallBell" >/dev/null
fi

echo "==> Building APK"
"${RUN[@]}" gradle --no-daemon --no-watch-fs --console=plain -q assembleRelease

mkdir -p dist
cp android/app/build/outputs/apk/release/app-release.apk dist/CallBell.apk
echo "==> Done: dist/CallBell.apk"

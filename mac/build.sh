#!/usr/bin/env bash
# Builds dist/CallBell.app (universal: Apple Silicon + Intel) with plain swiftc.
set -euo pipefail
cd "$(dirname "$0")/.."

APP=dist/CallBell.app
BUILD=mac/.build
rm -rf "$APP" "$BUILD"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$BUILD"

for arch in arm64 x86_64; do
  echo "==> Compiling for $arch"
  swiftc -O -swift-version 5 \
    -target "$arch-apple-macos13.0" \
    -module-name CallBell \
    mac/Sources/*.swift \
    -o "$BUILD/CallBell-$arch"
done
lipo -create "$BUILD/CallBell-arm64" "$BUILD/CallBell-x86_64" -output "$APP/Contents/MacOS/CallBell"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>CallBell</string>
  <key>CFBundleDisplayName</key><string>CallBell</string>
  <key>CFBundleIdentifier</key><string>com.callbell.mac</string>
  <key>CFBundleExecutable</key><string>CallBell</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key>
  <string>CallBell listens on your Wi-Fi for your Android phone to tell it about incoming calls.</string>
</dict>
</plist>
PLIST

codesign --force --sign - "$APP"
echo "==> Done: $APP"

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="$ROOT_DIR/core"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"

mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/armeabi-v7a"

cd "$CORE_DIR"

echo "==> Building Go proxy core for android arm64-v8a..."
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o "$JNI_DIR/arm64-v8a/libcarwithin_proxy.so" .

echo "==> Building Go proxy core for android armeabi-v7a..."
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -trimpath -ldflags="-s -w" -o "$JNI_DIR/armeabi-v7a/libcarwithin_proxy.so" .

ls -lh "$JNI_DIR/arm64-v8a/libcarwithin_proxy.so" "$JNI_DIR/armeabi-v7a/libcarwithin_proxy.so"

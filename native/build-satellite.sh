#!/usr/bin/env sh
# Builds the bundled xwayland-satellite binaries used by WaylandCraft.
#
# The resulting executables are embedded in the jar at the jar root as
# `xwayland-satellite-linux-gnu-<arch>` and extracted at runtime by
# WaylandCraftBridge, so users do NOT need to install xwayland-satellite
# themselves.
#
# System requirements at runtime (NOT bundled):
#   - Xwayland >= 23.1  (present on virtually all Wayland desktops)
#   - libxcb, libxcb-cursor (present on virtually all X11-capable desktops)
#
# Usage:
#   ./build-satellite.sh            # builds for the host arch only
#   XWS_ARCH=aarch64 ./build-satellite.sh   # cross-build for arm64
#
# Requires: cargo + rustup target + clang + libxcb-cursor-dev
set -e

cd "$(dirname "$0")"

XWS_SRC="${XWS_SRC:-/tmp/xwayland-satellite-src}"
XWS_REPO="https://github.com/Supreeeme/xwayland-satellite.git"
ARCH="${XWS_ARCH:-$(uname -m)}"

case "$ARCH" in
    x86_64) TARGET="x86_64-unknown-linux-gnu"; PLATFORM="x86_64" ;;
    aarch64|arm64) TARGET="aarch64-unknown-linux-gnu"; PLATFORM="arm64" ;;
    *) echo "Unsupported arch: $ARCH" >&2; exit 1 ;;
esac

if [ ! -d "$XWS_SRC" ]; then
    echo "==> Cloning xwayland-satellite into $XWS_SRC"
    git clone --depth 1 "$XWS_REPO" "$XWS_SRC"
fi

echo "==> Building xwayland-satellite for $TARGET"
(
    cd "$XWS_SRC"
    if [ "$TARGET" != "x86_64-unknown-linux-gnu" ] && ! rustup target list --installed | grep -q "$TARGET"; then
        rustup target add "$TARGET"
    fi
    cargo build --release --target "$TARGET"
)

BIN="$XWS_SRC/target/$TARGET/release/xwayland-satellite"
OUT="xwayland-satellite-linux-gnu-$PLATFORM"

echo "==> Installing stripped binary as native/$OUT"
cp "$BIN" "$OUT"
strip "$OUT"
chmod 755 "$OUT"
ls -la "$OUT"

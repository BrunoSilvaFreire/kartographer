#!/usr/bin/env bash
set -e

echo "Building Kartographer CLI and Daemon..."
./gradlew :cli:installDist :daemon:installDist

BIN_DIR="$HOME/.local/bin"
mkdir -p "$BIN_DIR"

CLI_BIN="$(pwd)/cli/build/install/cli/bin/cli"
DAEMON_BIN="$(pwd)/daemon/build/install/daemon/bin/daemon"

echo "Symlinking $CLI_BIN to $BIN_DIR/karto"
ln -sf "$CLI_BIN" "$BIN_DIR/karto"

echo "Symlinking $CLI_BIN to $BIN_DIR/kartographer"
ln -sf "$CLI_BIN" "$BIN_DIR/kartographer"

echo "Symlinking $DAEMON_BIN to $BIN_DIR/kartographer-daemon"
ln -sf "$DAEMON_BIN" "$BIN_DIR/kartographer-daemon"

echo "Bootstrap complete! Make sure $BIN_DIR is in your PATH."
echo "You can now run 'karto' from your terminal."

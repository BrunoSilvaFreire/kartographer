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

# Stop any running daemon so it restarts from the freshly built distribution.
# The daemon caches compiled pipelines and task classes, so a rebuild would
# otherwise keep executing stale code until the next CLI connect detected the
# mismatch. Its shutdown hook removes the socket; the next `karto` invocation
# spawns a fresh daemon on demand.
echo "Restarting the Kartographer daemon..."
if pkill -f 'io.kartographer.daemon.MainKt' 2>/dev/null; then
    for _ in $(seq 1 20); do
        pgrep -f 'io.kartographer.daemon.MainKt' >/dev/null 2>&1 || break
        sleep 0.25
    done
    pkill -9 -f 'io.kartographer.daemon.MainKt' 2>/dev/null || true
    echo "Stopped a stale Kartographer daemon; it will start fresh on the next 'karto' run."
else
    echo "No Kartographer daemon was running."
fi

echo "Bootstrap complete! Make sure $BIN_DIR is in your PATH."
echo "You can now run 'karto' from your terminal."

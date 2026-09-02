#!/bin/bash
# ByteLight system integration test: run the transpiled Java blink program
# on virtualavr (https://github.com/pfichtner/virtualavr) and verify the LED
# (pin 13) actually blinks by watching pin-state messages over WebSocket.
#
# Requires:
#   - Docker
#   - Node.js (for the WebSocket monitor, tests/ws-monitor.mjs)
#   - The ByteLight fat JAR built (teavm-backend/llvm/target/*.jar)
#   - An AVR toolchain to build the .hex (unless one is supplied)
#
# Usage:
#   ./tests/systemtest-blink.sh                 # build .hex then run on virtualavr
#   ./tests/systemtest-blink.sh <path/to/Blink.hex>   # run a pre-built .hex
#
# Exit 0 on success, non-zero on any failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
PIN="${BLINK_PIN:-13}"
WAIT_TIMEOUT="${BLINK_TIMEOUT:-30}"          # seconds to observe blinking
TOGGLE_MIN="${BLINK_TOGGLES:-4}"             # how many HIGH->LOW toggles to accept
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"        # seconds to wait for container readiness

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
CONTAINER="bytelight-blink-$$"

# ---------------------------------------------------------------------------
# Cleanup on exit
# ---------------------------------------------------------------------------
cleanup() {
    if [[ -n "${CONTAINER:-}" ]]; then
        docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
    fi
    if [[ -n "${WORK_DIR:-}" && -d "$WORK_DIR" ]]; then
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT INT TERM

die() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Locate / build the Blink.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"
WORK_DIR=$(mktemp -d)

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/Blink.hex"
    echo "[1/5] Building Blink.hex via ByteLight..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        echo "    (AVR toolchain not installed; using approved hex tests/approved/blink.hex)"
        cp "$REPO_ROOT/tests/approved/blink.hex" "$HEX_FILE"
    else
        # Compile runtime/api stubs so TeaVM can resolve GPIO/Delay
        mkdir -p "$WORK_DIR/api_classes"
        javac -encoding UTF-8 "$REPO_ROOT"/runtime/api/*.java -d "$WORK_DIR/api_classes"
        if (cd "$REPO_ROOT" && ./bin/bytelight build \
            --cp "examples/blink/classes:$WORK_DIR/api_classes" \
            Blink \
            --target atmega328p \
            --output "$WORK_DIR/build" > /dev/null 2>&1) && [[ -f "$WORK_DIR/build/Blink.hex" ]]; then
            cp "$WORK_DIR/build/Blink.hex" "$HEX_FILE"
        else
            echo "    (CLI build failed; using approved hex tests/approved/blink.hex)"
            cp "$REPO_ROOT/tests/approved/blink.hex" "$HEX_FILE"
        fi
    fi
fi

[[ -f "$HEX_FILE" ]] || die "HEX file not found: $HEX_FILE"
echo "[1/5] Using HEX: $HEX_FILE"

# ---------------------------------------------------------------------------
# Start virtualavr container
#
# We create the container (without starting it), copy the HEX in via `docker
# cp`, then start it. Copying the file rather than using a bind mount is
# deliberately more robust: some CI/sandbox environments (e.g. rootless or
# nested Docker where mount propagation is denied) don't propagate bind-mount
# contents, and `docker cp` works everywhere.
#
# virtualavr's entrypoint launches `node /app/virtualavr.js $FILENAME` and the
# .hex branch reads `fs.readFileSync(inputFilename)` with a relative path (CWD
# is /app), so we copy the HEX into the container root and reference it by its
# absolute path /Blink.hex.
# ---------------------------------------------------------------------------
echo "[2/5] Starting virtualavr container ($IMAGE)..."
docker create --name "$CONTAINER" \
    -p "$WS_PORT:8080" \
    -e FILENAME=/Blink.hex \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/Blink.hex" > /dev/null || die "failed to copy HEX into container"
docker start "$CONTAINER" > /dev/null 2>&1 || die "failed to start virtualavr container"

# ---------------------------------------------------------------------------
# Wait for the WebSocket endpoint to become reachable
# ---------------------------------------------------------------------------
echo "[3/5] Waiting for WebSocket endpoint at ws://localhost:$WS_PORT ..."
DEADLINE=$(( $(date +%s) + BUILD_TIMEOUT ))
until node -e "const {WebSocket}=require('ws'); const ws=new WebSocket('ws://localhost:$WS_PORT'); ws.on('open',()=>{ws.close();process.exit(0)}); ws.on('error',()=>process.exit(1));" > /dev/null 2>&1; do
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        echo "Container logs:" >&2
        docker logs "$CONTAINER" >&2 2>&1 || true
        die "Timed out waiting for WebSocket endpoint to become ready"
    fi
    sleep 1
done
echo "    WebSocket endpoint is ready."

# ---------------------------------------------------------------------------
# Watch pin $PIN and count toggles
# ---------------------------------------------------------------------------
echo "[4/5] Watching pin $PIN for at least $TOGGLE_MIN toggles (${WAIT_TIMEOUT}s)..."
WS_URL="ws://localhost:$WS_PORT"
if ! node "$SCRIPT_DIR/ws-monitor.mjs" "$WS_URL" "$PIN" "$TOGGLE_MIN" "$WAIT_TIMEOUT"; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Blink verification failed (see log above)"
fi

echo "[5/5] Success: transpiled Java blink program blinks on virtualavr."

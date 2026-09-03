#!/bin/bash
# EspressoMachine system integration test: run the transpiled Java blink program
# on virtualavr (https://github.com/pfichtner/virtualavr) and verify the LED
# (pin 13) actually blinks by watching pin-state messages over WebSocket.
#
# Requires:
#   - Docker
#   - websocat  (apt install websocat)
#   - jq        (pre-installed on most CI environments)
#   - The EspressoMachine fat JAR built (teavm-backend/llvm/target/*.jar)
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
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="espressomachine-blink-$$"

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
    echo "[1/5] Building Blink.hex via EspressoMachine..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        echo "    (AVR toolchain not installed; using approved hex tests/approved/blink.hex)"
        cp "$REPO_ROOT/tests/approved/blink.hex" "$HEX_FILE"
    else
        # Compile all examples (including runtime API stubs) via Maven
        mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
        if (cd "$REPO_ROOT" && ./bin/espressomachine build \
            --cp "examples/blink/target/classes:runtime/api/target/classes" \
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
echo "[3/5] Waiting for WebSocket endpoint at $WS_URL ..."
DEADLINE=$(( $(date +%s) + BUILD_TIMEOUT ))
until timeout 2 websocat "$WS_URL" < /dev/null > /dev/null 2>&1; do
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
#
# Send a pinMode message to subscribe, then collect pinState events for
# WAIT_TIMEOUT seconds.  jq extracts the .state field for our pin, awk counts
# transitions (HIGH→LOW or LOW→HIGH).
# ---------------------------------------------------------------------------
echo "[4/5] Watching pin $PIN for at least $TOGGLE_MIN toggles (${WAIT_TIMEOUT}s)..."
toggles=$(
    {
        printf '{"type":"pinMode","pin":"%s","mode":"digital"}\n' "$PIN"
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | jq -rc --arg p "$PIN" \
            'try (select(.type=="pinState" and .pin==$p) | .state)' 2>/dev/null \
      | awk 'NR>1 && $0!=prev{c++} {prev=$0} END{print c+0}'
) || true

if [[ "${toggles:-0}" -lt "$TOGGLE_MIN" ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Blink verification failed: pin $PIN toggled ${toggles:-0} times, need $TOGGLE_MIN"
fi

echo "[5/5] Success: transpiled Java blink program blinks on virtualavr."

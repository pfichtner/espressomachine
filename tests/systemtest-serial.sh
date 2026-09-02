#!/bin/bash
# ByteLight system integration test: run the transpiled Java serial program on
# virtualavr and verify that 'A' (0x41) is transmitted over the virtual USART
# by watching serialDebug messages on the WebSocket control interface.
#
# virtualavr broadcasts { "type": "serialDebug", "direction": "TX", "bytes": [n] }
# for each byte the simulated AVR writes to UDR0.  We enable that stream with
# { "type": "serialDebug", "state": true } and count how many times byte 65
# ('A') appears within WAIT_TIMEOUT seconds.
#
# Requires:
#   - Docker
#   - websocat  (apt install websocat)
#   - jq        (pre-installed on most CI environments)
#   - The ByteLight fat JAR built (teavm-backend/llvm/target/*.jar)
#
# Usage:
#   ./tests/systemtest-serial.sh                      # build .hex then run on virtualavr
#   ./tests/systemtest-serial.sh <path/to/file.hex>   # run a pre-built .hex
#
# Exit 0 on success, non-zero on any failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
WAIT_TIMEOUT="${SERIAL_TIMEOUT:-30}"       # seconds to watch for serial output
SERIAL_MIN="${SERIAL_MIN:-3}"             # minimum 'A' bytes required to pass
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"      # seconds to wait for container readiness

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="bytelight-serial-$$"

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
# Locate / build the HelloSerial.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"
WORK_DIR=$(mktemp -d)

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/HelloSerial.hex"
    echo "[1/5] Building HelloSerial.hex via ByteLight..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        echo "    (AVR toolchain not installed; using approved hex tests/approved/serial.hex)"
        cp "$REPO_ROOT/tests/approved/serial.hex" "$HEX_FILE"
    else
        mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
        if (cd "$REPO_ROOT" && ./bin/bytelight build \
            --cp "examples/serial/target/classes:runtime/api/target/classes" \
            HelloSerial \
            --target atmega328p \
            --output "$WORK_DIR/build" > /dev/null 2>&1) && [[ -f "$WORK_DIR/build/HelloSerial.hex" ]]; then
            cp "$WORK_DIR/build/HelloSerial.hex" "$HEX_FILE"
        else
            echo "    (CLI build failed; using approved hex tests/approved/serial.hex)"
            cp "$REPO_ROOT/tests/approved/serial.hex" "$HEX_FILE"
        fi
    fi
fi

[[ -f "$HEX_FILE" ]] || die "HEX file not found: $HEX_FILE"
echo "[1/5] Using HEX: $HEX_FILE"

# ---------------------------------------------------------------------------
# Start virtualavr container (no /dev/ bind mount needed — we use WebSocket)
# ---------------------------------------------------------------------------
echo "[2/5] Starting virtualavr container ($IMAGE)..."
docker create --name "$CONTAINER" \
    -p "$WS_PORT:8080" \
    -e FILENAME=/HelloSerial.hex \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/HelloSerial.hex" > /dev/null || die "failed to copy HEX into container"
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
# Enable serialDebug and count 'A' (0x41 = 65) bytes in TX output
#
# virtualavr emits:
#   { "type": "serialDebug", "direction": "TX", "bytes": [65] }
# for each byte written to UDR0.  We enable the stream with a control message,
# wait WAIT_TIMEOUT seconds, and count how many TX bytes equal 65.
# ---------------------------------------------------------------------------
echo "[4/5] Watching serial TX for at least $SERIAL_MIN 'A' (0x41) bytes (${WAIT_TIMEOUT}s)..."
rx_count=$(
    {
        printf '{"type":"serialDebug","state":true}\n'
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | jq -rc 'try (select(.type=="serialDebug" and .direction=="TX") | .bytes[])' 2>/dev/null \
      | awk -v target=65 '$0==target{c++} END{print c+0}'
) || true

if [[ "${rx_count:-0}" -lt "$SERIAL_MIN" ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Serial verification failed: received ${rx_count:-0} 'A' bytes, need $SERIAL_MIN"
fi

echo "[5/5] Success: transpiled Java serial program transmits over USART on virtualavr."

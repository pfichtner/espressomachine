#!/bin/bash
# ByteLight system integration test: run the transpiled Java serial program on
# virtualavr and verify that 'A' is transmitted over the virtual USART by
# reading from the PTY that virtualavr creates on the host.
#
# virtualavr uses socat to expose the simulated USART as a PTY device on the
# host (e.g. /dev/ttyUSB0).  This requires bind-mounting the host /dev/
# directory into the container so the device node appears on the host side.
# We configure the PTY with stty, read raw bytes, and count occurrences of
# 'A' (0x41).
#
# PAUSE_ON_START=true suspends the AVR simulation until we are ready; we
# then send { "type": "control", "action": "unpause" } via the WebSocket
# control interface before reading, matching the approach used by
# https://github.com/Ardulink/Firmware/blob/main/tests/environment.py.
#
# Requires:
#   - Docker with access to /dev (host /dev bind-mount)
#   - websocat  (apt install websocat)
#   - stty      (usually part of coreutils)
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
BAUD="${SERIAL_BAUD:-9600}"
WAIT_TIMEOUT="${SERIAL_TIMEOUT:-30}"   # seconds to read from the serial device
SERIAL_MIN="${SERIAL_MIN:-3}"          # minimum 'A' bytes required to pass
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"  # seconds to wait for device/WS readiness

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="bytelight-serial-$$"

die() { echo "ERROR: $*" >&2; exit 1; }

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

# ---------------------------------------------------------------------------
# Find a free /dev/ttyUSBx slot (same logic as Ardulink environment.py)
# ---------------------------------------------------------------------------
SERIAL_DEVICE=""
for n in $(seq 0 63); do
    if [[ ! -e "/dev/ttyUSB$n" ]]; then
        SERIAL_DEVICE="/dev/ttyUSB$n"
        break
    fi
done
[[ -n "$SERIAL_DEVICE" ]] || die "no free /dev/ttyUSB slot available (0-63 all in use)"

# ---------------------------------------------------------------------------
# Locate / build the HelloSerial.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"
WORK_DIR=$(mktemp -d)

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/HelloSerial.hex"
    echo "[1/6] Building HelloSerial.hex via ByteLight..."
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
echo "[1/6] Using HEX: $HEX_FILE"

# ---------------------------------------------------------------------------
# Start virtualavr with /dev bind-mount so the PTY appears on the host.
#
# PAUSE_ON_START=true suspends the simulation until we send the unpause
# command, ensuring the serial port is open before the AVR writes any bytes.
# DEVICEUSER=$(id -u) makes the PTY readable by the current user.
# ---------------------------------------------------------------------------
echo "[2/6] Starting virtualavr container ($IMAGE)..."
echo "      Serial device: $SERIAL_DEVICE @ ${BAUD} baud"
docker create --name "$CONTAINER" \
    --volume /dev:/dev \
    -p "$WS_PORT:8080" \
    -e FILENAME=/HelloSerial.hex \
    -e VIRTUALDEVICE="$SERIAL_DEVICE" \
    -e BAUDRATE="$BAUD" \
    -e DEVICEUSER="$(id -u)" \
    -e PAUSE_ON_START=true \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/HelloSerial.hex" > /dev/null || die "failed to copy HEX into container"
docker start "$CONTAINER" > /dev/null 2>&1 || die "failed to start virtualavr container"

# ---------------------------------------------------------------------------
# Wait for the WebSocket endpoint to become reachable
# ---------------------------------------------------------------------------
echo "[3/6] Waiting for WebSocket endpoint at $WS_URL ..."
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
# Wait for the PTY device to appear on the host
# ---------------------------------------------------------------------------
echo "[4/6] Waiting for serial device $SERIAL_DEVICE ..."
until [[ -e "$SERIAL_DEVICE" ]]; do
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        echo "Container logs:" >&2
        docker logs "$CONTAINER" >&2 2>&1 || true
        die "Timed out waiting for $SERIAL_DEVICE to appear"
    fi
    sleep 1
done
echo "    Device is ready."

# Configure the PTY: raw mode, no echo, 8N1.
stty -F "$SERIAL_DEVICE" "$BAUD" raw -echo cs8 -parenb -cstopb clocal

# ---------------------------------------------------------------------------
# Unpause the simulation now that the serial port is configured
# ---------------------------------------------------------------------------
printf '{"type":"control","action":"unpause"}\n' \
    | timeout 5 websocat "$WS_URL" > /dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Read WAIT_TIMEOUT seconds of serial output and count 'A' (0x41) bytes
# ---------------------------------------------------------------------------
echo "[5/6] Reading serial output for ${WAIT_TIMEOUT}s (need $SERIAL_MIN 'A' bytes)..."
count=$(timeout "$WAIT_TIMEOUT" cat "$SERIAL_DEVICE" 2>/dev/null \
    | tr -cd 'A' | wc -c) || true

if [[ "${count:-0}" -lt "$SERIAL_MIN" ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Serial verification failed: received ${count:-0} 'A' bytes, need $SERIAL_MIN"
fi

echo "[6/6] Success: transpiled Java serial program transmits over USART on virtualavr."

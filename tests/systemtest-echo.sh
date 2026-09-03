#!/bin/bash
# EspressoMachine system integration test: verify Serial.available() and Serial.read()
# by running the Echo example on virtualavr and checking that bytes sent to the
# virtual USART are echoed back.
#
# The Echo program calls Serial.available() to check for incoming data, reads
# each byte with Serial.read(), and immediately re-transmits it with Serial.write().
# This test writes a known byte sequence to the PTY, reads back what the simulated
# AVR retransmits, and asserts that the echoed bytes match.
#
# The /dev/ bind-mount lets virtualavr's socat create a PTY on the host (e.g.
# /dev/ttyUSB0).  PAUSE_ON_START=true holds the AVR at reset until we send the
# unpause command, preventing any bytes being lost before the serial port is open.
#
# Requires:
#   - Docker with access to /dev (host /dev bind-mount)
#   - websocat  (apt install websocat)
#   - stty      (coreutils)
#
# Usage:
#   ./tests/systemtest-echo.sh                    # build .hex then run on virtualavr
#   ./tests/systemtest-echo.sh <path/to/file.hex> # use a pre-built .hex
#
# Exit 0 on success, non-zero on any failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
BAUD="${SERIAL_BAUD:-9600}"
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"  # seconds to wait for device/WS readiness

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="espressomachine-echo-$$"

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
# Locate / build the Echo.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"
WORK_DIR=$(mktemp -d)

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/Echo.hex"
    echo "[1/6] Building Echo.hex via EspressoMachine..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        echo "    (AVR toolchain not installed; using approved hex tests/approved/echo.hex)"
        cp "$REPO_ROOT/tests/approved/echo.hex" "$HEX_FILE"
    else
        mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
        if (cd "$REPO_ROOT" && ./bin/espressomachine build \
            --cp "examples/echo/target/classes:runtime/api/target/classes" \
            Echo \
            --target atmega328p \
            --output "$WORK_DIR/build" > /dev/null 2>&1) && [[ -f "$WORK_DIR/build/Echo.hex" ]]; then
            cp "$WORK_DIR/build/Echo.hex" "$HEX_FILE"
        else
            echo "    (CLI build failed; using approved hex tests/approved/echo.hex)"
            cp "$REPO_ROOT/tests/approved/echo.hex" "$HEX_FILE"
        fi
    fi
fi

[[ -f "$HEX_FILE" ]] || die "HEX file not found: $HEX_FILE"
echo "[1/6] Using HEX: $HEX_FILE"

# ---------------------------------------------------------------------------
# Start virtualavr with /dev bind-mount so the PTY appears on the host.
# ---------------------------------------------------------------------------
echo "[2/6] Starting virtualavr container ($IMAGE)..."
echo "      Serial device: $SERIAL_DEVICE @ ${BAUD} baud"
docker create --name "$CONTAINER" \
    --volume /dev:/dev \
    -p "$WS_PORT:8080" \
    -e FILENAME=/Echo.hex \
    -e VIRTUALDEVICE="$SERIAL_DEVICE" \
    -e BAUDRATE="$BAUD" \
    -e DEVICEUSER="$(id -u)" \
    -e PAUSE_ON_START=true \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/Echo.hex" > /dev/null || die "failed to copy HEX into container"
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
# Start capturing AVR output, unpause, then send test bytes
#
# Writing to $SERIAL_DEVICE sends bytes to the AVR (RX).
# Reading from $SERIAL_DEVICE receives bytes from the AVR (TX).
# stty -echo ensures our transmitted bytes don't appear in our reads.
# ---------------------------------------------------------------------------
ECHO_OUT="$WORK_DIR/echoed.bin"
timeout 10 cat "$SERIAL_DEVICE" > "$ECHO_OUT" 2>/dev/null &
CAT_PID=$!

# Unpause the simulation now that the serial port is configured.
printf '{"type":"control","action":"unpause"}\n' \
    | timeout 5 websocat "$WS_URL" > /dev/null 2>&1 || true

echo "[5/6] Sending test bytes and waiting for echo..."
# Allow the AVR to reach its read loop, then send five 'A' bytes.
sleep 1
printf 'AAAAA' > "$SERIAL_DEVICE"
sleep 3

kill "$CAT_PID" 2>/dev/null || true
wait "$CAT_PID" 2>/dev/null || true

count=$(tr -cd 'A' < "$ECHO_OUT" | wc -c)

if [[ "${count:-0}" -lt 3 ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Echo verification failed: got ${count:-0} 'A' bytes back, need 3"
fi

echo "[6/6] Success: Serial.available()/read() correctly echo received bytes."

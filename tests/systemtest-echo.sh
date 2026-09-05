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

# shellcheck disable=SC2034,SC1091  # config consumed by tests/lib/systemtest-lib.sh, dynamic source path
set -euo pipefail

PROG_NAME="Echo"
ENTRY_CLASS="Echo"
EXAMPLE_DIR="echo"
APPROVED_HEX="echo.hex"
CONTAINER_TAG="echo"
STEPS=6
ST_SERIAL=1
ST_PAUSE=true

BAUD="${SERIAL_BAUD:-9600}"

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/systemtest-lib.sh"
st_init

# ---------------------------------------------------------------------------
# Find a free /dev/ttyUSBx slot (same logic as Ardulink environment.py)
# ---------------------------------------------------------------------------
st_find_serial_device

# ---------------------------------------------------------------------------
# Locate / build the Echo.hex
# ---------------------------------------------------------------------------
st_find_hex "${1:-}"

# ---------------------------------------------------------------------------
# Start virtualavr with /dev bind-mount so the PTY appears on the host.
# ---------------------------------------------------------------------------
st_start_container

# ---------------------------------------------------------------------------
# Wait for the WebSocket endpoint to become reachable
# ---------------------------------------------------------------------------
st_wait_ws

# ---------------------------------------------------------------------------
# Wait for the PTY device to appear on the host
# ---------------------------------------------------------------------------
st_wait_serial_device

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

st_step "Sending test bytes and waiting for echo..."
# Allow the AVR to reach its read loop, then send five 'A' bytes.
sleep 1
printf 'AAAAA' > "$SERIAL_DEVICE"
sleep 3

kill "$CAT_PID" 2>/dev/null || true
wait "$CAT_PID" 2>/dev/null || true

count=$(tr -cd 'A' < "$ECHO_OUT" | wc -c)

if [[ "${count:-0}" -lt 3 ]]; then
    st_logs_and_die "Echo verification failed: got ${count:-0} 'A' bytes back, need 3"
fi

st_step "Success: Serial.available()/read() correctly echo received bytes."
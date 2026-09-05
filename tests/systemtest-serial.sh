#!/bin/bash
# EspressoMachine system integration test: run the transpiled Java serial program on
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
#   - The EspressoMachine fat JAR built (teavm-backend/llvm/target/*.jar)
#
# Usage:
#   ./tests/systemtest-serial.sh                      # build .hex then run on virtualavr
#   ./tests/systemtest-serial.sh <path/to/file.hex>   # run a pre-built .hex
#
# Exit 0 on success, non-zero on any failure.

# shellcheck disable=SC2034,SC1091  # config consumed by tests/lib/systemtest-lib.sh, dynamic source path
set -euo pipefail

PROG_NAME="HelloSerial"
ENTRY_CLASS="HelloSerial"
EXAMPLE_DIR="serial"
APPROVED_HEX="serial.hex"
CONTAINER_TAG="serial"
STEPS=6
ST_SERIAL=1
ST_PAUSE=true

BAUD="${SERIAL_BAUD:-9600}"
WAIT_TIMEOUT="${SERIAL_TIMEOUT:-30}"   # seconds to read from the serial device
SERIAL_MIN="${SERIAL_MIN:-3}"          # minimum 'A' bytes required to pass

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/systemtest-lib.sh"
st_init

# ---------------------------------------------------------------------------
# Find a free /dev/ttyUSBx slot (same logic as Ardulink environment.py)
# ---------------------------------------------------------------------------
st_find_serial_device

# ---------------------------------------------------------------------------
# Locate / build the HelloSerial.hex
# ---------------------------------------------------------------------------
st_find_hex "${1:-}"

# ---------------------------------------------------------------------------
# Start virtualavr with /dev bind-mount so the PTY appears on the host.
#
# PAUSE_ON_START=true suspends the simulation until we send the unpause
# command, ensuring the serial port is open before the AVR writes any bytes.
# DEVICEUSER=$(id -u) makes the PTY readable by the current user.
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
# Unpause the simulation now that the serial port is configured
# ---------------------------------------------------------------------------
printf '{"type":"control","action":"unpause"}\n' \
    | timeout 5 websocat "$WS_URL" > /dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Read WAIT_TIMEOUT seconds of serial output and count 'A' (0x41) bytes
# ---------------------------------------------------------------------------
st_step "Reading serial output for ${WAIT_TIMEOUT}s (need $SERIAL_MIN 'A' bytes)..."
count=$(timeout "$WAIT_TIMEOUT" cat "$SERIAL_DEVICE" 2>/dev/null \
    | tr -cd 'A' | wc -c) || true

if [[ "${count:-0}" -lt "$SERIAL_MIN" ]]; then
    st_logs_and_die "Serial verification failed: received ${count:-0} 'A' bytes, need $SERIAL_MIN"
fi

st_step "Success: transpiled Java serial program transmits over USART on virtualavr."
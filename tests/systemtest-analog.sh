#!/bin/bash
# EspressoMachine system integration test: run the transpiled Java AnalogBlink program
# on virtualavr, inject an ADC value via WebSocket, and verify the LED (pin 13)
# blinks at the expected rate.
#
# Protocol:
#   Client → {"type":"pinState","pin":"A0","state":800}  inject ADC value (> 512 → fast blink)
#   Client → {"type":"pinMode","pin":"13","mode":"digital"}  subscribe to pin 13
#   Server → {"type":"pinState","pin":"13","state":true/false,...}  observed toggles
#
# Requires:
#   - Docker
#   - websocat  (apt install websocat)
#   - jq        (pre-installed on most CI environments)
#   - The EspressoMachine fat JAR built (teavm-backend/llvm/target/*.jar)
#   - An AVR toolchain to build the .hex (unless one is supplied)
#
# Usage:
#   ./tests/systemtest-analog.sh                          # build .hex then run
#   ./tests/systemtest-analog.sh <path/to/AnalogBlink.hex>  # run a pre-built .hex
#
# Exit 0 on success, non-zero on any failure.

# shellcheck disable=SC2034,SC1091  # config consumed by tests/lib/systemtest-lib.sh, dynamic source path
set -euo pipefail

PROG_NAME="AnalogBlink"
ENTRY_CLASS="AnalogBlink"
EXAMPLE_DIR="analog-blink"
APPROVED_HEX="analog-blink.hex"
CONTAINER_TAG="analog"
STEPS=5

PIN="${BLINK_PIN:-13}"
ADC_PIN="${ADC_PIN:-A0}"
ADC_VALUE="${ADC_VALUE:-800}"          # > 512 → fast-blink path (100 ms half-period)
WAIT_TIMEOUT="${BLINK_TIMEOUT:-5}"     # seconds to observe blinking
TOGGLE_MIN="${BLINK_TOGGLES:-6}"       # 6 toggles in 5 s requires ≤ 833 ms period; fast=200 ms ✓, slow=1000 ms ✗

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/systemtest-lib.sh"
st_init

# ---------------------------------------------------------------------------
# Locate / build the AnalogBlink.hex
# ---------------------------------------------------------------------------
st_find_hex "${1:-}"

# ---------------------------------------------------------------------------
# Start virtualavr container
# ---------------------------------------------------------------------------
st_start_container

# ---------------------------------------------------------------------------
# Wait for the WebSocket endpoint to become reachable
# ---------------------------------------------------------------------------
st_wait_ws

# ---------------------------------------------------------------------------
# Inject ADC value and watch pin $PIN for toggles.
#
# Send the ADC value first so it is in effect when the MCU executes the first
# analogRead.  Then subscribe to pin 13 and count HIGH↔LOW transitions.
# ADC_VALUE=800 (> 512) selects the fast-blink path (100 ms half-period = 200 ms
# full period).  6 toggles in 5 s requires ≤ 833 ms/toggle — easily met by the
# fast path but impossible for the slow path (1000 ms/toggle).
# ---------------------------------------------------------------------------
st_step "Injecting ADC $ADC_PIN=$ADC_VALUE, watching pin $PIN for ≥$TOGGLE_MIN toggles (${WAIT_TIMEOUT}s)..."
toggles=$(
    {
        ws_pin_inject "$ADC_PIN" "$ADC_VALUE"
        ws_pin_subscribe "$PIN"
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | st_count_pin_toggles "$PIN"
) || true

if [[ "${toggles:-0}" -lt "$TOGGLE_MIN" ]]; then
    st_logs_and_die "Analog blink verification failed: pin $PIN toggled ${toggles:-0} times (need $TOGGLE_MIN). ADC value $ADC_VALUE may not have been applied or the fast-blink path was not taken."
fi

st_step "Success: transpiled Java AnalogBlink program blinks at expected rate on virtualavr (${toggles} toggles in ${WAIT_TIMEOUT}s with ADC $ADC_PIN=$ADC_VALUE)."
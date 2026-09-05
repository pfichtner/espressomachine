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

# shellcheck disable=SC2034,SC1091  # config consumed by tests/lib/systemtest-lib.sh, dynamic source path
set -euo pipefail

PROG_NAME="Blink"
ENTRY_CLASS="Blink"
EXAMPLE_DIR="blink"
APPROVED_HEX="blink.hex"
CONTAINER_TAG="blink"
STEPS=5

PIN="${BLINK_PIN:-13}"
WAIT_TIMEOUT="${BLINK_TIMEOUT:-30}"          # seconds to observe blinking
TOGGLE_MIN="${BLINK_TOGGLES:-4}"             # how many HIGH->LOW toggles to accept

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/systemtest-lib.sh"
st_init

# ---------------------------------------------------------------------------
# Locate / build the Blink.hex
# ---------------------------------------------------------------------------
st_find_hex "${1:-}"

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
st_start_container

# ---------------------------------------------------------------------------
# Wait for the WebSocket endpoint to become reachable
# ---------------------------------------------------------------------------
st_wait_ws

# ---------------------------------------------------------------------------
# Watch pin $PIN and count toggles
#
# Send a pinMode message to subscribe, then collect pinState events for
# WAIT_TIMEOUT seconds.  jq extracts the .state field for our pin, awk counts
# transitions (HIGH→LOW or LOW→HIGH).
# ---------------------------------------------------------------------------
st_step "Watching pin $PIN for at least $TOGGLE_MIN toggles (${WAIT_TIMEOUT}s)..."
toggles=$(
    {
        ws_pin_subscribe "$PIN"
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | st_count_pin_toggles "$PIN"
) || true

if [[ "${toggles:-0}" -lt "$TOGGLE_MIN" ]]; then
    st_logs_and_die "Blink verification failed: pin $PIN toggled ${toggles:-0} times, need $TOGGLE_MIN"
fi

st_step "Success: transpiled Java blink program blinks on virtualavr."
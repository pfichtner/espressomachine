#!/bin/bash
# EspressoMachine system integration test: run the transpiled Java OOP blink
# program on virtualavr (https://github.com/pfichtner/virtualavr) and verify
# that TWO LEDs (pin 13 and pin 12) actually blink IN TURN by watching pin-state
# messages over WebSocket. This exercises the class-method call transformation
# (Led.on()/Led.off()/Led constructor) that OopBlink relies on.
#
# Requires:
#   - Docker
#   - websocat  (apt install websocat)
#   - jq        (pre-installed on most CI environments)
#   - The EspressoMachine fat JAR built (teavm-backend/llvm/target/*.jar)
#   - An AVR toolchain to build the .hex (unless one is supplied)
#
# Usage:
#   ./tests/systemtest-oop-blink.sh                   # build .hex then run on virtualavr
#   ./tests/systemtest-oop-blink.sh <path/to/OopBlink.hex>   # run a pre-built .hex
#
# Exit 0 on success, non-zero on any failure.

# shellcheck disable=SC2034,SC1091  # config consumed by tests/lib/systemtest-lib.sh, dynamic source path
set -euo pipefail

PROG_NAME="OopBlink"
ENTRY_CLASS="OopBlink"
EXAMPLE_DIR="oop-blink"
APPROVED_HEX="oop-blink.hex"
CONTAINER_TAG="oop-blink"
STEPS=5

PIN1="${OOPBLINK_PIN1:-13}"
PIN2="${OOPBLINK_PIN2:-12}"
WAIT_TIMEOUT="${OOPBLINK_TIMEOUT:-30}"        # seconds to observe blinking
TOGGLE_MIN="${OOPBLINK_TOGGLES:-4}"           # how many toggles per pin to accept

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/systemtest-lib.sh"
st_init

# ---------------------------------------------------------------------------
# Locate / build the OopBlink.hex
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
# Watch pins $PIN1 and $PIN2
#
# Send a pinMode message for each pin to subscribe, then collect pinState
# events for WAIT_TIMEOUT seconds. jq reduces the stream to "pin state" pairs,
# awk tracks the last known state of each pin to count per-pin toggles and to
# detect any simultaneous HIGH (which would mean the two LEDs are NOT blinking
# in turn).
# ---------------------------------------------------------------------------
st_step "Watching pins $PIN1 and $PIN2 for at least $TOGGLE_MIN toggles each (${WAIT_TIMEOUT}s)..."
result=$(
    {
        ws_pin_subscribe "$PIN1"
        ws_pin_subscribe "$PIN2"
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | jq -rc --arg p1 "$PIN1" --arg p2 "$PIN2" \
            'try (select(.type=="pinState" and (.pin==$p1 or .pin==$p2)) | "\(.pin) \(.state)")' 2>/dev/null \
      | awk -v p1="$PIN1" -v p2="$PIN2" '
            {
                if ($2 == "true") cur = 1; else cur = 0;
                if (state[$1] != "" && state[$1] != cur) toggles[$1]++;
                state[$1] = cur;
                if (cur == 1) {
                    for (other in state) {
                        if (other != $1 && state[other] == 1) overlap++;
                    }
                }
            }
            END { printf "tog=%d/%d,%d/%d overlap=%d",
                    toggles[p1]+0, p1, toggles[p2]+0, p2, overlap+0 }
        '
) || true

tog1=$(printf '%s' "$result" | sed -n 's/.*tog=\([0-9]*\)\/[0-9]*,[0-9]*\/[0-9]*.*/\1/p')
tog2=$(printf '%s' "$result" | sed -n 's/.*tog=[0-9]*\/[0-9]*,\([0-9]*\)\/[0-9]*.*/\1/p')
overlap=$(printf '%s' "$result" | sed -n 's/.*overlap=\([0-9]*\).*/\1/p')

if [[ -z "$result" ]]; then
    st_logs_and_die "Oop-blink verification failed: no pinState events received"
fi

if [[ "${tog1:-0}" -lt "$TOGGLE_MIN" || "${tog2:-0}" -lt "$TOGGLE_MIN" ]]; then
    st_logs_and_die "Oop-blink verification failed: pin $PIN1 toggled ${tog1:-0}, pin $PIN2 toggled ${tog2:-0} (need $TOGGLE_MIN each)"
fi

if [[ "${overlap:-0}" -ne 0 ]]; then
    st_logs_and_die "Oop-blink verification failed: pins $PIN1 and $PIN2 were simultaneously HIGH $overlap times (expected 0 — LEDs must blink in turn)"
fi

st_step "Success: transpiled Java OOP blink blinks pins $PIN1 and $PIN2 in turn on virtualavr."
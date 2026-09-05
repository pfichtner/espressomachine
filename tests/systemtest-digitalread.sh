#!/bin/bash
# EspressoMachine system integration test: run the transpiled Java DigitalRead
# program on virtualavr and verify that pin 13 (LED) blinks when pin 2 is HIGH.
#
# Protocol:
#   Client → {"type":"pinState","pin":"2","state":false,"replyId":"..."} normalize LOW
#   Client → {"type":"pinState","pin":"2","state":true, "replyId":"..."} drive HIGH
#   Client → {"type":"pinMode","pin":"13","mode":"digital","replyId":"..."} subscribe
#   Server → {"type":"pinState","pin":"13","state":true/false,...} observed blinks
#   Server → {"replyId":"...","executed":true,...} injection acknowledged
#
# With INPUT_PULLUP on pin 2 and blink logic in loop():
#   pin 2 HIGH → LED blinks at ~2.5 Hz (200 ms on / 200 ms off)
#   pin 2 LOW  → LED stays off
#
# replyId acknowledgements let us sequence subscription → normalize → inject
# without fixed sleeps: each step waits until virtualavr confirms the previous
# message was processed before sending the next.
#
# Requires:
#   - Docker
#   - websocat  (apt install websocat)
#   - jq        (pre-installed on most CI environments)
#   - The EspressoMachine fat JAR built
#
# Usage:
#   ./tests/systemtest-digitalread.sh
#   ./tests/systemtest-digitalread.sh <path/to/DigitalRead.hex>
#
# Exit 0 on success, non-zero on any failure.

# shellcheck disable=SC2034,SC1091  # config consumed by tests/lib/systemtest-lib.sh, dynamic source path
set -euo pipefail

PROG_NAME="DigitalRead"
ENTRY_CLASS="DigitalRead"
EXAMPLE_DIR="digitalread"
APPROVED_HEX="digitalread.hex"
CONTAINER_TAG="digitalread"
STEPS=5

LED_PIN="${LED_PIN:-13}"
BTN_PIN="${BTN_PIN:-2}"
WAIT_TIMEOUT="${DR_TIMEOUT:-5}"
TOGGLE_MIN="${DR_TOGGLES:-4}"      # ≥4 blink edges in 5 s; blink = 400 ms/cycle → ~12 edges

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/systemtest-lib.sh"
st_init

ACK_FILE=$(mktemp)

# ---------------------------------------------------------------------------
# Locate / build the DigitalRead.hex
# ---------------------------------------------------------------------------
st_find_hex "${1:-}"

# ---------------------------------------------------------------------------
# Start virtualavr container
# ---------------------------------------------------------------------------
st_start_container

# ---------------------------------------------------------------------------
# Wait for WebSocket endpoint
# ---------------------------------------------------------------------------
st_wait_ws

# ---------------------------------------------------------------------------
# Drive pin BTN_PIN HIGH and verify pin LED_PIN blinks.
#
# Messages are sequenced via replyId acknowledgements so each step waits
# until virtualavr confirms the previous message before sending the next:
#
#   1. Subscribe to LED pin  → wait for "sub" ack
#   2. Normalize BTN to LOW  → wait for "low" ack  (handles INPUT_PULLUP case
#      where the pull-up may have already driven the pin HIGH before we subscribed)
#   3. Drive BTN HIGH        → wait for "high" ack
#   4. Count LED blink edges for WAIT_TIMEOUT seconds
#
# The concurrent pipeline structure: the writer process polls ACK_FILE which
# the reader side-channel fills from received replyId messages; both run as
# concurrent stages of the same bash pipeline.
# ---------------------------------------------------------------------------
st_step "Subscribing to pin $LED_PIN, injecting pin $BTN_PIN HIGH → expecting ≥${TOGGLE_MIN} blink edges in ${WAIT_TIMEOUT}s..."

sub_id=$(uuidgen); low_id=$(uuidgen); high_id=$(uuidgen)
toggles=$(
    (
        ws_pin_subscribe "$LED_PIN" "$sub_id"
        st_wait_ack "$sub_id"

        ws_pin_inject "$BTN_PIN" false "$low_id"
        st_wait_ack "$low_id"

        ws_pin_inject "$BTN_PIN" true "$high_id"
        st_wait_ack "$high_id"

        sleep "$WAIT_TIMEOUT"
    ) | websocat "$WS_URL" 2>/dev/null \
      | while IFS= read -r line; do
            printf '%s\n' "$line"
            rid=$(printf '%s' "$line" | jq -r '.replyId // empty' 2>/dev/null)
            [[ -n "$rid" ]] && printf '%s\n' "$rid" >> "$ACK_FILE"
        done \
      | st_count_pin_toggles "$LED_PIN"
) || true

if [[ "${toggles:-0}" -lt "$TOGGLE_MIN" ]]; then
    st_logs_and_die "Digital-read blink test failed: pin $LED_PIN toggled ${toggles:-0} times (need $TOGGLE_MIN). Pin $BTN_PIN injection may not have been applied."
fi

st_step "Success: transpiled Java DigitalRead blinks pin $LED_PIN ${toggles} times when pin $BTN_PIN is HIGH."
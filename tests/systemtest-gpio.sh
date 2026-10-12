#!/bin/bash
# EspressoMachine system integration test: run the transpiled Java GpioShowcase
# program on virtualavr and verify that pin 13 (LED) blinks when pin 2 is HIGH.
#
# GpioShowcase uses the mockable Gpio wrapper for every GPIO call; the backend
# devirtualises gpio.x() to the GPIO statics. Injecting pin 2 HIGH drives the
# digitalRead/digitalWrite blink path (analogRead/analogWrite run alongside).
#
# Protocol:
#   Client → {"type":"pinState","pin":"2","state":false,"replyId":"..."} normalize LOW
#   Client → {"type":"pinState","pin":"2","state":true, "replyId":"..."} drive HIGH
#   Client → {"type":"pinMode","pin":"13","mode":"digital","replyId":"..."} subscribe
#   Server → {"type":"pinState","pin":"13","state":true/false,...} observed blinks
#   Server → {"replyId":"...","executed":true,...} injection acknowledged
#
# Requires:
#   - Docker
#   - websocat  (apt install websocat)
#   - jq        (pre-installed on most CI environments)
#   - The EspressoMachine fat JAR built
#
# Usage:
#   ./tests/systemtest-gpio.sh
#   ./tests/systemtest-gpio.sh <path/to/GpioShowcase.hex>
#
# Exit 0 on success, non-zero on any failure.

# shellcheck disable=SC2034,SC1091  # config consumed by tests/lib/systemtest-lib.sh, dynamic source path
set -euo pipefail

PROG_NAME="GpioShowcase"
ENTRY_CLASS="GpioShowcase"
EXAMPLE_DIR="gpio-showcase"
APPROVED_HEX="gpio-showcase.hex"
CONTAINER_TAG="gpio-showcase"
STEPS=5

LED_PIN="${LED_PIN:-13}"
BTN_PIN="${BTN_PIN:-2}"
WAIT_TIMEOUT="${GPIO_TIMEOUT:-5}"
TOGGLE_MIN="${GPIO_TOGGLES:-4}"    # ≥4 blink edges in 5 s; blink = 400 ms/cycle → ~12 edges

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/systemtest-lib.sh"
st_init

ACK_FILE=$(mktemp)

# ---------------------------------------------------------------------------
# Locate / build the GpioShowcase.hex
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
# Subscribe to the LED, normalize the button LOW, then drive it HIGH and count
# LED blink edges.  replyId acknowledgements sequence the steps.
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
    st_logs_and_die "GpioShowcase blink failed: pin $LED_PIN toggled ${toggles:-0} times (need $TOGGLE_MIN). Pin $BTN_PIN injection may not have been applied."
fi

st_step "Success: transpiled Java GpioShowcase blinks pin $LED_PIN ${toggles} times when pin $BTN_PIN is HIGH."

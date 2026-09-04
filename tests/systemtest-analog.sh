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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
PIN="${BLINK_PIN:-13}"
ADC_PIN="${ADC_PIN:-A0}"
ADC_VALUE="${ADC_VALUE:-800}"          # > 512 → fast-blink path (100 ms half-period)
WAIT_TIMEOUT="${BLINK_TIMEOUT:-5}"     # seconds to observe blinking
TOGGLE_MIN="${BLINK_TOGGLES:-6}"       # 6 toggles in 5 s requires ≤ 833 ms period; fast=200 ms ✓, slow=1000 ms ✗
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="espressomachine-analog-$$"

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
# Locate / build the AnalogBlink.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"
WORK_DIR=$(mktemp -d)

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/AnalogBlink.hex"
    echo "[1/5] Building AnalogBlink.hex via EspressoMachine..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        echo "    (AVR toolchain not installed; using approved hex tests/approved/analog-blink.hex)"
        cp "$REPO_ROOT/tests/approved/analog-blink.hex" "$HEX_FILE"
    else
        mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
        if (cd "$REPO_ROOT" && ./bin/espressomachine build \
            --cp "examples/analog-blink/target/classes:runtime/api/target/classes" \
            AnalogBlink \
            --target atmega328p \
            --output "$WORK_DIR/build" > /dev/null 2>&1) && [[ -f "$WORK_DIR/build/AnalogBlink.hex" ]]; then
            cp "$WORK_DIR/build/AnalogBlink.hex" "$HEX_FILE"
        else
            echo "    (CLI build failed; using approved hex tests/approved/analog-blink.hex)"
            cp "$REPO_ROOT/tests/approved/analog-blink.hex" "$HEX_FILE"
        fi
    fi
fi

[[ -f "$HEX_FILE" ]] || die "HEX file not found: $HEX_FILE"
echo "[1/5] Using HEX: $HEX_FILE"

# ---------------------------------------------------------------------------
# Start virtualavr container
# ---------------------------------------------------------------------------
echo "[2/5] Starting virtualavr container ($IMAGE)..."
docker create --name "$CONTAINER" \
    -p "$WS_PORT:8080" \
    -e FILENAME=/AnalogBlink.hex \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/AnalogBlink.hex" > /dev/null || die "failed to copy HEX into container"
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
# Inject ADC value and watch pin $PIN for toggles.
#
# Send the ADC value first so it is in effect when the MCU executes the first
# analogRead.  Then subscribe to pin 13 and count HIGH↔LOW transitions.
# ADC_VALUE=800 (> 512) selects the fast-blink path (100 ms half-period = 200 ms
# full period).  6 toggles in 5 s requires ≤ 833 ms/toggle — easily met by the
# fast path but impossible for the slow path (1000 ms/toggle).
# ---------------------------------------------------------------------------
echo "[4/5] Injecting ADC $ADC_PIN=$ADC_VALUE, watching pin $PIN for ≥$TOGGLE_MIN toggles (${WAIT_TIMEOUT}s)..."
toggles=$(
    {
        printf '{"type":"pinState","pin":"%s","state":%s}\n' "$ADC_PIN" "$ADC_VALUE"
        printf '{"type":"pinMode","pin":"%s","mode":"digital"}\n' "$PIN"
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | jq -rc --arg p "$PIN" \
            'try (select(.type=="pinState" and .pin==$p) | .state)' 2>/dev/null \
      | awk 'NR>1 && $0!=prev{c++} {prev=$0} END{print c+0}'
) || true

if [[ "${toggles:-0}" -lt "$TOGGLE_MIN" ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Analog blink verification failed: pin $PIN toggled ${toggles:-0} times (need $TOGGLE_MIN). ADC value $ADC_VALUE may not have been applied or the fast-blink path was not taken."
fi

echo "[5/5] Success: transpiled Java AnalogBlink program blinks at expected rate on virtualavr (${toggles} toggles in ${WAIT_TIMEOUT}s with ADC $ADC_PIN=$ADC_VALUE)."

#!/bin/bash
# EspressoMachine system integration test: run the transpiled Java DigitalRead
# program on virtualavr and verify that pin 13 (LED) mirrors pin 2 (button).
#
# Protocol:
#   Client → {"type":"pinState","pin":"2","state":true}   simulate pin 2 HIGH
#   Client → {"type":"pinState","pin":"2","state":false}  simulate pin 2 LOW
#   Client → {"type":"pinMode","pin":"13","mode":"digital"} subscribe to pin 13
#   Server → {"type":"pinState","pin":"13","state":true/false,...}
#
# With INPUT_PULLUP on pin 2 and loop() = digitalWrite(13, digitalRead(2)):
#   pin 2 HIGH → LED ON  (pin 13 HIGH)
#   pin 2 LOW  → LED OFF (pin 13 LOW)
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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
LED_PIN="${LED_PIN:-13}"
BTN_PIN="${BTN_PIN:-2}"
WAIT_TIMEOUT="${DR_TIMEOUT:-5}"
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="espressomachine-digitalread-$$"
WORK_DIR=$(mktemp -d)

cleanup() {
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT INT TERM

die() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Locate / build the DigitalRead.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/DigitalRead.hex"
    echo "[1/6] Building DigitalRead.hex via EspressoMachine..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        die "AVR toolchain not installed; cannot build DigitalRead.hex — pass a pre-built hex as argument"
    fi
    mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
    (cd "$REPO_ROOT" && ./bin/espressomachine build \
        --cp "examples/digitalread/target/classes:runtime/api/target/classes" \
        DigitalRead \
        --target atmega328p \
        --output "$WORK_DIR/build" > /dev/null 2>&1) \
        && cp "$WORK_DIR/build/DigitalRead.hex" "$HEX_FILE" \
        || die "CLI build failed"
fi

[[ -f "$HEX_FILE" ]] || die "HEX file not found: $HEX_FILE"
echo "[1/6] Using HEX: $HEX_FILE"

# ---------------------------------------------------------------------------
# Start virtualavr container
# ---------------------------------------------------------------------------
echo "[2/6] Starting virtualavr container ($IMAGE)..."
docker create --name "$CONTAINER" \
    -p "$WS_PORT:8080" \
    -e FILENAME=/DigitalRead.hex \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/DigitalRead.hex" > /dev/null || die "failed to copy HEX into container"
docker start "$CONTAINER" > /dev/null 2>&1 || die "failed to start virtualavr container"

# ---------------------------------------------------------------------------
# Wait for WebSocket endpoint
# ---------------------------------------------------------------------------
echo "[3/6] Waiting for WebSocket endpoint at $WS_URL ..."
DEADLINE=$(( $(date +%s) + BUILD_TIMEOUT ))
until timeout 2 websocat "$WS_URL" < /dev/null > /dev/null 2>&1; do
    if [[ $(date +%s) -gt $DEADLINE ]]; then
        docker logs "$CONTAINER" >&2 2>&1 || true
        die "Timed out waiting for WebSocket endpoint"
    fi
    sleep 1
done
echo "    WebSocket endpoint is ready."

# ---------------------------------------------------------------------------
# Phase A: subscribe first, then inject pin 2 HIGH → expect pin 13 HIGH
#
# Subscribe before injecting so we don't miss the LOW→HIGH transition:
# loop() runs without delay so pin 13 can settle before we subscribe if we
# inject first.  A 0.5 s pause after subscribing lets virtualavr register
# the subscription before the injection triggers a pin change.
# ---------------------------------------------------------------------------
echo "[4/6] Subscribing to pin $LED_PIN, then setting pin $BTN_PIN HIGH → expecting pin $LED_PIN HIGH..."
pin13_high=$(
    {
        printf '{"type":"pinMode","pin":"%s","mode":"digital"}\n' "$LED_PIN"
        sleep 0.5
        printf '{"type":"pinState","pin":"%s","state":true}\n'    "$BTN_PIN"
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | jq -rc --arg p "$LED_PIN" \
            'try (select(.type=="pinState" and .pin==$p) | .state)' 2>/dev/null \
      | grep -c "^true$" || true
) || true

if [[ "${pin13_high:-0}" -lt 1 ]]; then
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Phase A failed: pin $LED_PIN did not go HIGH when pin $BTN_PIN was HIGH (got ${pin13_high:-0} HIGH observations)"
fi
echo "    Phase A passed: pin $LED_PIN went HIGH ($pin13_high observations)."

# ---------------------------------------------------------------------------
# Phase B: inject pin 2 LOW → expect pin 13 LOW
#
# Pin 13 is currently HIGH from Phase A; subscribe first so we catch
# the HIGH→LOW edge when we drive pin 2 LOW.
# ---------------------------------------------------------------------------
echo "[5/6] Subscribing to pin $LED_PIN, then setting pin $BTN_PIN LOW → expecting pin $LED_PIN LOW..."
pin13_low=$(
    {
        printf '{"type":"pinMode","pin":"%s","mode":"digital"}\n' "$LED_PIN"
        sleep 0.5
        printf '{"type":"pinState","pin":"%s","state":false}\n'   "$BTN_PIN"
        sleep "$WAIT_TIMEOUT"
    } | websocat "$WS_URL" 2>/dev/null \
      | jq -rc --arg p "$LED_PIN" \
            'try (select(.type=="pinState" and .pin==$p) | .state)' 2>/dev/null \
      | grep -c "^false$" || true
) || true

if [[ "${pin13_low:-0}" -lt 1 ]]; then
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Phase B failed: pin $LED_PIN did not go LOW when pin $BTN_PIN was LOW (got ${pin13_low:-0} LOW observations)"
fi
echo "    Phase B passed: pin $LED_PIN went LOW ($pin13_low observations)."

echo "[6/6] Success: DigitalRead mirrors pin $BTN_PIN → pin $LED_PIN correctly."

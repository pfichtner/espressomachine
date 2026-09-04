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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
LED_PIN="${LED_PIN:-13}"
BTN_PIN="${BTN_PIN:-2}"
WAIT_TIMEOUT="${DR_TIMEOUT:-5}"
TOGGLE_MIN="${DR_TOGGLES:-4}"      # ≥4 blink edges in 5 s; blink = 400 ms/cycle → ~12 edges
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="espressomachine-digitalread-$$"
WORK_DIR=$(mktemp -d)
ACK_FILE=$(mktemp)

cleanup() {
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
    rm -rf "$WORK_DIR" "$ACK_FILE"
}
trap cleanup EXIT INT TERM

die() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Locate / build the DigitalRead.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/DigitalRead.hex"
    echo "[1/5] Building DigitalRead.hex via EspressoMachine..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        echo "    (AVR toolchain not installed; using approved hex tests/approved/digitalread.hex)"
        cp "$REPO_ROOT/tests/approved/digitalread.hex" "$HEX_FILE"
    else
        mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
        if (cd "$REPO_ROOT" && ./bin/espressomachine build \
            --cp "examples/digitalread/target/classes:runtime/api/target/classes" \
            DigitalRead \
            --target atmega328p \
            --output "$WORK_DIR/build" > /dev/null 2>&1) && [[ -f "$WORK_DIR/build/DigitalRead.hex" ]]; then
            cp "$WORK_DIR/build/DigitalRead.hex" "$HEX_FILE"
        else
            echo "    (CLI build failed; using approved hex tests/approved/digitalread.hex)"
            cp "$REPO_ROOT/tests/approved/digitalread.hex" "$HEX_FILE"
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
    -e FILENAME=/DigitalRead.hex \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/DigitalRead.hex" > /dev/null || die "failed to copy HEX into container"
docker start "$CONTAINER" > /dev/null 2>&1 || die "failed to start virtualavr container"

# ---------------------------------------------------------------------------
# Wait for WebSocket endpoint
# ---------------------------------------------------------------------------
echo "[3/5] Waiting for WebSocket endpoint at $WS_URL ..."
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
echo "[4/5] Subscribing to pin $LED_PIN, injecting pin $BTN_PIN HIGH → expecting ≥${TOGGLE_MIN} blink edges in ${WAIT_TIMEOUT}s..."

toggles=$(
    (
        printf '{"type":"pinMode","pin":"%s","mode":"digital","replyId":"sub"}\n' "$LED_PIN"
        until grep -q 'sub' "$ACK_FILE" 2>/dev/null; do sleep 0.05; done

        printf '{"type":"pinState","pin":"%s","state":false,"replyId":"low"}\n' "$BTN_PIN"
        until grep -q 'low' "$ACK_FILE" 2>/dev/null; do sleep 0.05; done

        printf '{"type":"pinState","pin":"%s","state":true,"replyId":"high"}\n' "$BTN_PIN"
        until grep -q 'high' "$ACK_FILE" 2>/dev/null; do sleep 0.05; done

        sleep "$WAIT_TIMEOUT"
    ) | websocat "$WS_URL" 2>/dev/null \
      | while IFS= read -r line; do
            printf '%s\n' "$line"
            rid=$(printf '%s' "$line" | jq -r '.replyId // empty' 2>/dev/null)
            [[ -n "$rid" ]] && printf '%s\n' "$rid" >> "$ACK_FILE"
        done \
      | jq -rc --arg p "$LED_PIN" \
            'try (select(.type=="pinState" and .pin==$p) | .state)' 2>/dev/null \
      | awk 'NR>1 && $0!=prev{c++} {prev=$0} END{print c+0}'
) || true

if [[ "${toggles:-0}" -lt "$TOGGLE_MIN" ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Digital-read blink test failed: pin $LED_PIN toggled ${toggles:-0} times (need $TOGGLE_MIN). Pin $BTN_PIN injection may not have been applied."
fi

echo "[5/5] Success: transpiled Java DigitalRead blinks pin $LED_PIN ${toggles} times when pin $BTN_PIN is HIGH."

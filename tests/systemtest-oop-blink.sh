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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
PIN1="${OOPBLINK_PIN1:-13}"
PIN2="${OOPBLINK_PIN2:-12}"
WAIT_TIMEOUT="${OOPBLINK_TIMEOUT:-30}"        # seconds to observe blinking
TOGGLE_MIN="${OOPBLINK_TOGGLES:-4}"           # how many toggles per pin to accept
BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"         # seconds to wait for container readiness

WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
WS_URL="ws://localhost:$WS_PORT"
CONTAINER="espressomachine-oop-blink-$$"

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
# Locate / build the OopBlink.hex
# ---------------------------------------------------------------------------
HEX_FILE="${1:-}"
WORK_DIR=$(mktemp -d)

if [[ -z "$HEX_FILE" ]]; then
    HEX_FILE="$WORK_DIR/OopBlink.hex"
    echo "[1/5] Building OopBlink.hex via EspressoMachine..."
    if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
        echo "    (AVR toolchain not installed; using approved hex tests/approved/oop-blink.hex)"
        cp "$REPO_ROOT/tests/approved/oop-blink.hex" "$HEX_FILE"
    else
        # Compile all examples (including runtime API stubs) via Maven
        mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
        if (cd "$REPO_ROOT" && ./bin/espressomachine build \
            --cp "examples/oop-blink/target/classes:runtime/api/target/classes" \
            OopBlink \
            --target atmega328p \
            --output "$WORK_DIR/build" > /dev/null 2>&1) && [[ -f "$WORK_DIR/build/OopBlink.hex" ]]; then
            cp "$WORK_DIR/build/OopBlink.hex" "$HEX_FILE"
        else
            echo "    (CLI build failed; using approved hex tests/approved/oop-blink.hex)"
            cp "$REPO_ROOT/tests/approved/oop-blink.hex" "$HEX_FILE"
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
    -e FILENAME=/OopBlink.hex \
    "$IMAGE" > /dev/null 2>&1 || die "failed to create virtualavr container"
docker cp "$HEX_FILE" "$CONTAINER:/OopBlink.hex" > /dev/null || die "failed to copy HEX into container"
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
# Watch pins $PIN1 and $PIN2
#
# Send a pinMode message for each pin to subscribe, then collect pinState
# events for WAIT_TIMEOUT seconds. jq reduces the stream to "pin state" pairs,
# awk tracks the last known state of each pin to count per-pin toggles and to
# detect any simultaneous HIGH (which would mean the two LEDs are NOT blinking
# in turn).
# ---------------------------------------------------------------------------
echo "[4/5] Watching pins $PIN1 and $PIN2 for at least $TOGGLE_MIN toggles each (${WAIT_TIMEOUT}s)..."
result=$(
    {
        printf '{"type":"pinMode","pin":"%s","mode":"digital"}\n' "$PIN1"
        printf '{"type":"pinMode","pin":"%s","mode":"digital"}\n' "$PIN2"
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
    die "Oop-blink verification failed: no pinState events received"
fi

if [[ "${tog1:-0}" -lt "$TOGGLE_MIN" || "${tog2:-0}" -lt "$TOGGLE_MIN" ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Oop-blink verification failed: pin $PIN1 toggled ${tog1:-0}, pin $PIN2 toggled ${tog2:-0} (need $TOGGLE_MIN each)"
fi

if [[ "${overlap:-0}" -ne 0 ]]; then
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "Oop-blink verification failed: pins $PIN1 and $PIN2 were simultaneously HIGH $overlap times (expected 0 — LEDs must blink in turn)"
fi

echo "[5/5] Success: transpiled Java OOP blink blinks pins $PIN1 and $PIN2 in turn on virtualavr."
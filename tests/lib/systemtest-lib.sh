#!/bin/bash
# Shared helpers for the EspressoMachine virtualavr system tests
# (tests/systemtest-*.sh).  Source this library from a system test script
# AFTER defining its per-test configuration:
#
#   PROG_NAME      hex basename + /FILENAME in the container  e.g. "OopBlink"
#   ENTRY_CLASS    Java entry point class                     e.g. "OopBlink"
#   EXAMPLE_DIR    examples module directory                  e.g. "oop-blink"
#   APPROVED_HEX   fallback .hex under tests/approved/        e.g. "oop-blink.hex"
#   CONTAINER_TAG  container name fragment                    e.g. "oop-blink"
#   STEPS          total number of steps for [n/N] numbering
#   ST_SERIAL      "1" for the serial variant (mounts /dev)   (default: off)
#   BAUD           baud rate; required when ST_SERIAL=1
#   ST_PAUSE       run paused until the "unpause" control msg (ST_SERIAL only)
#
# Provided (in calling order):
#   st_init               - derive REPO_ROOT, allocate WS port + work dir,
#                           install cleanup trap, reset step counter
#   st_find_serial_device - pick a free /dev/ttyUSBn slot (serial variants)
#   st_find_hex [hex]     - build -- or fall back to the approved hex for $1
#   st_start_container    - docker create/cp/start the virtualavr container
#   st_wait_ws            - poll until the WebSocket endpoint is reachable
#   st_wait_serial_device - poll until the PTY appears, then configure stty
#   st_step <label>       - print "[n/N] label" (auto-numbered)
#   st_logs_and_die <msg> - dump container logs to stderr, then die
#
# WebSocket helpers:
#   ws_pin_subscribe <pin> [replyId]   - print pinMode subscribe JSON line
#   ws_pin_inject <pin> <val> [replyId]- print pinState inject JSON line
#   ws_unpause_msg                     - print control/unpause JSON line
#   st_ws_unpause                      - send unpause and discard reply
#   st_count_pin_toggles <pin>         - stdin filter: count HIGH<->LOW transitions
#   st_wait_ack <id>                   - block until <id> appears in $ACK_FILE

set -euo pipefail

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$LIB_DIR/../.." && pwd)"

: "${PROG_NAME:?PROG_NAME not set (see tests/lib/systemtest-lib.sh)}" \
  "${ENTRY_CLASS:?ENTRY_CLASS not set (see tests/lib/systemtest-lib.sh)}" \
  "${EXAMPLE_DIR:?EXAMPLE_DIR not set (see tests/lib/systemtest-lib.sh)}" \
  "${APPROVED_HEX:?APPROVED_HEX not set (see tests/lib/systemtest-lib.sh)}" \
  "${CONTAINER_TAG:?CONTAINER_TAG not set (see tests/lib/systemtest-lib.sh)}" \
  "${STEPS:?STEPS not set (see tests/lib/systemtest-lib.sh)}"

die() { echo "ERROR: $*" >&2; exit 1; }

cleanup() {
    if [[ -n "${CONTAINER:-}" ]]; then
        docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
    fi
    if [[ -n "${WORK_DIR:-}" && -d "$WORK_DIR" ]]; then
        rm -rf "$WORK_DIR"
    fi
    if [[ -n "${ACK_FILE:-}" ]]; then
        rm -f "$ACK_FILE" 2>/dev/null || true
    fi
}

st_logs_and_die() {
    echo "Container logs:" >&2
    docker logs "$CONTAINER" >&2 2>&1 || true
    die "$*"
}

st_init() {
    IMAGE="${VIRTUALAVR_IMAGE:-pfichtner/virtualavr:latest}"
    BUILD_TIMEOUT="${BUILD_TIMEOUT:-180}"
    WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
    WS_URL="ws://localhost:$WS_PORT"
    CONTAINER="espressomachine-$CONTAINER_TAG-$$"
    WORK_DIR=$(mktemp -d)
    _STEP_COUNT=0
    trap cleanup EXIT INT TERM
}

st_step() {
    _STEP_COUNT=$((_STEP_COUNT + 1))
    echo "[$_STEP_COUNT/$STEPS] $*"
}

st_find_serial_device() {
    SERIAL_DEVICE=""
    for n in $(seq 0 63); do
        if [[ ! -e "/dev/ttyUSB$n" ]]; then
            SERIAL_DEVICE="/dev/ttyUSB$n"
            break
        fi
    done
    [[ -n "$SERIAL_DEVICE" ]] || die "no free /dev/ttyUSB slot available (0-63 all in use)"
}

st_find_hex() {
    HEX_FILE="${1:-}"
    if [[ -z "$HEX_FILE" ]]; then
        HEX_FILE="$WORK_DIR/$PROG_NAME.hex"
        st_step "Building $PROG_NAME.hex via EspressoMachine..."
        if ! command -v javac > /dev/null 2>&1 || ! command -v avr-ld > /dev/null 2>&1; then
            echo "    (AVR toolchain not installed; using approved hex tests/approved/$APPROVED_HEX)"
            cp "$REPO_ROOT/tests/approved/$APPROVED_HEX" "$HEX_FILE"
        else
            # Compile all examples (including runtime API stubs) via Maven
            mvn compile -q -f "$REPO_ROOT/examples/pom.xml"
            if (cd "$REPO_ROOT" && ./bin/espressomachine build \
                --cp "examples/$EXAMPLE_DIR/target/classes:runtime/api/target/classes" \
                "$ENTRY_CLASS" \
                --target atmega328p \
                --output "$WORK_DIR/build" > /dev/null 2>&1) && [[ -f "$WORK_DIR/build/$ENTRY_CLASS.hex" ]]; then
                cp "$WORK_DIR/build/$ENTRY_CLASS.hex" "$HEX_FILE"
            else
                echo "    (CLI build failed; using approved hex tests/approved/$APPROVED_HEX)"
                cp "$REPO_ROOT/tests/approved/$APPROVED_HEX" "$HEX_FILE"
            fi
        fi
    fi

    [[ -f "$HEX_FILE" ]] || die "HEX file not found: $HEX_FILE"
    echo "    Using HEX: $HEX_FILE"
}

st_start_container() {
    local extra=()
    if [[ "${ST_SERIAL:-0}" == "1" ]]; then
        extra+=(--volume /dev:/dev \
                -e "VIRTUALDEVICE=${SERIAL_DEVICE:?SERIAL_DEVICE not set}" \
                -e "BAUDRATE=${BAUD:?BAUD not set}" \
                -e "DEVICEUSER=$(id -u)" \
                -e "PAUSE_ON_START=${ST_PAUSE:-true}")
    fi

    st_step "Starting virtualavr container ($IMAGE)..."
    if [[ "${ST_SERIAL:-0}" == "1" ]]; then
        echo "      Serial device: $SERIAL_DEVICE @ ${BAUD} baud"
    fi

    docker create --name "$CONTAINER" \
        "${extra[@]}" \
        -p "$WS_PORT:8080" \
        -e "FILENAME=/$PROG_NAME.hex" \
        "$IMAGE" > /dev/null 2>&1 || st_logs_and_die "failed to create virtualavr container"
    docker cp "$HEX_FILE" "$CONTAINER:/$PROG_NAME.hex" > /dev/null || die "failed to copy HEX into container"
    docker start "$CONTAINER" > /dev/null 2>&1 || st_logs_and_die "failed to start virtualavr container"
}

st_wait_ws() {
    st_step "Waiting for WebSocket endpoint at $WS_URL ..."
    local deadline=$(( $(date +%s) + BUILD_TIMEOUT ))
    until timeout 2 websocat "$WS_URL" < /dev/null > /dev/null 2>&1; do
        if [[ $(date +%s) -gt $deadline ]]; then
            st_logs_and_die "Timed out waiting for WebSocket endpoint to become ready"
        fi
        sleep 1
    done
    echo "    WebSocket endpoint is ready."
}

st_wait_serial_device() {
    st_step "Waiting for serial device $SERIAL_DEVICE ..."
    local deadline=$(( $(date +%s) + BUILD_TIMEOUT ))
    until [[ -e "$SERIAL_DEVICE" ]]; do
        if [[ $(date +%s) -gt $deadline ]]; then
            st_logs_and_die "Timed out waiting for $SERIAL_DEVICE to appear"
        fi
        sleep 1
    done
    echo "    Device is ready."

    # Configure the PTY: raw mode, no echo, 8N1.
    stty -F "$SERIAL_DEVICE" "$BAUD" raw -echo cs8 -parenb -cstopb clocal
}

# ---------------------------------------------------------------------------
# WebSocket message builders — print one JSON line to stdout.
# An optional replyId argument appends a "replyId" field; callers that need
# ack-sequencing should pass $(uuidgen) so IDs are unique and collision-free.
# ---------------------------------------------------------------------------

ws_pin_subscribe() {    # ws_pin_subscribe <pin> [replyId]
    local pin=$1 extra=""
    [[ -n "${2:-}" ]] && extra=',"replyId":"'"$2"'"'
    printf '{"type":"pinMode","pin":"%s","mode":"digital"%s}\n' "$pin" "$extra"
}

ws_pin_inject() {       # ws_pin_inject <pin> <value> [replyId]
    local pin=$1 val=$2 extra=""
    [[ -n "${3:-}" ]] && extra=',"replyId":"'"$3"'"'
    printf '{"type":"pinState","pin":"%s","state":%s%s}\n' "$pin" "$val" "$extra"
}

ws_unpause_msg() {
    printf '{"type":"control","action":"unpause"}\n'
}

st_ws_unpause() {
    ws_unpause_msg | timeout 5 websocat "$WS_URL" > /dev/null 2>&1 || true
}

st_wait_ack() {             # st_wait_ack <id>
    until grep -q "$1" "$ACK_FILE" 2>/dev/null; do sleep 0.05; done
}

# Reads websocat output from stdin; prints count of HIGH<->LOW transitions for <pin>.
st_count_pin_toggles() {   # st_count_pin_toggles <pin>
    jq -rc --arg p "$1" \
        'try (select(.type=="pinState" and .pin==$p) | .state)' 2>/dev/null \
    | awk 'NR>1 && $0!=prev{c++} {prev=$0} END{print c+0}'
}
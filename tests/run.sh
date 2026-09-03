#!/bin/bash
# EspressoMachine approval-style test suite.
#
# Usage:
#   ./tests/run.sh            — run all tests, exit 1 if any fail
#   ./tests/run.sh --approve  — overwrite approved/ with fresh output (capture new goldens)
#   ./tests/run.sh <name>     — run a single test by name
#
# Tests compare generated .ll files (and Blink.hex) against approved/ snapshots.
# When a test fails, the diff is printed so you can see exactly what changed.

set -euo pipefail
cd "$(dirname "$0")/.."           # run from repo root

APPROVE=false
FILTER=""
for arg in "$@"; do
    [[ "$arg" == "--approve" ]] && APPROVE=true || FILTER="$arg"
done

TOOL_JAR="teavm-backend/llvm/target/espressomachine-teavm-ir-dumper-0.1.0-SNAPSHOT.jar"
TJ="java -jar $TOOL_JAR"
APPROVED_DIR="tests/approved"
ACTUAL_DIR="tests/actual"
API_CLASSES="runtime/api/target/classes"

mkdir -p "$APPROVED_DIR" "$ACTUAL_DIR"

PASS=0; FAIL=0; SKIP=0

# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

# Ensure the EspressoMachine JAR is built.
ensure_jar() {
    if [[ ! -f "$TOOL_JAR" ]]; then
        echo "[setup] Building EspressoMachine JAR..."
        (cd teavm-backend/llvm && mvn package -q)
    fi
}

# Compile all examples (and runtime API) via Maven once per run.
ensure_examples_compiled() {
    if [[ ! -d "$API_CLASSES" ]]; then
        mvn compile -q -f examples/pom.xml
    fi
}

# compare actual vs approved file; print diff on mismatch.
# Line endings are normalized (CRLF/CR -> LF) so the comparison is agnostic to
# whether avr-objcopy or a checkout produced \r\n vs \n.
check() {
    local name="$1" actual="$2" approved="$3"
    if $APPROVE; then
        cp "$actual" "$approved"
        echo "  APPROVED"
        return 0
    fi
    if diff -u <(tr -d '\r' < "$approved") <(tr -d '\r' < "$actual") > /dev/null 2>&1; then
        echo "  PASS"
        PASS=$((PASS + 1))
        return 0
    else
        echo "  FAIL"
        diff -u <(tr -d '\r' < "$approved") <(tr -d '\r' < "$actual") | head -60
        FAIL=$((FAIL + 1))
        return 1
    fi
}

# Run an emit-llvm test: generate .ll via CLI and compare against golden.
run_ll_test() {
    local name="$1" classpath="$2" entry="$3"
    [[ -n "$FILTER" && "$name" != "$FILTER" ]] && { SKIP=$((SKIP + 1)); return 0; }
    echo "[$name]"
    local actual="$ACTUAL_DIR/${name}.ll"
    local approved="$APPROVED_DIR/${name}.ll"
    $TJ emit-llvm --cp "$classpath" "$entry" -o "$actual" > /dev/null 2>&1 || {
        echo "  ERROR: emit-llvm failed"
        FAIL=$((FAIL + 1))
        return 1
    }
    check "$name" "$actual" "$approved"
}

# Run a full build test: generate HEX via CLI build and compare against golden.
run_hex_test() {
    local name="$1" classpath="$2" entry="$3" approved_hex="$4"
    [[ -n "$FILTER" && "$name" != "$FILTER" ]] && { SKIP=$((SKIP + 1)); return 0; }
    echo "[$name]"
    local build_dir="$ACTUAL_DIR/${name}-build"
    $TJ build --cp "$classpath" "$entry" --target atmega328p --output "$build_dir" \
        > /dev/null 2>&1 || {
        echo "  ERROR: build failed"
        FAIL=$((FAIL + 1))
        return 1
    }
    local actual_hex="$build_dir/${entry}.hex"
    if [[ -f "$actual_hex" ]]; then
        check "$name" "$actual_hex" "$approved_hex"
    else
        echo "  ERROR: ${entry}.hex not produced"
        FAIL=$((FAIL + 1))
    fi
}

# ------------------------------------------------------------------
# Test cases
# ------------------------------------------------------------------

ensure_jar
ensure_examples_compiled

# Phase 1: arithmetic
run_ll_test "add" \
    "examples/add/target/classes" \
    "Add"

# Phase 1: control flow — if/else, while loop, PHI node
run_ll_test "controlflow" \
    "examples/controlflow/target/classes" \
    "ControlFlow"

# Phase 2: object model — struct type, getelementptr, instance methods
run_ll_test "counter" \
    "examples/objects/target/classes" \
    "Counter"

# Phase 3: memory model — stack alloca, static global, escape error
run_ll_test "memory" \
    "examples/memory/target/classes" \
    "MemoryTest"

# Phase 4: AVR intrinsics — GPIO MMIO inlining, Delay call
run_ll_test "blink" \
    "examples/blink/target/classes:$API_CLASSES" \
    "Blink"

# Phase 4b: Delay.time — TimeUnit fold to __espressomachine_delay_ms
run_ll_test "delay-time" \
    "examples/delay-time/target/classes:$API_CLASSES" \
    "DelayTime"

# Phase 5: full pipeline — Java → HEX via ATmega328P target
run_hex_test "blink-hex" \
    "examples/blink/target/classes:$API_CLASSES" \
    "Blink" \
    "$APPROVED_DIR/blink.hex"

# Phase 7: OOP Blink — Led class with pin field, on()/off() instance methods
run_ll_test "oop-blink" \
    "examples/oop-blink/target/classes:$API_CLASSES" \
    "OopBlink"

run_hex_test "oop-blink-hex" \
    "examples/oop-blink/target/classes:$API_CLASSES" \
    "OopBlink" \
    "$APPROVED_DIR/oop-blink.hex"

# Enum support: Direction (ordinal, ==, if-else), Pin (custom field)
run_ll_test "enum" \
    "examples/enum/target/classes" \
    "EnumTest"

# Serial: USART0 init inlined, write calls via runtime, println inlined
run_ll_test "serial" \
    "examples/serial/target/classes:$API_CLASSES" \
    "HelloSerial"

run_hex_test "serial-hex" \
    "examples/serial/target/classes:$API_CLASSES" \
    "HelloSerial" \
    "$APPROVED_DIR/serial.hex"

# Echo: available() inlined as RXC0 bit-check, read() inlined as UDR0 load
run_ll_test "echo" \
    "examples/echo/target/classes:$API_CLASSES" \
    "Echo"

run_hex_test "echo-hex" \
    "examples/echo/target/classes:$API_CLASSES" \
    "Echo" \
    "$APPROVED_DIR/echo.hex"

# Arduino-style entry: no main(); setup()/loop() wrapper synthesized
run_ll_test "setup-loop" \
    "examples/setup-loop/target/classes" \
    "ArduinoBlink"

run_hex_test "setup-loop-hex" \
    "examples/setup-loop/target/classes" \
    "ArduinoBlink" \
    "$APPROVED_DIR/setup-loop.hex"

# ------------------------------------------------------------------
# System integration test (requires Docker + Node.js + AVR toolchain)
# ------------------------------------------------------------------
# Run the transpiled Java blink program on virtualavr and verify the LED
# blinks by watching pin-state messages over WebSocket.
if [[ -n "${RUN_INTEGRATION_TESTS:-}" || "$FILTER" == "systemtest-blink" ]]; then
    if [[ "$FILTER" == "systemtest-blink" || -z "$FILTER" ]]; then
        echo "[systemtest-blink]"
        if bash tests/systemtest-blink.sh; then
            echo "  PASS"
            PASS=$((PASS + 1))
        else
            echo "  FAIL"
            FAIL=$((FAIL + 1))
        fi
    fi
fi

# Run the Echo program on virtualavr: send bytes to the PTY and verify the AVR
# echoes them back, exercising Serial.available() and Serial.read().
if [[ -n "${RUN_INTEGRATION_TESTS:-}" || "$FILTER" == "systemtest-echo" ]]; then
    if [[ "$FILTER" == "systemtest-echo" || -z "$FILTER" ]]; then
        echo "[systemtest-echo]"
        if bash tests/systemtest-echo.sh; then
            echo "  PASS"
            PASS=$((PASS + 1))
        else
            echo "  FAIL"
            FAIL=$((FAIL + 1))
        fi
    fi
fi

# Run the transpiled Java serial program on virtualavr and verify 'A' (0x41)
# is transmitted over the virtual USART via the WebSocket serialDebug interface.
if [[ -n "${RUN_INTEGRATION_TESTS:-}" || "$FILTER" == "systemtest-serial" ]]; then
    if [[ "$FILTER" == "systemtest-serial" || -z "$FILTER" ]]; then
        echo "[systemtest-serial]"
        if bash tests/systemtest-serial.sh; then
            echo "  PASS"
            PASS=$((PASS + 1))
        else
            echo "  FAIL"
            FAIL=$((FAIL + 1))
        fi
    fi
fi

# ------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------
echo
if $APPROVE; then
    echo "Goldens approved. Commit tests/approved/ to lock them in."
else
    total=$((PASS + FAIL))
    echo "$PASS/$total passed${SKIP:+, $SKIP skipped}."
    [[ $FAIL -eq 0 ]]   # exit 1 on any failure
fi

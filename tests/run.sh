#!/bin/bash
# TinyJava approval-style test suite.
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

TOOL_JAR="teavm-backend/llvm/target/teavm-ir-dumper-0.1.0-SNAPSHOT.jar"
TJ="java -jar $TOOL_JAR"
APPROVED_DIR="tests/approved"
ACTUAL_DIR="tests/actual"
API_CLASSES="$ACTUAL_DIR/api_classes"

mkdir -p "$APPROVED_DIR" "$ACTUAL_DIR"

PASS=0; FAIL=0; SKIP=0

# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

# Ensure the TinyJava JAR is built.
ensure_jar() {
    if [[ ! -f "$TOOL_JAR" ]]; then
        echo "[setup] Building TinyJava JAR..."
        (cd teavm-backend/llvm && mvn package -q)
    fi
}

# Compile runtime/api stubs (GPIO.java, Delay.java) once per run.
ensure_api_classes() {
    if [[ ! -d "$API_CLASSES" ]]; then
        mkdir -p "$API_CLASSES"
        javac runtime/api/*.java -d "$API_CLASSES"
    fi
}

# compare actual vs approved file; print diff on mismatch.
check() {
    local name="$1" actual="$2" approved="$3"
    if $APPROVE; then
        cp "$actual" "$approved"
        echo "  APPROVED"
        return 0
    fi
    if diff -u "$approved" "$actual" > /dev/null 2>&1; then
        echo "  PASS"
        PASS=$((PASS + 1))
        return 0
    else
        echo "  FAIL"
        diff -u "$approved" "$actual" | head -60
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
ensure_api_classes

# Phase 1: arithmetic
run_ll_test "add" \
    "examples/add/classes" \
    "Add"

# Phase 1: control flow — if/else, while loop, PHI node
run_ll_test "controlflow" \
    "examples/controlflow/classes" \
    "ControlFlow"

# Phase 2: object model — struct type, getelementptr, instance methods
run_ll_test "counter" \
    "examples/objects/classes" \
    "Counter"

# Phase 3: memory model — stack alloca, static global, escape error
run_ll_test "memory" \
    "examples/memory/classes" \
    "MemoryTest"

# Phase 4: AVR intrinsics — GPIO MMIO inlining, Delay call
run_ll_test "blink" \
    "examples/blink/classes:$API_CLASSES" \
    "Blink"

# Phase 4b: Delay.time — TimeUnit fold to __tinyjava_delay_ms
run_ll_test "delay-time" \
    "examples/delay-time/classes:$API_CLASSES" \
    "DelayTime"

# Phase 5: full pipeline — Java → HEX via ATmega328P target
run_hex_test "blink-hex" \
    "examples/blink/classes:$API_CLASSES" \
    "Blink" \
    "$APPROVED_DIR/blink.hex"

# Phase 7: OOP Blink — Led class with pin field, on()/off() instance methods
run_ll_test "oop-blink" \
    "examples/oop-blink/classes:$API_CLASSES" \
    "OopBlink"

run_hex_test "oop-blink-hex" \
    "examples/oop-blink/classes" \
    "OopBlink" \
    "$APPROVED_DIR/oop-blink.hex"

# Enum support: Direction (ordinal, ==, if-else), Pin (custom field)
run_ll_test "enum" \
    "examples/enum/classes" \
    "EnumTest"

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

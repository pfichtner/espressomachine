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
APPROVED_DIR="tests/approved"
ACTUAL_DIR="tests/actual"
API_CLASSES="$ACTUAL_DIR/api_classes"

mkdir -p "$APPROVED_DIR" "$ACTUAL_DIR"

PASS=0; FAIL=0; SKIP=0

# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

# Ensure the IrDumper JAR is built.
ensure_jar() {
    if [[ ! -f "$TOOL_JAR" ]]; then
        echo "[setup] Building teavm-ir-dumper JAR..."
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

# Run an IrDumper test: generate .ll and compare.
run_ll_test() {
    local name="$1" classpath="$2" entry="$3"
    [[ -n "$FILTER" && "$name" != "$FILTER" ]] && { SKIP=$((SKIP + 1)); return 0; }
    echo "[$name]"
    local actual="$ACTUAL_DIR/${name}.ll"
    local approved="$APPROVED_DIR/${name}.ll"
    # Suppress the verbose stdout IR dump; only the .ll file is the artifact.
    java -jar "$TOOL_JAR" "$classpath" "$entry" "$actual" > /dev/null 2>&1 || {
        echo "  ERROR: IrDumper failed"
        FAIL=$((FAIL + 1))
        return 1
    }
    check "$name" "$actual" "$approved"
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

# Phase 5: full pipeline — Java → HEX via ATmega328P target
if [[ -z "$FILTER" || "$FILTER" == "blink-hex" ]]; then
    echo "[blink-hex]"
    BLINK_BUILD="examples/blink/build"
    bash targets/atmega328p/build.sh \
        "examples/blink/classes:$API_CLASSES" Blink \
        > /dev/null 2>&1 || {
        echo "  ERROR: build.sh failed"
        FAIL=$((FAIL + 1))
    }
    actual_hex="$BLINK_BUILD/Blink.hex"
    approved_hex="$APPROVED_DIR/blink.hex"
    if [[ -f "$actual_hex" ]]; then
        check "blink-hex" "$actual_hex" "$approved_hex"
    else
        echo "  ERROR: Blink.hex not produced"
        FAIL=$((FAIL + 1))
    fi
fi

# Phase 7: OOP Blink — Led class with pin field, on()/off() instance methods
# Proves constructor, field store/load, instance calls, and the key PRD acceptance:
# TeaVM inlines Led entirely so GPIO pin 13 remains a compile-time constant.
run_ll_test "oop-blink" \
    "examples/oop-blink/classes" \
    "OopBlink"

if [[ -z "$FILTER" || "$FILTER" == "oop-blink-hex" ]]; then
    echo "[oop-blink-hex]"
    BLINK_BUILD="examples/blink/build"
    bash targets/atmega328p/build.sh \
        "examples/oop-blink/classes" OopBlink \
        > /dev/null 2>&1 || {
        echo "  ERROR: build.sh failed (oop-blink-hex)"
        FAIL=$((FAIL + 1))
    }
    actual_hex="$BLINK_BUILD/OopBlink.hex"
    approved_hex="$APPROVED_DIR/oop-blink.hex"
    if [[ -f "$actual_hex" ]]; then
        check "oop-blink-hex" "$actual_hex" "$approved_hex"
    else
        echo "  ERROR: OopBlink.hex not produced"
        FAIL=$((FAIL + 1))
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

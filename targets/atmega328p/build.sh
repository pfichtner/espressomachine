#!/bin/bash
# EspressoMachine ATmega328P build script
#
# Usage:
#   ./build.sh <EntryClass.java ...>   — compile from Java source
#   ./build.sh <classpath-dir> <EntryClass>  — use pre-compiled .class files
#
# Outputs: build/Blink.{ll,elf,hex}
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RUNTIME_API="$REPO_ROOT/runtime/api"
TARGET_DIR="$REPO_ROOT/runtime/avr/atmega328p"

# Load target descriptor (MCU, F_CPU, DELAY_ITERS, TRIPLE)
# shellcheck source=/dev/null
source "$TARGET_DIR/target.sh"

# ---- Parse arguments ----
if [[ $# -eq 0 ]]; then
    echo "Usage: build.sh <File.java ...>  OR  build.sh <classdir> <EntryClass>"
    exit 1
fi

if [[ "$1" == *.java ]]; then
    # Source mode: compile Java files then run TeaVM
    JAVA_SOURCES=("$@")
    ENTRY_CLASS="${JAVA_SOURCES[0]}"
    ENTRY_CLASS="$(basename "${ENTRY_CLASS%.java}")"
    CLASSES_DIR="$REPO_ROOT/examples/blink/build/classes"
    mkdir -p "$CLASSES_DIR"
    BUILD_DIR="$REPO_ROOT/examples/blink/build"
else
    # Pre-compiled mode: classdir + entry class name
    CLASSES_DIR="$1"
    ENTRY_CLASS="$2"
    BUILD_DIR="$REPO_ROOT/examples/blink/build"
fi

mkdir -p "$BUILD_DIR"
OUTPUT_LL="$BUILD_DIR/${ENTRY_CLASS}.ll"

echo "=== EspressoMachine ATmega328P build ==="
echo "Target: $MCU @ $(( F_CPU / 1000000 )) MHz  (DELAY_ITERS=$DELAY_ITERS)"
echo "Entry:  $ENTRY_CLASS"
echo "Output: $BUILD_DIR/${ENTRY_CLASS}.{elf,hex}"
echo

# ---- Step 0: compile Java sources (source mode only) ----
if [[ "$1" == *.java ]]; then
    echo "[0/6] Compiling Java sources..."
    javac -encoding UTF-8 -cp "$RUNTIME_API" "${JAVA_SOURCES[@]}" -d "$CLASSES_DIR"
    CLASSES_DIR="$CLASSES_DIR"
fi

# ---- Step 1: TeaVM → LLVM IR ----
TOOL_JAR="$REPO_ROOT/teavm-backend/llvm/target/teavm-ir-dumper-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$TOOL_JAR" ]]; then
    echo "Building TeaVM backend..."
    (cd "$REPO_ROOT/teavm-backend/llvm" && mvn package -q)
fi

echo "[1/8] TeaVM → LLVM IR..."
# Add runtime/api to classpath so TeaVM can resolve GPIO/Delay class definitions
TEAVM_CP="$CLASSES_DIR:$RUNTIME_API"
# Compile runtime/api stubs to classes if not already done
API_CLASSES="$BUILD_DIR/api_classes"
if [[ ! -d "$API_CLASSES" ]]; then
    mkdir -p "$API_CLASSES"
    javac -encoding UTF-8 "$RUNTIME_API"/*.java -d "$API_CLASSES"
fi
TEAVM_CP="$CLASSES_DIR:$API_CLASSES"

java -jar "$TOOL_JAR" "$TEAVM_CP" "$ENTRY_CLASS" "$OUTPUT_LL"

# ---- Step 2: assemble startup.S (substitute entry class name) ----
echo "[2/8] Assembling startup.S (entry: ${ENTRY_CLASS}_main)..."
CALIBRATED_STARTUP="$BUILD_DIR/startup_${ENTRY_CLASS}.S"
sed "s/__ENTRY_CLASS__/${ENTRY_CLASS}/g" "$SCRIPT_DIR/startup.S" > "$CALIBRATED_STARTUP"
avr-as -mmcu=$MCU "$CALIBRATED_STARTUP" -o "$BUILD_DIR/startup.o"

# ---- Step 3: compile user LLVM IR ----
echo "[3/8] Compiling ${ENTRY_CLASS}.ll → ${ENTRY_CLASS}.o ..."
llc-18 -march=avr -mcpu=$MCU -filetype=obj \
    -o "$BUILD_DIR/${ENTRY_CLASS}.o" \
    "$OUTPUT_LL"

# ---- Step 4: compile gpio.ll runtime ----
echo "[4/8] Compiling gpio.ll → gpio.o ..."
llc-18 -march=avr -mcpu=$MCU -filetype=obj \
    -o "$BUILD_DIR/gpio.o" \
    "$TARGET_DIR/gpio.ll"

# ---- Step 5: generate and compile calibrated delay.ll ----
echo "[5/8] Generating delay.ll (DELAY_ITERS=$DELAY_ITERS) → delay.o ..."
CALIBRATED_DELAY="$BUILD_DIR/delay_${DELAY_ITERS}.ll"
sed "s/__DELAY_ITERS__/$DELAY_ITERS/g" "$TARGET_DIR/delay.ll" > "$CALIBRATED_DELAY"
llc-18 -march=avr -mcpu=$MCU -filetype=obj \
    -o "$BUILD_DIR/delay.o" \
    "$CALIBRATED_DELAY"

# ---- Step 6: compile random.ll runtime ----
echo "[6/8] Compiling random.ll → random.o ..."
llc-18 -march=avr -mcpu=$MCU -filetype=obj \
    -o "$BUILD_DIR/random.o" \
    "$TARGET_DIR/random.ll"

# ---- Step 6b: compile random.S (java.util.Random 48-bit LCG) ----
echo "[6b/8] Assembling random.S → random_asm.o ..."
avr-as -mmcu=$MCU "$TARGET_DIR/random.S" -o "$BUILD_DIR/random_asm.o"

# ---- Step 6c: compile time.ll if millis() is used ----
TIME_OBJ=""
if grep -q "@__espressomachine_time_millis" "$OUTPUT_LL" 2>/dev/null; then
    echo "[6c/8] Compiling time.ll → time.o ..."
    llc-18 -march=avr -mcpu=$MCU -filetype=obj \
        -o "$BUILD_DIR/time.o" \
        "$TARGET_DIR/time.ll"
    TIME_OBJ="$BUILD_DIR/time.o"
fi

# ---- Step 7: link ----
echo "[7/8] Linking → ${ENTRY_CLASS}.elf ..."
avr-ld -T "$SCRIPT_DIR/linker.ld" \
    "$BUILD_DIR/startup.o" \
    "$BUILD_DIR/${ENTRY_CLASS}.o" \
    "$BUILD_DIR/gpio.o" \
    "$BUILD_DIR/delay.o" \
    "$BUILD_DIR/random.o" \
    "$BUILD_DIR/random_asm.o" \
    ${TIME_OBJ:+"$TIME_OBJ"} \
    -o "$BUILD_DIR/${ENTRY_CLASS}.elf"

avr-objcopy -O ihex -R .eeprom \
    "$BUILD_DIR/${ENTRY_CLASS}.elf" \
    "$BUILD_DIR/${ENTRY_CLASS}.hex"

echo
echo "=== Build complete ==="
avr-size "$BUILD_DIR/${ENTRY_CLASS}.elf"
echo
echo "Flash image: $BUILD_DIR/${ENTRY_CLASS}.hex"

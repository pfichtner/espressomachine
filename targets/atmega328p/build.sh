#!/bin/bash
# TinyJava ATmega328P build script
# Usage: ./build.sh <blink.ll>
# Produces: build/Blink.elf  build/Blink.hex
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RUNTIME="$REPO_ROOT/runtime/avr/atmega328p"
TARGETS="$REPO_ROOT/targets/atmega328p"

INPUT_LL="${1:-$REPO_ROOT/examples/blink/build/Blink.ll}"
BUILD_DIR="$REPO_ROOT/examples/blink/build"
mkdir -p "$BUILD_DIR"

AVR_TARGET="avr"
MCU="atmega328p"
# LLVM/Clang AVR triple and mcpu flag
TRIPLE="${AVR_TARGET}-unknown-unknown"
MCPU="atmega328p"

echo "=== TinyJava ATmega328P build ==="
echo "Input:  $INPUT_LL"
echo "Output: $BUILD_DIR/Blink.{elf,hex}"
echo

# 1. Assemble startup.S with avr-as
echo "[1/5] Assembling startup.S..."
avr-as -mmcu=$MCU "$TARGETS/startup.S" -o "$BUILD_DIR/startup.o"

# 2. Compile Blink LLVM IR to AVR object
echo "[2/5] Compiling Blink.ll → Blink.o ..."
llc-18 -march=avr -mcpu=$MCPU -filetype=obj \
    -o "$BUILD_DIR/Blink.o" \
    "$INPUT_LL"

# 3. Compile gpio.ll runtime
echo "[3/5] Compiling gpio.ll → gpio.o ..."
llc-18 -march=avr -mcpu=$MCPU -filetype=obj \
    -o "$BUILD_DIR/gpio.o" \
    "$RUNTIME/gpio.ll"

# 4. Compile delay.ll runtime
echo "[4/5] Compiling delay.ll → delay.o ..."
llc-18 -march=avr -mcpu=$MCPU -filetype=obj \
    -o "$BUILD_DIR/delay.o" \
    "$RUNTIME/delay.ll"

# 5. Link
echo "[5/5] Linking → Blink.elf ..."
avr-ld -T "$TARGETS/linker.ld" \
    "$BUILD_DIR/startup.o" \
    "$BUILD_DIR/Blink.o" \
    "$BUILD_DIR/gpio.o" \
    "$BUILD_DIR/delay.o" \
    -o "$BUILD_DIR/Blink.elf"

# 6. Convert to Intel HEX
echo "[6/5] Converting → Blink.hex ..."
avr-objcopy -O ihex -R .eeprom \
    "$BUILD_DIR/Blink.elf" \
    "$BUILD_DIR/Blink.hex"

echo
echo "=== Build complete ==="
avr-size "$BUILD_DIR/Blink.elf"
echo
echo "Flash image: $BUILD_DIR/Blink.hex"

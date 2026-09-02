# ByteLight

Write Java. Flash machine code.

Compiles JVM bytecode produced by standard `javac` into native code for microcontrollers — no JVM, no interpreter, no managed runtime on the MCU.

Initial target: **ATmega328P** (Arduino Uno, 16 MHz, 32 KB flash).

```
class Blink {
    static void main() {
        GPIO.pinMode(13, GPIO.OUTPUT);
        while (true) {
            GPIO.digitalWrite(13, GPIO.HIGH);
            Delay.ms(500);
            GPIO.digitalWrite(13, GPIO.LOW);
            Delay.ms(500);
        }
    }
}
```

```
bytelight build Blink.class --target atmega328p
```

→ `build/Blink.hex` — **332 bytes** of AVR machine code, ready to flash.

## Architecture

```
Java source
    │
    ▼ javac
.class / .jar
    │
    ▼ TeaVM (frontend)
    │  · bytecode parsing
    │  · dependency analysis
    │  · devirtualization
    │  · SSA optimisation
    │
    ▼ ByteLight backend
    │  · LLVM IR generation
    │  · escape analysis (stack vs. static vs. heap error)
    │  · AVR intrinsic lowering (GPIO, Delay → MMIO)
    │
    ▼ LLVM AVR backend (llc)
    │
    ▼ avr-ld + avr-objcopy
    │
    ▼ ATmega328P .hex
```

[TeaVM](https://teavm.org) handles the hard frontend work (class loading, points-to analysis, SSA construction, inlining). ByteLight is a pure backend/profile that targets embedded MCUs.

## Features

- **Zero JVM overhead** — object abstractions are absorbed at compile time. The `Led` class from the OOP Blink example adds zero bytes over the equivalent imperative code.
- **Escape analysis** — non-escaping objects stack-allocated (`alloca`); static objects become LLVM globals; heap-escaping allocations produce a compile-time error.
- **Compile-time GPIO inlining** — `GPIO.digitalWrite(13, HIGH)` with a constant pin number compiles to a single AVR `sbi` instruction.
- **Approval-style test suite** — golden `.ll` and `.hex` files catch regressions across all phases.

## Completed phases

| Phase | What |
|-------|------|
| 0 | TeaVM feasibility — IR enumerable, backend interface identified |
| 1 | LLVM IR emitter — arithmetic, if/else, while loops, PHI nodes |
| 2 | Java objects — LLVM struct types, `getelementptr`, instance methods |
| 3 | Memory model — escape analysis, static globals, heap compile error |
| 4 | Embedded intrinsics — GPIO / Delay API, PORTB/DDRB MMIO inlining |
| 5 | ATmega328P target — startup, linker, `avr-ld`, produces flashable HEX |
| 6 | First Blink — `Blink.hex` 332 bytes, `sbi`/`cbi` verified by disassembly |
| 7 | OOP Blink — `Led` class with constructor, fields, instance methods; 328 bytes |
| CLI | `bytelight build / inspect / emit-llvm / flash` |

## Requirements

| Tool | Version |
|------|---------|
| Java (JDK) | 17+ |
| Maven | 3.8+ |
| LLVM | 18 (`llc-18`, `llvm-as`) |
| AVR binutils | `avr-as`, `avr-ld`, `avr-objcopy`, `avr-size` |
| avrdude | for `bytelight flash` |

Install on Ubuntu/Debian:

```bash
sudo apt-get install openjdk-17-jdk maven llvm avr-libc binutils-avr avrdude
```

## Quick start

```bash
# Build the compiler
cd teavm-backend/llvm && mvn package -q && cd ../..

# Add bin/ to PATH
export PATH="$PWD/bin:$PATH"

# Compile and inspect IR
bytelight emit-llvm examples/blink/classes Blink -o Blink.ll

# Full pipeline → HEX
bytelight build examples/blink/classes Blink --target atmega328p
avr-size build/Blink.elf

# Flash (replace /dev/ttyUSB0 with your port)
bytelight flash build/Blink.hex --port /dev/ttyUSB0
```

## CLI reference

```
bytelight build     [--target <mcu>] [--cp <dirs>] [--output <dir>] <Foo.class|dir> [Name]
bytelight inspect   [--cp <dirs>]                                   <Foo.class|dir> [Name]
bytelight emit-llvm [--cp <dirs>] [-o <out.ll>]                    <Foo.class|dir> [Name]
bytelight flash     --port <dev>                                    <Foo.hex>
```

Input forms:
- `Blink.class` — classpath = parent directory, entry class = `Blink`
- `examples/blink/classes Blink` — explicit classpath directory and class name
- `--cp dir1:dir2 ClassName` — colon-separated extra classpath entries

Default output directory: `build/` relative to the working directory.

## GPIO and Delay API

The API lives in package `bytelight.api`. Compile the stubs once, then reference them when compiling user code:

```bash
javac runtime/api/*.java -d api-classes/
javac -cp api-classes MyProgram.java -d classes/
```

Or compile everything in one pass:

```bash
javac runtime/api/*.java MyProgram.java -d classes/
```

```java
import bytelight.api.*;

// GPIO.java — target-agnostic stub, lowered to AVR MMIO by the backend
GPIO.pinMode(13, GPIO.OUTPUT);
GPIO.digitalWrite(13, GPIO.HIGH);
GPIO.digitalWrite(13, GPIO.LOW);

// Delay.java
Delay.ms(500);
```

Calls with compile-time-constant pin numbers are inlined directly to AVR `sbi`/`cbi` instructions. Non-constant pins fall back to a runtime pin-table lookup in `runtime/avr/atmega328p/gpio.ll`.

## Memory model

| Allocation | Java pattern | LLVM | Status |
|---|---|---|---|
| Stack | `Counter c = new Counter()` (local, non-escaping) | `alloca %Counter_t` | ✓ |
| Static | `static Counter c = new Counter()` | `global %Counter_t zeroinitializer` | ✓ |
| Heap | `return new Counter()` (escapes) | compile error | intentional |

Heap/GC is explicitly out of scope for the initial release.

## Adding a new target

1. Create `runtime/avr/<mcu>/target.sh`:
   ```bash
   MCU=atmega2560
   F_CPU=16000000
   DELAY_ITERS=4000   # F_CPU / 4 / 1000
   ```
2. Provide `gpio.ll` and `delay.ll` (or symlink if registers are identical).
3. Create `targets/<mcu>/startup.S`, `linker.ld`.
4. Run: `bytelight build MyClass --target <mcu>`

## Project layout

```
bin/bytelight                  CLI launcher
teavm-backend/llvm/           Java compiler (Maven project)
  src/main/java/bytelight/
    cli/ByteLightCli.java      CLI entry point & subcommand dispatch
    cli/Pipeline.java         AVR toolchain orchestration
    IrDumper.java             TeaVM driver (also legacy entry point)
    LlvmModuleEmitter.java    LLVM IR module writer
    LlvmMethodEmitter.java    Per-method SSA → LLVM translation
    EscapeAnalyzer.java       Intra-procedural escape analysis
    AvrIntrinsics.java        GPIO/Delay → AVR MMIO lowering
runtime/
  api/                        Target-agnostic Java stubs (GPIO.java, Delay.java)
  avr/atmega328p/             ATmega328P runtime implementation
    target.sh                 MCU descriptor (F_CPU, DELAY_ITERS)
    gpio.ll                   Runtime GPIO fallback (non-constant pins)
    delay.ll                  Busy-wait delay loop template
targets/atmega328p/           Linker script, startup assembly, build script
examples/                     Demonstration programs
tests/
  run.sh                      Approval-style test runner
  systemtest-blink.sh         End-to-end blink test on virtualavr (requires Docker)
  ws-monitor.mjs              Node.js WebSocket pin monitor used by the systemtest
  approved/                   Golden .ll and .hex snapshots
```

## Tests

```bash
bash tests/run.sh             # run all 8 tests (exit 1 on any failure)
bash tests/run.sh <name>      # run a single test (add, controlflow, counter, memory, blink, blink-hex, oop-blink, oop-blink-hex)
bash tests/run.sh --approve   # overwrite golden files after an intentional change
```

### System integration test (virtualavr)

`tests/systemtest-blink.sh` verifies the transpiled Java blink program really
blinks by running the generated `.hex` inside [virtualavr](https://github.com/pfichtner/virtualavr),
an AVR simulator running in Docker. It enables digital reporting on pin 13 over
the simulator's WebSocket endpoint and asserts the pin toggles at least
`BLINK_TOGGLES` (default 4) times, proving the on/off LED cycle runs correctly
on simulated hardware.

```bash
# Requires: Docker + Node.js (with the `ws` npm package installed)
bash tests/systemtest-blink.sh                # builds Blink.hex, then runs it on virtualavr
bash tests/systemtest-blink.sh tests/approved/blink.hex   # use a pre-built .hex
```

The systemtest needs Docker, so it is not part of the default `tests/run.sh`
run. Run it explicitly:

```bash
bash tests/run.sh systemtest-blink             # systemtest only
RUN_INTEGRATION_TESTS=1 bash tests/run.sh      # approval tests + systemtest
```

Configuration is via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `VIRTUALAVR_IMAGE` | `pfichtner/virtualavr:latest` | Docker image to run |
| `BLINK_PIN` | `13` | Pin to watch for blinking |
| `BLINK_TIMEOUT` | `30` | Seconds to observe before failing |
| `BLINK_TOGGLES` | `4` | Minimum on/off toggle count to accept |
| `BUILD_TIMEOUT` | `180` | Seconds to wait for the container to start |

## Research notes

`teavm-backend/llvm/FINDINGS.md` documents the Phase 0 feasibility study: which TeaVM classes represent the IR, how the compilation pipeline is invoked programmatically, and the complete proposed mapping from TeaVM IR instructions to LLVM IR.

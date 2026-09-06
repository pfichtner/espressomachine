<img align="left" src="https://pfichtner.github.io/assets/espressomachine/espressomachine.jpg" alt="EspressoMachine logo" width="120">

# EspressoMachine
<br clear="left">

Java bytecode, distilled for microcontrollers.

Write JVM code. Flash machine code.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net)
[![GitHub Stars](https://img.shields.io/github/stars/pfichtner/espressomachine?style=social)](https://github.com/pfichtner/espressomachine)

Compiles JVM bytecode (`.class` / `.jar`) into native code for microcontrollers — no JVM, no interpreter, no managed runtime on the MCU.

Initial target: **ATmega328P** (Arduino Uno, 16 MHz, 32 KB flash).

```
class Blink {
    public static void main(String[] args) {
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
espressomachine build Blink.class --target atmega328p
```

→ `build/Blink.hex` — **272 bytes** of AVR machine code, ready to flash.

### Entry points

Two styles of entry are supported:

- **`public static void main(String[] args)`** — the default. Called once from the reset vector, so it is expected to drive any repeated work itself (e.g. the `while (true)` loop above).
- **`static void setup()` / `static void loop()`** — the Arduino style. If the entry class has no `main()`, EspressoMachine synthesizes a `ClassName_main` wrapper that calls `setup()` once and then calls `loop()` in an endless loop. A real `main()` always wins over `setup()`/`loop()`.

```java
class Blink {
    static void setup() {
        GPIO.pinMode(13, GPIO.OUTPUT);
    }
    static void loop() {
        GPIO.digitalWrite(13, GPIO.HIGH);
        Delay.ms(500);
        GPIO.digitalWrite(13, GPIO.LOW);
        Delay.ms(500);
    }
}
```

Either `setup()` or `loop()` may be omitted — a `loop()`-only sketch runs `loop()` forever with no setup, and a `setup()`-only sketch calls `setup()` once and then halts.

## Supported languages

EspressoMachine compiles JVM **bytecode**, not source, so any language that targets the JVM can in
principle feed it — as long as the code stays within EspressoMachine's constraints (no heap/GC, no
exceptions, no `invokedynamic`). In practice that means idiomatic Kotlin/Scala/Clojure will
largely fall outside the supported subset today; Java remains the primary, fully-tested path.

| Language | Compiler | Status |
|----------|----------|--------|
| Java | `javac` | Primary — all examples and tests |
| Kotlin | `kotlinc` | Supported (emits standard `.class` files) |
| Scala | `scalac` | Possible, but the heavy stdlib tends to exceed MCU flash |
| Clojure | `clojure` | Partial — needs `invokedynamic`-free codegen |

## Architecture

```
Java / Kotlin / Scala / Clojure source
    │
    ▼ javac / kotlinc / scalac / …
.class / .jar
    │
    ▼ TeaVM (frontend)
    │  · bytecode parsing
    │  · dependency analysis
    │  · devirtualization
    │  · SSA optimisation
    │
    ▼ EspressoMachine backend
    │  · LLVM IR generation
    │  · escape analysis (stack vs. static vs. heap error)
    │  · AVR intrinsic lowering (GPIO, Delay, Serial, Time → MMIO / ISR)
    │
    ▼ LLVM AVR backend (llc -function-sections -data-sections)
    │
    ▼ avr-ld --gc-sections + avr-objcopy
    │
    ▼ ATmega328P .hex
```

[TeaVM](https://teavm.org) handles the hard frontend work (class loading, points-to analysis, SSA construction, inlining). EspressoMachine is a pure backend/profile that targets embedded MCUs.

## Features

- **Zero JVM overhead** — object abstractions are absorbed at compile time. The `Led` class from the OOP Blink example adds zero bytes over the equivalent imperative code.
- **Escape analysis** — non-escaping objects stack-allocated (`alloca`); static objects become LLVM globals; heap-escaping allocations produce a compile-time error.
- **Compile-time GPIO inlining** — `GPIO.digitalWrite(13, HIGH)` with a constant pin number compiles to a single `sbi`/`cbi` instruction; `GPIO.digitalRead(2)` with a constant pin inlines to a `PIND` load and bit-test.
- **Dead-code elimination** — each runtime function lands in its own ELF section (`-function-sections`); `avr-ld --gc-sections` drops any section not reachable from the interrupt vector table. A Blink sketch that inlines all its GPIO calls carries no gpio.ll code at all.
- **Serial (USART0)** — `Serial.begin()`, `Serial.print()`, `Serial.println()`, `Serial.available()`, `Serial.read()` backed by a busy-wait AVR runtime; compile-time constant baud rates are inlined as four MMIO stores.
- **Non-blocking timing** — `Time.millis()` backed by a Timer0 overflow ISR; the compiler injects `__espressomachine_time_init()` before `setup()` only when `millis()` is referenced.
- **Utility functions** — `Functions.map()` and `Functions.constrain()` match Arduino's macros; both are pure Java and get constant-folded or inlined by TeaVM.
- **Arduino-style setup/loop** — entry classes without a `main()` can define `static void setup()` / `static void loop()`; EspressoMachine synthesizes the `main()` wrapper automatically.
- **Approval-style test suite** — golden `.ll` and `.hex` files catch regressions across all steps; system tests run generated HEX inside virtualavr.

## Requirements

| Tool | Version |
|------|---------|
| Java (JDK) | 17+ |
| Maven | 3.8+ |
| LLVM | 18 (`llc-18`, `llvm-as`) |
| AVR binutils | `avr-as`, `avr-ld`, `avr-objcopy`, `avr-size` |
| avrdude | for `espressomachine flash` |

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
espressomachine emit-llvm examples/blink/classes Blink -o Blink.ll

# Full pipeline → HEX
espressomachine build examples/blink/classes Blink --target atmega328p
avr-size build/Blink.elf

# Flash (replace /dev/ttyUSB0 with your port)
espressomachine flash build/Blink.hex --port /dev/ttyUSB0
```

## Running with Docker

No local toolchain required — the image bundles a JRE, LLVM 18, and the AVR binutils.

```bash
# Build the image
docker build -t espressomachine .

# Compile your class files to HEX (mount the directory that contains them)
docker run --rm \
    -v "/path/to/your/classes:/input:ro" \
    -v "$PWD/build:/workspace/build" \
    espressomachine build /input YourClass --target atmega328p

# Flash (requires passing the serial device into the container)
docker run --rm --device /dev/ttyUSB0 \
    -v "$PWD/build:/workspace/build" \
    espressomachine flash /workspace/build/Blink.hex --port /dev/ttyUSB0
```

The container entrypoint is `espressomachine`, so every subcommand (`build`, `emit-llvm`, `inspect`, `flash`) works the same way as the native CLI.

## CLI reference

```
espressomachine build     [--target <mcu>] [--cp <dirs>] [--output <dir>] <Foo.class|dir> [Name]
espressomachine inspect   [--cp <dirs>]                                   <Foo.class|dir> [Name]
espressomachine emit-llvm [--cp <dirs>] [-o <out.ll>]                    <Foo.class|dir> [Name]
espressomachine flash     --port <dev>                                    <Foo.hex>
```

Input forms:
- `Blink.class` — classpath = parent directory, entry class = `Blink`
- `examples/blink/classes Blink` — explicit classpath directory and class name
- `--cp dir1:dir2 ClassName` — colon-separated extra classpath entries

Default output directory: `build/` relative to the working directory.

## API reference

The API lives in `runtime/api/`. Compile the stubs once, then reference them when compiling user code:

```bash
mvn compile -q -f runtime/api/pom.xml
javac -cp runtime/api/target/classes MyProgram.java -d classes/
```

```java
import com.github.pfichtner.espressomachine.api.*;

// GPIO — lowered to AVR MMIO; constant pins are inlined to sbi/cbi/PIND load
GPIO.pinMode(2,  GPIO.INPUT_PULLUP);   // DDR clear + PORT set (internal pull-up)
GPIO.pinMode(13, GPIO.OUTPUT);
GPIO.digitalWrite(13, GPIO.HIGH);
GPIO.digitalWrite(13, GPIO.LOW);
int state = GPIO.digitalRead(2);       // reads PINx register bit → 0 or 1
int adc   = GPIO.analogRead(GPIO.A0); // 10-bit ADC, 0–1023
GPIO.analogWrite(9, 128);             // 8-bit PWM, 0–255

// Delay — busy-wait; constant ms is inlined as a calibrated loop
Delay.ms(500);
Delay.time(1, java.util.concurrent.TimeUnit.SECONDS);

// Serial — USART0; constant baud rate inlined as four MMIO stores
Serial.begin(9600);
Serial.print('A');
Serial.println(42);
Serial.println();           // CR+LF
int avail = Serial.available();
int b     = Serial.read();

// Time — Timer0 overflow counter; init injected automatically before setup()
int now = Time.millis();    // ms since boot, wraps ~49.7 days

// Functions — Arduino utility macros, inlined by TeaVM
int pwm = Functions.map(analogValue, 0, 1023, 0, 255);
int clamped = Functions.constrain(raw, 0, 255);
```

`GPIO` calls with compile-time-constant pin numbers are inlined to direct `sbi`/`cbi`/`PIND` load instructions; non-constant pins fall back to a runtime lookup table in `gpio.ll`. `Serial.begin()` with a constant baud rate is inlined as four USART register writes. `Time.millis()` is linked only when referenced; the Timer0 ISR and init are added automatically.

## Memory model

| Allocation | Pattern | LLVM | Status |
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
2. Provide `gpio.ll`, `delay.ll`, `time.ll` (or symlink if registers are identical).
3. Create `targets/<mcu>/startup.S` (with interrupt vector table), `linker.ld`.
4. Run: `espressomachine build MyClass --target <mcu>`

## Project layout

```
bin/espressomachine                  CLI launcher
teavm-backend/llvm/                  Java compiler (Maven project)
  src/main/java/com/github/pfichtner/espressomachine/
    cli/EspressoMachineCli.java      CLI entry point & subcommand dispatch
    cli/Pipeline.java                AVR toolchain orchestration
    LlvmModuleEmitter.java           LLVM IR module writer
    LlvmMethodEmitter.java           Per-method SSA → LLVM translation
    EscapeAnalyzer.java              Intra-procedural escape analysis
    AvrIntrinsics.java               Dispatch to per-API intrinsic emitters
    emit/
      GpioEmitter.java               GPIO → MMIO (pinMode, digitalRead/Write, analog)
      DelayEmitter.java              Delay.ms / Delay.time → busy-wait loop
      SerialEmitter.java             Serial → USART0 MMIO
      TimeEmitter.java               Time.millis() → Timer0 overflow counter
      MathBridgeEmitter.java         java.lang.Math → LLVM intrinsics / libm
      RandomEmitter.java             Random → LCG runtime
runtime/
  api/                               Target-agnostic Java stubs
    GPIO.java                        pinMode, digitalRead/Write, analogRead/Write
    Delay.java                       ms(), time()
    Serial.java                      begin, print, println, available, read
    Time.java                        millis()
    Functions.java                   map(), constrain()
    Random.java                      nextInt()
  avr/atmega328p/                    ATmega328P runtime implementation
    target.sh                        MCU descriptor (F_CPU, DELAY_ITERS)
    gpio.ll                          Runtime GPIO fallback (non-constant pins)
    delay.ll                         Busy-wait delay loop template
    serial.ll                        USART0 TX/RX busy-wait
    time.ll                          Timer0 overflow ISR + millis()
    random.ll / random.S             LCG random number generator
targets/atmega328p/                  Linker script, startup assembly, build script
  startup.S                          Full 26-entry interrupt vector table
  linker.ld                          Flash/SRAM layout with --gc-sections support
examples/                            15 demonstration programs
tests/
  run.sh                             Approval-style test runner (25 tests)
  approved/                          Golden .ll and .hex snapshots
  systemtest-blink.sh                End-to-end blink test on virtualavr
  systemtest-serial.sh               End-to-end serial print test on virtualavr
  systemtest-echo.sh                 End-to-end serial echo test on virtualavr
  systemtest-analog.sh               End-to-end ADC → blink-rate test on virtualavr
  systemtest-digitalread.sh          End-to-end digitalRead → blink test on virtualavr
```

## Tests

```bash
bash tests/run.sh             # run all 25 approval tests (exit 1 on any failure)
bash tests/run.sh <name>      # run a single test by name
bash tests/run.sh --approve   # overwrite golden files after an intentional change
```

### System integration tests (virtualavr)

System tests run the generated `.hex` inside [virtualavr](https://github.com/pfichtner/virtualavr),
an AVR simulator running in Docker. They are not part of the default `tests/run.sh` run:

```bash
bash tests/run.sh systemtest-blink          # LED blink timing
bash tests/run.sh systemtest-serial         # USART TX
bash tests/run.sh systemtest-echo           # USART RX→TX echo
bash tests/run.sh systemtest-analog         # ADC → blink rate
bash tests/run.sh systemtest-digitalread    # digital input → LED blink
RUN_INTEGRATION_TESTS=1 bash tests/run.sh  # approval tests + all system tests
```

All system tests accept a pre-built `.hex` as an optional first argument and fall back to the approved golden hex when the AVR toolchain is unavailable.

Configuration via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `VIRTUALAVR_IMAGE` | `pfichtner/virtualavr:latest` | Docker image |
| `BUILD_TIMEOUT` | `180` | Seconds to wait for container start |
| `BLINK_PIN` | `13` | Pin to watch (blink test) |
| `BLINK_TIMEOUT` | `5` | Observation window in seconds |
| `BLINK_TOGGLES` | `6` | Minimum toggle count |
| `SERIAL_BAUD` | `9600` | Baud rate for serial tests |
| `SERIAL_TIMEOUT` | `30` | Seconds to read from serial device |
| `SERIAL_MIN` | `3` | Minimum `'A'` byte count |

## How we got here

| Step | What |
|------|------|
| 0 | TeaVM feasibility — IR enumerable, backend interface identified |
| 1 | LLVM IR emitter — arithmetic, if/else, while loops, PHI nodes |
| 2 | Java objects — LLVM struct types, `getelementptr`, instance methods |
| 3 | Memory model — escape analysis, static globals, heap compile error |
| 4 | Embedded intrinsics — GPIO / Delay API, PORTB/DDRB MMIO inlining |
| 5 | ATmega328P target — startup, linker, `avr-ld`, produces flashable HEX |
| 6 | First Blink — `Blink.hex` 332 bytes, `sbi`/`cbi` verified by disassembly |
| 7 | OOP Blink — `Led` class with constructor, fields, instance methods; single-LED version 272 bytes (identical to imperative Blink, proving zero-overhead abstraction); current example alternates two LEDs: 668 bytes |
| CLI | `espressomachine build / inspect / emit-llvm / flash` |
| 8 | Serial, ADC, PWM, Random, java.lang.Math, java.util.Random |
| 9 | `GPIO.digitalRead` + `INPUT_PULLUP`, `Functions.map/constrain`, `Time.millis()` |
| 10 | Full interrupt vector table; Timer0 ISR for `millis()`; dead-code elimination (`--gc-sections`): Blink 272 bytes |

## Research notes

`teavm-backend/llvm/FINDINGS.md` documents the Phase 0 feasibility study: which TeaVM classes represent the IR, how the compilation pipeline is invoked programmatically, and the complete proposed mapping from TeaVM IR instructions to LLVM IR.

# EspressoMachine

Compiler: JVM bytecode (Java) → AVR native machine code (ATmega328P `.hex` files). Uses TeaVM as frontend (bytecode parsing, SSA optimization) and a custom LLVM IR backend targeting AVR.

## Build

```bash
# Build compiler (fat JAR via Maven shade plugin)
mvn package -q -f teavm-backend/llvm/pom.xml

# Compile runtime API stubs
mvn compile -q -f runtime/api/pom.xml

# Compile all examples
mvn compile -q -f examples/pom.xml
```

## Test

```bash
# Run all approval (golden-file) tests — builds JAR + examples automatically if missing
bash tests/run.sh

# Run a single approval test
bash tests/run.sh <name>          # e.g. "add", "blink-hex", "serial"

# Approve new golden files (run after intentional output changes)
bash tests/run.sh --approve

# System tests (require Docker + websocat + jq)
bash tests/systemtest-blink.sh
bash tests/systemtest-serial.sh
bash tests/systemtest-echo.sh
```

No lint, typecheck, or formatter is configured. CI runs only: build → approval tests → system tests.

After every relevant code change, run the applicable host-side tests. If a required tool is missing, attempt to install it first (use `sudo` if necessary). Skip tests that cannot run in the current environment (e.g. Docker-based system tests when Docker is unavailable).

## Project layout

| Path | Purpose |
|------|---------|
| `teavm-backend/llvm/` | Core compiler (Java 17, Maven). Entry: `espressomachine/cli/EspressoMachineCli.java` |
| `runtime/api/` | Java API stubs: `GPIO`, `Delay`, `Serial` |
| `runtime/avr/atmega328p/` | MCU runtime `.ll` files (GPIO, Delay, Serial) — templates with `__DELAY_ITERS__` placeholder |
| `targets/atmega328p/` | `startup.S` (reset vector), `linker.ld`, `build.sh` |
| `examples/` | 11 Java example programs (each a Maven sub-module) |
| `tests/approved/` | Golden snapshots (`.ll` and `.hex`) |
| `bin/espressomachine` | CLI wrapper script |

Multi-module Maven project — no top-level POM. `examples/pom.xml` is a reactor that includes `runtime/api` + 11 example modules.

## Gotchas

- **Golden files use LF**: `.gitattributes` enforces `eol=lf` on `tests/approved/*.ll` and `*.hex`. Do not force CRLF or approval diffs will break cross-platform.
- **`tests/run.sh` auto-builds**: The test runner calls `mvn package` / `mvn compile` itself if artifacts are missing. You don't need to build separately before running tests, but rebuilding first is faster for iteration.
- **Template placeholders**: `delay.ll` uses `__DELAY_ITERS__` (replaced per MCU); `startup.S` uses `__ENTRY_CLASS__` (replaced per entry point). Do not hardcode values — the build scripts substitute them.
- **CLI requires `espressomachine` on PATH**: Add `bin/` to PATH or invoke `bin/espressomachine` directly. The test runner does this automatically.
- **System tests need Docker**: The virtualavr simulator runs in Docker. System tests will fail without Docker and `websocat`.
- **No static analysis**: There is no linter, formatter, or typecheck tool configured.

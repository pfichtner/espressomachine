TinyJava — Revised PRD
1. Vision

TinyJava compiles JVM bytecode produced by Java (and potentially other JVM languages) into native code for microcontrollers, initially the ATmega328P, with no JVM, interpreter, JIT, or managed runtime required on the MCU.

The preferred architecture is:

Java
  │
  ▼
javac
  │
  ▼
.class / .jar
  │
  ▼
┌─────────────────────────┐
│         TeaVM           │
│                         │
│ class loading           │
│ bytecode analysis       │
│ dependency analysis     │
│ optimization            │
│ devirtualization        │
│ JVM → optimized IR      │
└────────────┬────────────┘
             │
             ▼
      TinyJava backend
             │
             ▼
         LLVM IR
             │
             ▼
       LLVM AVR backend
             │
             ▼
       native AVR code
             │
             ▼
          ATmega328P

The project should not duplicate functionality already provided by TeaVM unless technically necessary.

2. First objective: feasibility, not a compiler

Before implementing TinyJava itself, build a TeaVM backend feasibility prototype.

The first milestone is not Blink.

It is:

Can we take TeaVM's optimized IR for a trivial Java program and translate it into valid LLVM IR?

If yes, TeaVM becomes the foundation of TinyJava.

3. Why TeaVM

TeaVM already works from JVM bytecode rather than Java source and has a compilation pipeline involving an intermediate representation and dependency analysis.

Therefore we want to reuse:

.class parsing
JVM bytecode processing
call/dependency analysis
reachable-code analysis
optimization
devirtualization
Java type information
object model where useful

TinyJava should concentrate on:

embedded restrictions
MCU-specific runtime
memory/allocation policy
LLVM IR generation
MCU target integration
4. Architectural principle

TeaVM is the frontend. TinyJava is the embedded backend/profile.

Do not fork or duplicate TeaVM functionality prematurely.

Conceptually:

                    JVM bytecode
                         │
                         ▼
                    ┌─────────┐
                    │  TeaVM  │
                    └────┬────┘
                         │
                  optimized IR
                         │
                         ▼
                 ┌──────────────┐
                 │   TinyJava   │
                 │              │
                 │ AVR backend  │
                 │ memory model │
                 │ intrinsics   │
                 └──────┬───────┘
                        │
                        ▼
                    LLVM IR
5. Phase 0 — TeaVM feasibility spike
Goal

Determine whether TeaVM's internal IR is suitable as the source for an LLVM backend.

Tasks
Build current TeaVM from source.
Compile a minimal Java program with javac.
Run it through TeaVM.
Locate the optimized IR representation.
Write an IR dumper.
Identify the backend interface.
Determine what runtime services the C backend relies on.
Implement a tiny experimental LLVM emitter.
Test 1
class Add {
    static int add(int a, int b) {
        return a + b;
    }
}

Expected:

Add.class
    ↓
TeaVM
    ↓
TeaVM IR
    ↓
LLVM IR

Expected LLVM conceptually:

define i32 @add(i32 %a, i32 %b) {
entry:
    %result = add i32 %a, %b
    ret i32 %result
}
Phase 0 acceptance criteria
TeaVM can be embedded/invoked programmatically.
We can access the relevant optimized IR.
We can enumerate methods/basic blocks/instructions.
We can translate constants, arithmetic, arguments and returns.
Generated LLVM passes LLVM validation.
No C is generated as an intermediate representation.
6. Phase 1 — Control flow

Support TeaVM IR constructs needed for:

static int test(int x) {
    if (x > 10)
        return 1;
    else
        return 0;
}

and:

static int count() {
    int x = 0;

    while (x < 10)
        x++;

    return x;
}

Translate:

TeaVM basic blocks
        ↓
LLVM basic blocks
        ↓
LLVM branches / SSA

Acceptance:

if
loops
branches
integer comparison
PHI nodes where required
function calls
returns
7. Phase 2 — Java objects

Support:

class Counter {
    int value;

    void increment() {
        value++;
    }
}

and:

Counter c = new Counter();
c.increment();

The backend must understand TeaVM's representation of:

classes
fields
references
constructors
instance methods

Target LLVM representation may be:

%Counter = type {
    i32
}

with methods such as:

define void @Counter_increment(ptr %this) {
    ...
}

Exact representation should follow what works best with TeaVM's IR rather than being predetermined.

8. Phase 3 — TinyJava memory model

This is where we intentionally diverge from a normal JVM.

Supported allocation
Local/non-escaping
void foo() {
    Foo f = new Foo();
    f.run();
}

→ stack allocation or optimization away.

Static
static Foo foo = new Foo();

→ statically allocated object.

Not initially supported
Foo makeFoo() {
    return new Foo();
}

if this requires dynamic heap allocation.

Compiler:

error: allocation escapes supported lifetime
heap allocation is not yet supported for target atmega328p
Future
stack
static
   ↓
heap
   ↓
GC

Heap/GC is explicitly out of scope for the initial release.

9. Phase 4 — Embedded intrinsics

Define a tiny Java-facing hardware API:

GPIO.pinMode(13, GPIO.OUTPUT);
GPIO.digitalWrite(13, GPIO.HIGH);
GPIO.digitalWrite(13, GPIO.LOW);

Delay.ms(500);

The backend recognizes these operations as target intrinsics.

Initially:

GPIO.pinMode()
GPIO.digitalWrite()
Delay.ms()

Only the ATmega328P target needs to implement them.

10. Phase 5 — ATmega328P target

Target:

ATmega328P

Initial configuration:

CPU: 16 MHz
Flash: 32 KB
SRAM: 2 KB

Initial GPIO mapping:

Arduino pin 13
       ↓
     PB5
       ↓
PORTB bit 5

Runtime:

startup.S
gpio.S
delay.S
linker.ld

No JVM runtime.

11. Phase 6 — First Blink

Input:

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

Pipeline:

Blink.java
   ↓
javac
   ↓
Blink.class
   ↓
TeaVM
   ↓
optimized TeaVM IR
   ↓
TinyJava LLVM backend
   ↓
LLVM IR
   ↓
LLVM AVR
   ↓
ELF
   ↓
HEX
   ↓
ATmega328P

Acceptance:

Physical LED blinks approximately every 500 ms.

12. Phase 7 — Object-oriented Blink

Then use:

class Led {
    int pin;

    Led(int pin) {
        this.pin = pin;
    }

    void on() {
        GPIO.digitalWrite(pin, GPIO.HIGH);
    }

    void off() {
        GPIO.digitalWrite(pin, GPIO.LOW);
    }
}

class Blink {
    static void main() {
        Led led = new Led(13);

        while (true) {
            led.on();
            Delay.ms(500);
            led.off();
            Delay.ms(500);
        }
    }
}

This is the real TinyJava v0.1 acceptance test.

It proves:

JVM bytecode consumption
TeaVM integration
instance objects
constructors
fields
instance calls
allocation lowering
control flow
GPIO
delay
LLVM
AVR code generation
physical execution
13. CLI

Eventually:

tinyjava build Blink.class --target atmega328p

Output:

build/
    Blink.ll
    Blink.elf
    Blink.hex

Optional:

tinyjava inspect Blink.class
tinyjava emit-llvm Blink.class
tinyjava flash build/Blink.hex --port /dev/ttyUSB0

The compiler should also accept a .jar eventually.

14. Runtime philosophy

The runtime should be as small as possible.

Do not port an entire desktop Java runtime.

Prefer:

Java API
   ↓
TeaVM intrinsic
   ↓
TinyJava backend
   ↓
AVR operation

For example:

Delay.ms(500)

should ultimately become a small AVR routine rather than a dependency on a large Java runtime.

15. Testing
Compiler tests
TeaVM IR → LLVM

Golden tests for:

arithmetic
branches
loops
calls
fields
objects
allocation
Runtime tests
GPIO high
GPIO low
GPIO direction
delay
Integration
.java
 ↓
javac
 ↓
.class
 ↓
TeaVM
 ↓
TinyJava
 ↓
LLVM
 ↓
AVR
Hardware

Blink on a real ATmega328P.

16. Explicit architectural decision

The project should not commit to reimplementing TeaVM's frontend unless Phase 0 proves that TeaVM cannot provide a suitable integration point.

Decision gate:

                 Investigate TeaVM
                       │
                       ▼
              Can we access IR?
                 /          \
               yes           no
                │             │
                ▼             ▼
          LLVM backend     Re-evaluate
                │
                ▼
        Can IR represent Java?
                │
          ┌─────┴─────┐
         yes          no
          │            │
          ▼            ▼
      Use TeaVM     Own frontend

This prevents us from spending months implementing functionality TeaVM already has.

17. Suggested repository

I'd actually rename the initial experimental project:

tinyjava/
├── teavm-backend/
│   └── llvm/
│
├── runtime/
│   └── avr/
│       └── atmega328p/
│
├── targets/
│   └── atmega328p/
│
├── examples/
│   └── blink/
│
└── tests/

Don't fork all of TeaVM into the repository unless necessary. Prefer depending on TeaVM as a library/module initially.

18. First coding-agent task

I'd give the agent only this task initially:

Investigate the current TeaVM source and implement a minimal experimental backend/tool that receives TeaVM's optimized IR and dumps its structure. Do not implement AVR, LLVM, GPIO, or the TinyJava runtime yet.

Demonstrate that the following Java program can be compiled with javac, processed by TeaVM, and its resulting IR inspected:

class Add {
    static int add(int a, int b) {
        return a + b;
    }
}

Document:

Which TeaVM classes represent the IR.
How the compilation pipeline reaches that IR.
How a backend accesses it.
Which runtime/dependency-analysis components are required.
Whether implementing an LLVM backend is practical.
A proposed mapping from TeaVM IR instructions to LLVM IR.

Do not make architectural assumptions without verifying them against the actual

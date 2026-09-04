; EspressoMachine ATmega328P startup
; Builds the complete interrupt vector table (26 × 4-byte entries) so that
; enabled interrupts jump to valid handlers.  Unused vectors return
; immediately with RETI.  The TIMER0_OVF slot (vector 16) links to
; __vector_16; when time.o is not linked the weak default below fires
; (also a bare RETI), so programs that don't call Time.millis() are
; unaffected.

.section .vectors, "ax", @progbits

; ---- Vector table (4 bytes per entry at 16-MHz / 32 KB flash) ----

; Vector 0  — RESET
.global __vector_reset
__vector_reset:
    jmp __startup_init

; Vectors 1–15 — unused; RETI + NOP to fill exactly 4 bytes each
.rept 15
    reti
    nop
.endr

; Vector 16 — TIMER0_OVF (= 0x0040)
    jmp __vector_16

; Vectors 17–25 — unused
.rept 9
    reti
    nop
.endr

; ---- Weak default for TIMER0_OVF (overridden by time.o when millis used) ----
.weak __vector_16
__vector_16:
    reti

; ---- Startup code ----
__startup_init:
    ; Set stack pointer to top of SRAM (0x08FF for ATmega328P)
    ldi r16, hi8(0x08FF)
    out 0x3E, r16          ; SPH
    ldi r16, lo8(0x08FF)
    out 0x3D, r16          ; SPL
    ; Jump to EspressoMachine main
    call __ENTRY_CLASS___main
    ; Halt (infinite loop as safety net)
.Lhalt:
    rjmp .Lhalt

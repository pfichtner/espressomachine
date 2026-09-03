; EspressoMachine ATmega328P startup
; Sets up the stack and calls __ENTRY_CLASS___main (substituted by build.sh).
; Placed at the reset vector (0x0000).

.section .vectors, "ax", @progbits
.global __vector_reset
__vector_reset:
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

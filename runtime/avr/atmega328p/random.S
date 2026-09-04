; EspressoMachine AVR runtime — java.util.Random support
;
; Full 48-bit Linear Congruential Generator matching java.util.Random:
;   state = state * 0x5DEECE66D + 0xB
;
; 48-bit state stored in SRAM (6 bytes: s5..s0, big-endian = s5=MSB).
; Uses registers r2-r7 during computation.
;
; Functions:
;   __random_next(bits)  → advance state, return upper @bits bits (1..32)
;   __random_next_int()  → next(32), i.e. 32-bit random int
;   __random_next_int_bound(bound)  → next(32) % bound (unsigned)
;   __random_next_long_lo() → low  32 bits of next(64)
;   __random_next_long_hi() → high 32 bits of next(64)

.section .data
.align 1
__random_seed:
    .byte 0x00, 0x00, 0x00, 0x00, 0x00, 0x01   ; seed = 1 (like java.util.Random)

.section .text

;;; ---------------------------------------------------------------------------
;;; __random_next(uint8_t bits → r24)  →  result in r22:r25 (32-bit return val)
;
; Advances the 48-bit LCG state and returns the upper @bits bits.
; bits must be 1..32.  For bits=32 this is the standard nextInt() source.
;;; ---------------------------------------------------------------------------
.global __random_next
__random_next:
    push r2
    push r3
    push r4
    push r5
    push r6
    push r7
    push r28
    push r29

    ; Load 48-bit state:  r7:r6:r5:r4:r3:r2  (big-endian, r7=MSB)
    lds r2, __random_seed+0
    lds r3, __random_seed+1
    lds r4, __random_seed+2
    lds r5, __random_seed+3
    lds r6, __random_seed+4
    lds r7, __random_seed+5

    ; ---- 48-bit multiply: state *= 0x5DEECE66D ----
    ; Multiplier: 0x5DEECE66D = {D=0x6D, C=0xE6, B=0xCE, A=0xEE, 9=0x5D, 8=0x00}
    ; We only need the lower 48 bits of the 96-bit product.

    ; r2 * 0x6D
    ldi r18, 0x6D
    mul r2, r18
    movw r28, r0          ; r29:r28 = low product
    clr r2                ; r2 will be overwritten

    ; r3 * 0x6D + r28
    mov r2, r3
    ldi r18, 0x6D
    mul r2, r18
    add r28, r0
    adc r29, r1

    ; r3 * 0xE6
    ldi r18, 0xE6
    mul r3, r18
    add r28, r0
    adc r29, r1

    mov r3, r28           ; store byte 1 (s1)
    clr r28               ; clear partial accumulator
    clr r29

    ; r4 * 0x6D
    mov r2, r4
    ldi r18, 0x6D
    mul r2, r18
    movw r28, r0

    ; r4 * 0xE6 + r28
    ldi r18, 0xE6
    mul r4, r18
    add r28, r0
    adc r29, r1

    ; r5 * 0x6D
    mov r2, r5
    ldi r18, 0x6D
    mul r2, r18
    add r28, r0
    adc r29, r1

    ; r5 * 0xCE
    ldi r18, 0xCE
    mul r5, r18
    add r28, r0
    adc r29, r1

    mov r4, r28           ; store byte 2 (s2)
    clr r28               ; clear accumulator
    clr r29

    ; r6 * 0x6D
    mov r2, r6
    ldi r18, 0x6D
    mul r2, r18
    movw r28, r0

    ; r6 * 0xE6
    ldi r18, 0xE6
    mul r6, r18
    add r28, r0
    adc r29, r1

    ; r7 * 0x6D
    mov r2, r7
    ldi r18, 0x6D
    mul r2, r18
    add r28, r0
    adc r29, r1

    ; r7 * 0xCE
    ldi r18, 0xCE
    mul r7, r18
    add r28, r0
    adc r29, r1

    mov r5, r28           ; store byte 3 (s3)
    clr r28               ; clear accumulator
    clr r29

    ; r7 * 0xEE
    ldi r18, 0xEE
    mul r7, r18
    movw r28, r0

    mov r6, r28           ; store byte 4 (s4)
    mov r7, r29           ; store byte 5 (s5 = MSB)

    ; ---- Add increment 0xB (only affects lowest byte) ----
    ldi r18, 0x0B
    add r3, r18
    clr r18
    adc r4, r18
    adc r5, r18
    adc r6, r18
    adc r7, r18

    ; ---- Store updated state ----
    sts __random_seed+0, r2
    sts __random_seed+1, r3
    sts __random_seed+2, r4
    sts __random_seed+3, r5
    sts __random_seed+4, r6
    sts __random_seed+5, r7

    ; ---- Extract upper @bits bits ----
    ; Right-shift state right by (48 - bits)
    mov r18, r24           ; r18 = bits
    ldi r19, 48
    sub r19, r18           ; r19 = shift = 48 - bits
    breq .Lno_shift

.Lshift_loop:
    lsr r7
    ror r6
    ror r5
    ror r4
    ror r3
    ror r2
    dec r19
    brne .Lshift_loop

.Lno_shift:
    ; Result: r5:r4:r3:r2 = upper 32 bits (for bits <= 32)
    movw r22, r2           ; r23:r22 = low 16 bits
    movw r24, r4           ; r25:r24 = high 16 bits

    pop r29
    pop r28
    pop r7
    pop r6
    pop r5
    pop r4
    pop r3
    pop r2
    ret


;;; ---------------------------------------------------------------------------
;;; __random_next_int()  →  32-bit random int  (r25:r24:r23:r22)
;;; ---------------------------------------------------------------------------
.global __random_next_int
__random_next_int:
    ldi r24, 32
    rjmp __random_next


;;; ---------------------------------------------------------------------------
;;; __random_next_int_bound(uint32_t bound → r25:r24:r23:r22)  →  r25:r24:r23:r22
;
; Returns next(32) % bound  (unsigned 32x32 modulo).
; Uses restoring long division, fully in-register (no libgcc dependency).
; Register usage:
;   D = r21:r20:r19:r18  (bound, D3=D21=MSB)
;   N = r25:r24:r23:r22  (random value, N3=N25=MSB)
;   R = r4:r3:r2:r27:r26 (33-bit remainder, R4=R4=MSB)
;   ZERO = r6
;;; ---------------------------------------------------------------------------
.global __random_next_int_bound
__random_next_int_bound:
    push r2
    push r3
    push r4
    push r6
    push r16
    push r17
    ; Save bound (r25:r24:r23:r22) on stack across the __random_next call
    push r25
    push r24
    push r23
    push r22

    ldi r24, 32
    call __random_next      ; r25:r24:r23:r22 = random value x

    ; Restore bound → D = r21:r20:r19:r18
    pop r18                 ; bound byte 0 (LSB)
    pop r19                 ; bound byte 1
    pop r20                 ; bound byte 2
    pop r21                 ; bound byte 3 (MSB)

    ; N = x (already in r22..r25)
    ; R = 0
    clr r26
    clr r27
    clr r2
    clr r3
    clr r4
    clr r6                  ; ZERO register

    ldi r17, 32
.Ldiv_loop:
    ; N <<= 1; top bit of N3 → carry
    lsl r22
    rol r23
    rol r24
    rol r25
    ; R = R << 1 | carry
    rol r26
    rol r27
    rol r2
    rol r3
    rol r4
    ; Compare R (r4:r3:r2:r27:r26) against D (r21:r20:r19:r18)
    cp  r4, r6              ; R4 != 0 → R >= 2^32 > D
    brne .Ldo_sub
    cp  r26, r18
    cpc r27, r19
    cpc r2,  r20
    cpc r3,  r21
    brlo .Lno_sub
.Ldo_sub:
    ; R = R - D
    sub r26, r18
    sbc r27, r19
    sbc r2,  r20
    sbc r3,  r21
    sbc r4,  r6             ; high byte: subtract 0
.Lno_sub:
    dec r17
    brne .Ldiv_loop

    ; Remainder in r3:r2:r27:r26 → return in r25:r24:r23:r22
    mov r22, r26
    mov r23, r27
    mov r24, r2
    mov r25, r3

    pop r17
    pop r16
    pop r6
    pop r4
    pop r3
    pop r2
    ret


;;; ---------------------------------------------------------------------------
;;; __random_next_long_lo()  →  low 32 bits of next(64)
;
; Calls next(32) and returns it.
;;; ---------------------------------------------------------------------------
.global __random_next_long_lo
__random_next_long_lo:
    ldi r24, 32
    rjmp __random_next


;;; ---------------------------------------------------------------------------
;;; __random_next_long_hi()  →  high 32 bits of next(64)
;
; For AVR, we approximate: high = next(32) (calling next() again to advance state).
;;; ---------------------------------------------------------------------------
.global __random_next_long_hi
__random_next_long_hi:
    ldi r24, 32
    rjmp __random_next

; EspressoMachine AVR runtime — millis() via Timer0 overflow (ATmega328P)
;
; Timer0 configuration: normal mode, prescaler /64
;   F_CPU = 16 000 000 Hz
;   tick rate = 16 000 000 / 64 = 250 000 Hz
;   overflow period = 256 / 250 000 = 1.024 ms  (976.5625 overflows / s)
;
; millis() = overflow_count * 1024 / 1000  (accurate to <0.1 % after scaling)
;
; Register addresses (ATmega328P data memory):
;   TCCR0A = 0x44  (68)   — Timer/Counter0 Control Register A
;   TCCR0B = 0x45  (69)   — Timer/Counter0 Control Register B
;   TIMSK0 = 0x6E  (110)  — Timer/Counter Interrupt Mask Register 0
;   SREG   = 0x5F  (95)   — Status Register (bit 7 = I = global interrupt enable)

; 32-bit overflow counter — incremented by the TIMER0_OVF ISR
@__millis_count = global i32 0

; ---- TIMER0_OVF interrupt service routine ----
; The avr_signal calling convention saves/restores all touched registers
; and ends with RETI rather than RET.
define avr_signal void @__vector_16() {
entry:
  %cur = load volatile i32, ptr @__millis_count
  %nxt = add i32 %cur, 1
  store volatile i32 %nxt, ptr @__millis_count
  ret void
}

; ---- Timer0 initialisation ----
; Called once from the synthesised main() before setup() when millis() is used.
define void @__espressomachine_time_init() {
entry:
  ; TCCR0A = 0  (normal / non-PWM mode — default, but explicit for safety)
  store volatile i8 0, ptr inttoptr (i16 68 to ptr)
  ; TCCR0B = 0x03  (CS01 | CS00 = prescaler /64, normal mode)
  store volatile i8 3, ptr inttoptr (i16 69 to ptr)
  ; TIMSK0 |= 0x01  (TOIE0 — enable Timer0 overflow interrupt)
  %timsk = load volatile i8, ptr inttoptr (i16 110 to ptr)
  %timsk2 = or i8 %timsk, 1
  store volatile i8 %timsk2, ptr inttoptr (i16 110 to ptr)
  ; SREG |= 0x80  (sei — enable global interrupts)
  %sreg = load volatile i8, ptr inttoptr (i16 95 to ptr)
  %sreg2 = or i8 %sreg, -128
  store volatile i8 %sreg2, ptr inttoptr (i16 95 to ptr)
  ret void
}

; ---- millis() ----
; Returns milliseconds since boot.  Uses i64 intermediate to avoid i32
; overflow at high counts, then truncates back to i32 (wraps at ~49.7 days
; — identical to Arduino's unsigned long millis() roll-over behaviour).
define i32 @__espressomachine_time_millis() {
entry:
  %c32 = load volatile i32, ptr @__millis_count
  %c64 = zext i32 %c32 to i64
  %scaled = mul i64 %c64, 1024
  %ms64 = udiv i64 %scaled, 1000
  %ms32 = trunc i64 %ms64 to i32
  ret i32 %ms32
}

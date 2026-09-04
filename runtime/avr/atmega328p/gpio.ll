; EspressoMachine AVR runtime — GPIO intrinsics (ATmega328P)
; Fallback implementations for runtime-dispatch paths (non-constant pin numbers).
;
; These are emitted when the pin or mode could not be resolved at compile time.
; For the common case (constant pins), AvrIntrinsics.java inlines the I/O directly.
;
; ATmega328P register map:
;   DDRB  = 0x24   PORTB = 0x25
;   DDRC  = 0x27   PORTC = 0x28
;   DDRD  = 0x2A   PORTD = 0x2B

; Arduino digital pin → (DDR address, PORT address, PIN address, bit mask)
; Table encoded per row, 14 pins (D0–D13).

@__pin_ddr  = private constant [14 x i8] [
    i8 42, i8 42, i8 42, i8 42, i8 42, i8 42, i8 42, i8 42,  ; D0–D7  DDRD=0x2A
    i8 36, i8 36, i8 36, i8 36, i8 36, i8 36                  ; D8–D13 DDRB=0x24
]
@__pin_port = private constant [14 x i8] [
    i8 43, i8 43, i8 43, i8 43, i8 43, i8 43, i8 43, i8 43,  ; D0–D7  PORTD=0x2B
    i8 37, i8 37, i8 37, i8 37, i8 37, i8 37                  ; D8–D13 PORTB=0x25
]
@__pin_pin  = private constant [14 x i8] [
    i8 41, i8 41, i8 41, i8 41, i8 41, i8 41, i8 41, i8 41,  ; D0–D7  PIND=0x29
    i8 35, i8 35, i8 35, i8 35, i8 35, i8 35                  ; D8–D13 PINB=0x23
]
@__pin_mask = private constant [14 x i8] [
    i8  1, i8  2, i8  4, i8  8, i8 16, i8 32, i8 64, i8 -128, ; D0–D7
    i8  1, i8  2, i8  4, i8  8, i8 16, i8  32                  ; D8–D13
]

; mode: 0=INPUT, 1=OUTPUT, 2=INPUT_PULLUP
define void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode) {
entry:
  %ddr_idx  = getelementptr [14 x i8], ptr @__pin_ddr,  i32 0, i32 %pin
  %ddr_byte = load i8, ptr %ddr_idx
  %ddr_a16  = zext i8 %ddr_byte to i16
  %ddr_addr = inttoptr i16 %ddr_a16 to ptr
  %mask_idx = getelementptr [14 x i8], ptr @__pin_mask, i32 0, i32 %pin
  %mask     = load i8, ptr %mask_idx
  %inv      = xor i8 %mask, -1
  %is_out   = icmp eq i32 %mode, 1
  br i1 %is_out, label %set_out, label %set_in
set_out:
  %cur_ddr_out = load volatile i8, ptr %ddr_addr
  %new_out     = or i8 %cur_ddr_out, %mask
  store volatile i8 %new_out, ptr %ddr_addr
  ret void
set_in:
  %cur_ddr_in = load volatile i8, ptr %ddr_addr
  %new_in     = and i8 %cur_ddr_in, %inv
  store volatile i8 %new_in, ptr %ddr_addr
  ; INPUT_PULLUP (mode==2): set PORT bit to enable internal pull-up
  %is_pullup = icmp eq i32 %mode, 2
  br i1 %is_pullup, label %set_pullup, label %done
set_pullup:
  %port_idx  = getelementptr [14 x i8], ptr @__pin_port, i32 0, i32 %pin
  %port_byte = load i8, ptr %port_idx
  %port_a16  = zext i8 %port_byte to i16
  %port_addr = inttoptr i16 %port_a16 to ptr
  %cur_port  = load volatile i8, ptr %port_addr
  %new_port  = or i8 %cur_port, %mask
  store volatile i8 %new_port, ptr %port_addr
  ret void
done:
  ret void
}

define i32 @__espressomachine_gpio_digitalread(i32 %pin) {
entry:
  %pin_idx  = getelementptr [14 x i8], ptr @__pin_pin,  i32 0, i32 %pin
  %pin_byte = load i8, ptr %pin_idx
  %pin_a16  = zext i8 %pin_byte to i16
  %pin_addr = inttoptr i16 %pin_a16 to ptr
  %mask_idx = getelementptr [14 x i8], ptr @__pin_mask, i32 0, i32 %pin
  %mask     = load i8, ptr %mask_idx
  %cur      = load volatile i8, ptr %pin_addr
  %masked   = and i8 %cur, %mask
  %ne       = icmp ne i8 %masked, 0
  %result   = zext i1 %ne to i32
  ret i32 %result
}

; ATmega328P analog register map:
;   ADMUX  = 0x7C (124)  ADC multiplexer / reference select
;   ADCSRA = 0x7A (122)  ADC control and status A
;   ADCL   = 0x78 (120)  ADC result low byte (must be read first)
;   ADCH   = 0x79 (121)  ADC result high byte

define i32 @__espressomachine_gpio_analogread(i32 %pin) {
entry:
  %chan   = and i32 %pin, 7
  %chan8  = trunc i32 %chan to i8
  %admux  = or i8 %chan8, 64            ; REFS0 (bit 6) = AVcc reference
  store volatile i8 %admux, ptr inttoptr (i16 124 to ptr)
  %cur    = load volatile i8, ptr inttoptr (i16 122 to ptr)
  %start  = or i8 %cur, -57            ; ADEN(7)|ADSC(6)|ADPS2(2)|ADPS1(1)|ADPS0(0)
  store volatile i8 %start, ptr inttoptr (i16 122 to ptr)
  br label %poll
poll:
  %st     = load volatile i8, ptr inttoptr (i16 122 to ptr)
  %adsc   = and i8 %st, 64
  %busy   = icmp ne i8 %adsc, 0
  br i1 %busy, label %poll, label %done
done:
  %lo     = load volatile i8, ptr inttoptr (i16 120 to ptr)  ; ADCL
  %hi     = load volatile i8, ptr inttoptr (i16 121 to ptr)  ; ADCH
  %lo32   = zext i8 %lo to i32
  %hi32   = zext i8 %hi to i32
  %hish   = shl i32 %hi32, 8
  %result = or i32 %lo32, %hish
  ret i32 %result
}

; PWM-capable Arduino pins and their timer register addresses.
;
; Pin  Timer  TCCRxA  COM bits  OCR addr
;   3  T2/B    0xB0    0x20      0xB4
;   5  T0/B    0x44    0x20      0x48
;   6  T0/A    0x44    0x80      0x47
;   9  T1/A    0x80    0x80      0x88
;  10  T1/B    0x80    0x20      0x8A
;  11  T2/A    0xB0    0x80      0xB3
;
; TCCRxA COM bits enable the compare-output pin in fast-PWM mode.
; The prescaler and WGM bits in TCCRxB are assumed to be set by user startup code.

@__pwm_pin  = private constant [6 x i8]  [i8  3, i8  5, i8  6, i8  9, i8 10, i8 11]
@__pwm_tccr = private constant [6 x i16] [i16 176, i16 68, i16 68, i16 128, i16 128, i16 176]
@__pwm_com  = private constant [6 x i8]  [i8 32, i8 32, i8 -128, i8 -128, i8 32, i8 -128]
@__pwm_ocr  = private constant [6 x i16] [i16 180, i16 72, i16 71, i16 136, i16 138, i16 179]

define void @__espressomachine_gpio_analogWrite(i32 %pin, i32 %value) {
entry:
  %pin8   = trunc i32 %pin to i8
  %duty   = trunc i32 %value to i8
  br label %loop
loop:
  %idx    = phi i32 [ 0, %entry ], [ %next, %no_match ]
  %done   = icmp eq i32 %idx, 6
  br i1 %done, label %exit, label %check
check:
  %p_ptr  = getelementptr [6 x i8], ptr @__pwm_pin, i32 0, i32 %idx
  %p      = load i8, ptr %p_ptr
  %match  = icmp eq i8 %p, %pin8
  br i1 %match, label %write, label %no_match
write:
  %tc_ptr = getelementptr [6 x i16], ptr @__pwm_tccr, i32 0, i32 %idx
  %tc_a   = load i16, ptr %tc_ptr
  %tc_ptr2 = inttoptr i16 %tc_a to ptr
  %tccr   = load volatile i8, ptr %tc_ptr2
  %com_p  = getelementptr [6 x i8], ptr @__pwm_com, i32 0, i32 %idx
  %com    = load i8, ptr %com_p
  %new_tc = or i8 %tccr, %com
  store volatile i8 %new_tc, ptr %tc_ptr2
  %oc_ptr = getelementptr [6 x i16], ptr @__pwm_ocr, i32 0, i32 %idx
  %oc_a   = load i16, ptr %oc_ptr
  %oc_ptr2 = inttoptr i16 %oc_a to ptr
  store volatile i8 %duty, ptr %oc_ptr2
  ret void
no_match:
  %next   = add i32 %idx, 1
  br label %loop
exit:
  ret void
}

define void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value) {
entry:
  %port_idx = getelementptr [14 x i8], ptr @__pin_port, i32 0, i32 %pin
  %port_byte = load i8, ptr %port_idx
  %port_addr16 = zext i8 %port_byte to i16
  %port_addr = inttoptr i16 %port_addr16 to ptr
  %mask_idx = getelementptr [14 x i8], ptr @__pin_mask, i32 0, i32 %pin
  %mask = load i8, ptr %mask_idx
  %cur = load volatile i8, ptr %port_addr
  %is_high = icmp ne i32 %value, 0
  br i1 %is_high, label %set_high, label %set_low
set_high:
  %new_hi = or i8 %cur, %mask
  store volatile i8 %new_hi, ptr %port_addr
  ret void
set_low:
  %inv = xor i8 %mask, -1
  %new_lo = and i8 %cur, %inv
  store volatile i8 %new_lo, ptr %port_addr
  ret void
}

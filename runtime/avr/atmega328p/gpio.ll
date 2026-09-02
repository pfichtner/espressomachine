; ByteLight AVR runtime — GPIO intrinsics (ATmega328P)
; Fallback implementations for runtime-dispatch paths (non-constant pin numbers).
;
; These are emitted when the pin or mode could not be resolved at compile time.
; For the common case (constant pins), AvrIntrinsics.java inlines the I/O directly.
;
; ATmega328P register map:
;   DDRB  = 0x24   PORTB = 0x25
;   DDRC  = 0x27   PORTC = 0x28
;   DDRD  = 0x2A   PORTD = 0x2B

; Arduino digital pin → (DDR address, PORT address, bit mask)
; Table encoded as 3 x i8 per row, 14 pins (D0–D13).

@__pin_ddr  = private constant [14 x i8] [
    i8 42, i8 42, i8 42, i8 42, i8 42, i8 42, i8 42, i8 42,  ; D0–D7  DDRD=0x2A
    i8 36, i8 36, i8 36, i8 36, i8 36, i8 36                  ; D8–D13 DDRB=0x24
]
@__pin_port = private constant [14 x i8] [
    i8 43, i8 43, i8 43, i8 43, i8 43, i8 43, i8 43, i8 43,  ; D0–D7  PORTD=0x2B
    i8 37, i8 37, i8 37, i8 37, i8 37, i8 37                  ; D8–D13 PORTB=0x25
]
@__pin_mask = private constant [14 x i8] [
    i8  1, i8  2, i8  4, i8  8, i8 16, i8 32, i8 64, i8 -128, ; D0–D7
    i8  1, i8  2, i8  4, i8  8, i8 16, i8  32                  ; D8–D13
]

define void @__bytelight_gpio_pinmode(i32 %pin, i32 %mode) {
entry:
  %ddr_idx = getelementptr [14 x i8], ptr @__pin_ddr, i32 0, i32 %pin
  %ddr_byte = load i8, ptr %ddr_idx
  %ddr_addr16 = zext i8 %ddr_byte to i16
  %ddr_addr = inttoptr i16 %ddr_addr16 to ptr
  %mask_idx = getelementptr [14 x i8], ptr @__pin_mask, i32 0, i32 %pin
  %mask = load i8, ptr %mask_idx
  %cur = load volatile i8, ptr %ddr_addr
  %is_out = icmp ne i32 %mode, 0
  br i1 %is_out, label %set_out, label %set_in
set_out:
  %new_out = or i8 %cur, %mask
  store volatile i8 %new_out, ptr %ddr_addr
  ret void
set_in:
  %inv = xor i8 %mask, -1
  %new_in = and i8 %cur, %inv
  store volatile i8 %new_in, ptr %ddr_addr
  ret void
}

define void @__bytelight_gpio_digitalwrite(i32 %pin, i32 %value) {
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

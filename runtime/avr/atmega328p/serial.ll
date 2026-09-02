; ByteLight AVR runtime — USART0 Serial (ATmega328P)
;
; ATmega328P USART0 register map (extended I/O space, direct memory addresses):
;   UCSR0A = 0xC0 = 192  bit 5 = UDRE0: TX buffer empty (ready to send)
;   UCSR0B = 0xC1 = 193  bit 4 = RXEN0, bit 3 = TXEN0
;   UCSR0C = 0xC2 = 194  bits 2:1 = UCSZ0[1:0] (8-bit frame = 0x06)
;   UBRR0L = 0xC4 = 196  baud-rate register low byte
;   UBRR0H = 0xC5 = 197  baud-rate register high byte
;   UDR0   = 0xC6 = 198  TX/RX data register
;
; Serial.begin() with a compile-time constant baud rate is fully inlined by
; AvrIntrinsics (no runtime call).  This fallback initialises USART0 for
; 9600 baud at 16 MHz (UBRR = 103) so programs that call begin() with a
; non-constant argument still get a working serial port.

define void @__bytelight_serial_begin(i32 %baud) {
entry:
  store volatile i8 0,   ptr inttoptr (i16 197 to ptr)  ; UBRR0H = 0
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)  ; UBRR0L = 103 (9600 @ 16 MHz)
  store volatile i8 24,  ptr inttoptr (i16 193 to ptr)  ; UCSR0B = RXEN0|TXEN0
  store volatile i8 6,   ptr inttoptr (i16 194 to ptr)  ; UCSR0C = 8N1
  ret void
}

; Busy-wait until the TX buffer is empty, then send one byte.
define void @__bytelight_serial_write(i32 %b) {
entry:
  br label %wait
wait:
  %sr    = load volatile i8, ptr inttoptr (i16 192 to ptr)  ; UCSR0A
  %udre  = and i8 %sr, 32                                    ; UDRE0 = bit 5
  %ready = icmp ne i8 %udre, 0
  br i1 %ready, label %send, label %wait
send:
  %b8 = trunc i32 %b to i8
  store volatile i8 %b8, ptr inttoptr (i16 198 to ptr)      ; UDR0
  ret void
}

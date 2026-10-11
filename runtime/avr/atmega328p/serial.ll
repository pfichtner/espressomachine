; EspressoMachine AVR runtime — USART0 Serial (ATmega328P)
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

declare i32 @__udivsi3(i32 %dividend, i32 %divisor)
declare i32 @__umodsi3(i32 %dividend, i32 %divisor)

define void @__espressomachine_serial_begin(i32 %baud) {
entry:
  store volatile i8 0,   ptr inttoptr (i16 197 to ptr)  ; UBRR0H = 0
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)  ; UBRR0L = 103 (9600 @ 16 MHz)
  store volatile i8 24,  ptr inttoptr (i16 193 to ptr)  ; UCSR0B = RXEN0|TXEN0
  store volatile i8 6,   ptr inttoptr (i16 194 to ptr)  ; UCSR0C = 8N1
  ret void
}

; Busy-wait until the TX buffer is empty, then send one byte.
define void @__espressomachine_serial_write(i32 %b) {
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

; Transmit a null-terminated byte string held in AVR program space (flash).
; The pointer is an addrspace(1) pointer, so each load lowers to `lpm`; the
; data address space (addrspace 0) only covers SRAM and cannot see string data.
define void @__espressomachine_serial_print_str(ptr addrspace(1) %s) {
entry:
  %p1 = getelementptr i8, ptr addrspace(1) %s, i32 0
  %c1 = load i8, ptr addrspace(1) %p1
  %z1 = icmp eq i8 %c1, 0
  br i1 %z1, label %done, label %loop_entry
loop_entry:
  br label %loop
loop:
  %p   = phi ptr addrspace(1) [ %p1, %loop_entry ], [ %pnext, %loop ]
  %c   = phi i8  [ %c1, %loop_entry ], [ %cnext, %loop ]
  %cu  = zext i8 %c to i32
  call void @__espressomachine_serial_write(i32 %cu)
  %pnext = getelementptr i8, ptr addrspace(1) %p, i32 1
  %cnext = load i8, ptr addrspace(1) %pnext
  %znext = icmp eq i8 %cnext, 0
  br i1 %znext, label %done, label %loop
done:
  ret void
}

; Transmit a signed 32-bit integer in decimal (most-significant digit first),
; recursing on the (unsigned) quotient so digits are emitted in order.
define void @__espressomachine_serial_print_int(i32 %n) {
entry:
  %neg = icmp slt i32 %n, 0
  br i1 %neg, label %negblock, label %body

negblock:
  call void @__espressomachine_serial_write(i32 45)   ; '-'
  %u = sub i32 0, %n
  br label %body

body:
  %v = phi i32 [ %n, %entry ], [ %u, %negblock ]
  %lt10 = icmp ult i32 %v, 10
  br i1 %lt10, label %last, label %recurse

recurse:
  %q = call i32 @__udivsi3(i32 %v, i32 10)
  call void @__espressomachine_serial_print_int(i32 %q)
  %r = call i32 @__umodsi3(i32 %v, i32 10)
  %dr = add i32 %r, 48
  call void @__espressomachine_serial_write(i32 %dr)
  ret void

last:
  %dl = add i32 %v, 48
  call void @__espressomachine_serial_write(i32 %dl)
  ret void
}

; Transmit an unsigned 32-bit integer in decimal. Used by Serial.printf("%u").
define void @__espressomachine_serial_print_uint(i32 %n) {
entry:
  %lt10 = icmp ult i32 %n, 10
  br i1 %lt10, label %last, label %recurse

recurse:
  %q = call i32 @__udivsi3(i32 %n, i32 10)
  call void @__espressomachine_serial_print_uint(i32 %q)
  %r = call i32 @__umodsi3(i32 %n, i32 10)
  %dr = add i32 %r, 48
  call void @__espressomachine_serial_write(i32 %dr)
  ret void

last:
  %dl = add i32 %n, 48
  call void @__espressomachine_serial_write(i32 %dl)
  ret void
}

; Transmit an unsigned 32-bit integer in the given radix (2..16). Used by
; Serial.printf("%x"/"%X"/"%b"). %upper is non-zero to emit upper-case hex.
define void @__espressomachine_serial_print_radix(i32 %n, i32 %radix, i32 %upper) {
entry:
  %lt = icmp ult i32 %n, %radix
  br i1 %lt, label %last, label %recurse

recurse:
  %q = call i32 @__udivsi3(i32 %n, i32 %radix)
  call void @__espressomachine_serial_print_radix(i32 %q, i32 %radix, i32 %upper)
  %r = call i32 @__umodsi3(i32 %n, i32 %radix)
  call void @__espressomachine_serial_radix_digit(i32 %r, i32 %upper)
  ret void

last:
  call void @__espressomachine_serial_radix_digit(i32 %n, i32 %upper)
  ret void
}

; Transmit one digit value (0..15) as '0'-'9' or 'a'-'f'/'A'-'F'.
define void @__espressomachine_serial_radix_digit(i32 %d, i32 %upper) {
entry:
  %lt10 = icmp ult i32 %d, 10
  br i1 %lt10, label %num, label %alpha

num:
  %c0 = add i32 %d, 48
  call void @__espressomachine_serial_write(i32 %c0)
  ret void

alpha:
  %isup = icmp ne i32 %upper, 0
  %off = select i1 %isup, i32 55, i32 87   ; 'A'-10 = 55, 'a'-10 = 87
  %c1 = add i32 %d, %off
  call void @__espressomachine_serial_write(i32 %c1)
  ret void
}


; TinyJava AVR runtime — delay (ATmega328P @ 16 MHz)
;
; __tinyjava_delay_ms(i32 %ms):
;   Each iteration of the inner loop = 4 cycles at 16 MHz = 0.25 µs.
;   We need 16,000 cycles per millisecond.
;   Inner loop: 4 cycles → 4000 iterations per ms.

define void @__tinyjava_delay_ms(i32 %ms) {
entry:
  %zero = icmp eq i32 %ms, 0
  br i1 %zero, label %done, label %outer_loop

outer_loop:
  %ms_rem = phi i32 [ %ms, %entry ], [ %ms_next, %outer_loop_tail ]
  ; Inner loop: 4000 iterations ≈ 1 ms at 16 MHz (4 cycles/iter)
  br label %inner_loop

inner_loop:
  %count = phi i32 [ 4000, %outer_loop ], [ %count_next, %inner_loop ]
  %count_next = sub i32 %count, 1
  %inner_done = icmp eq i32 %count_next, 0
  br i1 %inner_done, label %outer_loop_tail, label %inner_loop

outer_loop_tail:
  %ms_next = sub i32 %ms_rem, 1
  %outer_done = icmp eq i32 %ms_next, 0
  br i1 %outer_done, label %done, label %outer_loop

done:
  ret void
}

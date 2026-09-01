; TinyJava AVR runtime — delay template
;
; __tinyjava_delay_ms(i32 %ms):
;   Inner loop body = 4 AVR cycles.
;   Iterations per ms = F_CPU / 4 / 1000  (set by build.sh via __DELAY_ITERS__).
;
;   ATmega328P @ 16 MHz → 4000 iterations/ms
;   ATmega328P @  8 MHz → 2000 iterations/ms

define void @__tinyjava_delay_ms(i32 %ms) {
entry:
  %zero = icmp eq i32 %ms, 0
  br i1 %zero, label %done, label %outer_loop

outer_loop:
  %ms_rem = phi i32 [ %ms, %entry ], [ %ms_next, %outer_loop_tail ]
  ; Inner loop: __DELAY_ITERS__ iterations ≈ 1 ms (4 cycles/iter at target F_CPU)
  br label %inner_loop

inner_loop:
  %count = phi i32 [ __DELAY_ITERS__, %outer_loop ], [ %count_next, %inner_loop ]
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

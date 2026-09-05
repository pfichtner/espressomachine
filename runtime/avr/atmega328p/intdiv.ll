; EspressoMachine AVR runtime — integer multiply/divide helpers
;
; LLVM's AVR backend emits calls to these GCC-ABI names when it encounters
; mul i32 / sdiv i32 / srem i32 instructions:
;
;   __mulsi3    — 32-bit signed/unsigned multiply (lower 32 bits of product)
;   __divmodsi4 — 32-bit signed divide + modulo combined
;
; All implementations use only shift/add/sub/compare — operations LLVM's AVR
; backend expands inline — so these functions themselves need no further helpers.

; ---------------------------------------------------------------------------
; Unsigned 32-bit division — quotient (called internally by __divmodsi4).
; Bit-serial restoring long-division, 32 iterations.
; ---------------------------------------------------------------------------
define i32 @__udivsi3(i32 %dividend, i32 %divisor) {
entry:
  br label %loop

loop:
  %i      = phi i32 [ 31, %entry ], [ %i_next, %loop ]
  %q      = phi i32 [ 0,  %entry ], [ %q_next, %loop ]
  %r      = phi i32 [ 0,  %entry ], [ %r_next, %loop ]

  %r_shl  = shl i32 %r, 1
  %dbit   = lshr i32 %dividend, %i
  %bit    = and i32 %dbit, 1
  %r1     = or i32 %r_shl, %bit

  %ge     = icmp uge i32 %r1, %divisor
  %r_sub  = sub i32 %r1, %divisor
  %r_next = select i1 %ge, i32 %r_sub, i32 %r1
  %mask   = shl i32 1, %i
  %q_or   = or i32 %q, %mask
  %q_next = select i1 %ge, i32 %q_or, i32 %q

  %done   = icmp eq i32 %i, 0
  %i_next = sub i32 %i, 1
  br i1 %done, label %exit, label %loop

exit:
  ret i32 %q_next
}

; ---------------------------------------------------------------------------
; Unsigned 32-bit modulo — remainder (called internally by __divmodsi4).
; ---------------------------------------------------------------------------
define i32 @__umodsi3(i32 %dividend, i32 %divisor) {
entry:
  br label %loop

loop:
  %i      = phi i32 [ 31, %entry ], [ %i_next, %loop ]
  %r      = phi i32 [ 0,  %entry ], [ %r_next, %loop ]

  %r_shl  = shl i32 %r, 1
  %dbit   = lshr i32 %dividend, %i
  %bit    = and i32 %dbit, 1
  %r1     = or i32 %r_shl, %bit

  %ge     = icmp uge i32 %r1, %divisor
  %r_sub  = sub i32 %r1, %divisor
  %r_next = select i1 %ge, i32 %r_sub, i32 %r1

  %done   = icmp eq i32 %i, 0
  %i_next = sub i32 %i, 1
  br i1 %done, label %exit, label %loop

exit:
  ret i32 %r_next
}

; ---------------------------------------------------------------------------
; 32-bit multiply — lower 32 bits of the product.
; Shift-and-add: for each bit of b (LSB first), add a to result if the bit
; is set, then shift a left.  Two's-complement wrapping makes this correct
; for both signed and unsigned inputs.
; ---------------------------------------------------------------------------
define i32 @__mulsi3(i32 %a, i32 %b) {
entry:
  br label %loop

loop:
  %a_cur  = phi i32 [ %a, %entry ], [ %a_shl,  %loop ]
  %b_cur  = phi i32 [ %b, %entry ], [ %b_shr,  %loop ]
  %r_cur  = phi i32 [ 0,  %entry ], [ %r_next, %loop ]

  %lsb    = and i32 %b_cur, 1
  %cond   = icmp ne i32 %lsb, 0
  %r_add  = add i32 %r_cur, %a_cur
  %r_next = select i1 %cond, i32 %r_add, i32 %r_cur
  %a_shl  = shl i32 %a_cur, 1
  %b_shr  = lshr i32 %b_cur, 1

  %done   = icmp eq i32 %b_shr, 0
  br i1 %done, label %exit, label %loop

exit:
  ret i32 %r_next
}

; ---------------------------------------------------------------------------
; Signed 32-bit divide + modulo combined.
; LLVM's AVR backend emits calls to this name (GCC ABI) for sdiv/srem i32.
; Returns { quotient, remainder } — LLVM reads quotient from element 0.
; Sign rule: quotient negative iff operands have opposite signs;
;            remainder sign follows dividend.
; ---------------------------------------------------------------------------
define { i32, i32 } @__divmodsi4(i32 %a, i32 %b) {
entry:
  ; Two's-complement abs: abs(x) = (x XOR sign) - sign, sign = ashr x, 31
  %sa     = ashr i32 %a, 31
  %sb     = ashr i32 %b, 31
  %a_xor  = xor i32 %a, %sa
  %abs_a  = sub i32 %a_xor, %sa
  %b_xor  = xor i32 %b, %sb
  %abs_b  = sub i32 %b_xor, %sb

  %q_raw  = call i32 @__udivsi3(i32 %abs_a, i32 %abs_b)
  %r_raw  = call i32 @__umodsi3(i32 %abs_a, i32 %abs_b)

  %sign   = xor i32 %sa, %sb
  %q_xor  = xor i32 %q_raw, %sign
  %q      = sub i32 %q_xor, %sign

  %r_xor  = xor i32 %r_raw, %sa
  %r      = sub i32 %r_xor, %sa

  %ret0   = insertvalue { i32, i32 } undef, i32 %q, 0
  %ret1   = insertvalue { i32, i32 } %ret0, i32 %r, 1
  ret { i32, i32 } %ret1
}

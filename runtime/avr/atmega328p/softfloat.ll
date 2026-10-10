; EspressoMachine AVR runtime — IEEE-754 single-precision (binary32) software floating point.
;
; The AVR target has no floating-point instructions, so LLVM lowers FP ops to
; calls with GCC-ABI names:
;
;   sitofp i32 -> float   -> __floatsisf(i32)
;   fptosi float -> i32   -> __fixsfsi(i32)         ; arg is the float bit pattern
;   fadd / fsub           -> __addsf3 / __subsf3
;   fmul / fdiv           -> __mulsf3 / __divsf3
;   fcmp  < <= > >= == != -> __ltsf2 / __lesf2 / __gtsf2 / __gesf2 / __eqsf2 / __nesf2
;
; These functions operate on the raw 32-bit bit pattern using only integer
; arithmetic, so llc can compile this module without emitting further float
; libcalls.  The AVR ABI passes 32-bit scalars in r22-r25 in both directions,
; so a function declared as `float` on the call side links fine against these
; `i32` definitions.
;
; Bit layout (binary32): sign(31) | biased exponent(30..23) | fraction(22..0).
; Internally normal numbers use
;
;     value = sig * 2^(e - 150),   sig = 0x800000 | fraction
;
; Design notes / documented limitations:
;   * results are rounded toward zero (truncated); no guard/round/sticky bits
;   * subnormal inputs are normalized; underflow flushes to (signed) zero
;   * exponent overflow clamps to +-Inf; a NaN input propagates 0x7FC00000
;   * comparisons follow the libgcc __*.sf2 ABI (NaN = unordered)

; ---------------------------------------------------------------------------
; Internal helpers
; ---------------------------------------------------------------------------

; Index (0..31) of the most significant set bit of %x (%x must be non-zero).
define internal i32 @_sf_msb(i32 %x) {
entry:
  %x0 = icmp eq i32 %x, 0
  br i1 %x0, label %zero, label %loop

zero:
  ret i32 0

loop:
  %i    = phi i32 [ 31, %entry ], [ %in, %notyet ]
  %bit1 = lshr i32 %x, %i
  %bitd = and i32 %bit1, 1
  %yes  = icmp ne i32 %bitd, 0
  br i1 %yes, label %done, label %notyet

notyet:
  %in = sub i32 %i, 1
  br label %loop

done:
  ret i32 %i
}

; Decompose a float bit pattern into the (sig, exponent-field) convention.
; Subnormals (exponent field 0, non-zero fraction) are normalized; pure zero
; returns (sig = 0, e = 0).
define internal { i32, i32 } @_sf_norm(i32 %b) {
entry:
  %expf = and i32 %b, 2139095040                ; 0x7F800000
  %e    = lshr i32 %expf, 23
  %f    = and i32 %b, 8388607                   ; 0x007FFFFF
  %ez   = icmp eq i32 %e, 0
  br i1 %ez, label %sub, label %normal

sub:
  %fz = icmp eq i32 %f, 0
  br i1 %fz, label %zero, label %subnorm

zero:
  %z0 = insertvalue { i32, i32 } undef, i32 0, 0
  %z1 = insertvalue { i32, i32 } %z0, i32 0, 1
  ret { i32, i32 } %z1

subnorm:
  %p  = call i32 @_sf_msb(i32 %f)
  %sh = sub i32 23, %p
  %sg = shl i32 %f, %sh
  %e2 = sub i32 %p, 22
  %s0 = insertvalue { i32, i32 } undef, i32 %sg, 0
  %s1 = insertvalue { i32, i32 } %s0, i32 %e2, 1
  ret { i32, i32 } %s1

normal:
  %sgn = or i32 %f, 8388608                     ; 0x00800000
  %n0  = insertvalue { i32, i32 } undef, i32 %sgn, 0
  %n1  = insertvalue { i32, i32 } %n0, i32 %e, 1
  ret { i32, i32 } %n1
}

; Re-normalize a non-zero 32-bit integer %s to most-significant bit 23,
; adjusting the exponent field %e to preserve value = %s * 2^(%e - 150).
; Returns (fraction, exponent field).
define internal { i32, i32 } @_sf_norm_sig(i32 %s, i32 %e) {
entry:
  %p   = call i32 @_sf_msb(i32 %s)
  %peq = icmp eq i32 %p, 23
  br i1 %peq, label %done, label %adj

adj:
  %ge = icmp sge i32 %p, 24
  br i1 %ge, label %right, label %left

right:
  %sr = sub i32 %p, 23
  %s1 = lshr i32 %s, %sr
  %e1 = add i32 %e, %sr
  %f1 = and i32 %s1, 8388607
  %r0 = insertvalue { i32, i32 } undef, i32 %f1, 0
  %r1 = insertvalue { i32, i32 } %r0, i32 %e1, 1
  ret { i32, i32 } %r1

left:
  %sl = sub i32 23, %p
  %s2 = shl i32 %s, %sl
  %e2 = sub i32 %e, %sl
  %f2 = and i32 %s2, 8388607
  %l0 = insertvalue { i32, i32 } undef, i32 %f2, 0
  %l1 = insertvalue { i32, i32 } %l0, i32 %e2, 1
  ret { i32, i32 } %l1

done:
  %fd = and i32 %s, 8388607
  %d0 = insertvalue { i32, i32 } undef, i32 %fd, 0
  %d1 = insertvalue { i32, i32 } %d0, i32 %e, 1
  ret { i32, i32 } %d1
}

; Assemble (sign bit, exponent field, 23-bit fraction) into a float pattern.
; Underflow (e < 1) flushes to (signed) zero; overflow (e >= 255) clamps to +-Inf.
define internal i32 @_sf_pack(i32 %sgn, i32 %e, i32 %f) {
entry:
  %under = icmp slt i32 %e, 1
  br i1 %under, label %uflow, label %ovrchk

uflow:
  ret i32 %sgn

ovrchk:
  %ov = icmp sge i32 %e, 255
  br i1 %ov, label %inf, label %ok

inf:
  %infb = or i32 %sgn, 2139095040              ; 0x7F800000
  ret i32 %infb

ok:
  %ef = shl i32 %e, 23
  %fb = or i32 %ef, %f
  %r  = or i32 %sgn, %fb
  ret i32 %r
}

; Unsigned 48-bit / 24-bit division (truncated), N = %nhi * 2^32 + %nlo,
; quotient returned.  %dv must be non-zero.  Bit-serial restoring algorithm,
; 48 iterations, feeding dividend bits MSB-first.  For normalized divisors
; (>= 2^23) the true quotient is < 2^25, so high quotient bits never matter.
define internal i32 @_sf_div48(i32 %nhi, i32 %nlo, i32 %dv) {
entry:
  br label %loop

loop:
  %i = phi i32 [ 47, %entry ], [ %in, %cont ]
  %r = phi i32 [ 0,  %entry ], [ %rn, %cont ]
  %q = phi i32 [ 0,  %entry ], [ %qn, %cont ]

  %ish  = icmp sge i32 %i, 32
  br i1 %ish, label %hibit, label %lobit

hibit:
  %i32_  = sub i32 %i, 32
  %hb1   = lshr i32 %nhi, %i32_
  %hb    = and i32 %hb1, 1
  br label %havebit

lobit:
  %lb1 = lshr i32 %nlo, %i
  %lb  = and i32 %lb1, 1
  br label %havebit

havebit:
  %bit = phi i32 [ %hb, %hibit ], [ %lb, %lobit ]
  %r2  = shl i32 %r, 1
  %r3  = or i32 %r2, %bit
  %ge  = icmp uge i32 %r3, %dv
  br i1 %ge, label %subtract, label %skipq

subtract:
  %r4     = sub i32 %r3, %dv
  %qok    = icmp slt i32 %i, 32
  %isham  = select i1 %qok, i32 %i, i32 0
  %qbit   = shl i32 1, %isham
  %qmsk   = select i1 %qok, i32 %qbit, i32 0
  %q2     = or i32 %q, %qmsk
  br label %cont

skipq:
  br label %cont

cont:
  %rn   = phi i32 [ %r4, %subtract ], [ %r3, %skipq ]
  %qn   = phi i32 [ %q2, %subtract ], [ %q,  %skipq ]
  %done = icmp eq i32 %i, 0
  %in   = sub i32 %i, 1
  %qfin = select i1 %done, i32 %qn, i32 0
  br i1 %done, label %exit, label %loop

exit:
  ret i32 %qfin
}

; ---------------------------------------------------------------------------
; 48-bit shift helpers used by the addition/subtraction core.  A 48-bit value
; is carried as (hi, lo); `hi` holds bits 47..0's upper word (bits 32..47 are
; the only ones ever set) and `lo` holds bits 31..0.
; ---------------------------------------------------------------------------

define internal { i32, i32 } @_sf_shl48(i32 %hi, i32 %lo, i32 %m) {
entry:
  %z = icmp eq i32 %m, 0
  br i1 %z, label %same, label %shift

same:
  %a0 = insertvalue { i32, i32 } undef, i32 %hi, 0
  %a1 = insertvalue { i32, i32 } %a0, i32 %lo, 1
  ret { i32, i32 } %a1

shift:
  %inv  = sub i32 32, %m
  %lo2  = shl i32 %lo, %m
  %hi2a = shl i32 %hi, %m
  %hi2b = lshr i32 %lo, %inv
  %hi2  = or i32 %hi2a, %hi2b
  %b0 = insertvalue { i32, i32 } undef, i32 %hi2, 0
  %b1 = insertvalue { i32, i32 } %b0, i32 %lo2, 1
  ret { i32, i32 } %b1
}

define internal { i32, i32 } @_sf_shr48(i32 %hi, i32 %lo, i32 %n) {
entry:
  %z = icmp eq i32 %n, 0
  br i1 %z, label %same, label %shift

same:
  %a0 = insertvalue { i32, i32 } undef, i32 %hi, 0
  %a1 = insertvalue { i32, i32 } %a0, i32 %lo, 1
  ret { i32, i32 } %a1

shift:
  %ge32 = icmp sge i32 %n, 32
  br i1 %ge32, label %high, label %low

low:
  %inv  = sub i32 32, %n
  %hi2  = lshr i32 %hi, %n
  %lo2a = lshr i32 %lo, %n
  %lo2b = shl i32 %hi, %inv
  %lo2  = or i32 %lo2a, %lo2b
  %c0 = insertvalue { i32, i32 } undef, i32 %hi2, 0
  %c1 = insertvalue { i32, i32 } %c0, i32 %lo2, 1
  ret { i32, i32 } %c1

high:
  %d   = sub i32 %n, 32
  %lo3 = lshr i32 %hi, %d
  %d0 = insertvalue { i32, i32 } undef, i32 0, 0
  %d1 = insertvalue { i32, i32 } %d0, i32 %lo3, 1
  ret { i32, i32 } %d1
}

; ---------------------------------------------------------------------------
; float addition / subtraction
; ---------------------------------------------------------------------------

define i32 @__addsf3(i32 %a, i32 %b) {
entry:
  %aexpf = and i32 %a, 2139095040
  %bexpf = and i32 %b, 2139095040
  %ae    = lshr i32 %aexpf, 23
  %be    = lshr i32 %bexpf, 23
  %ae255 = icmp eq i32 %ae, 255
  %be255 = icmp eq i32 %be, 255
  br i1 %ae255, label %ainf, label %bcheck

bcheck:
  br i1 %be255, label %binf, label %main

ainf:
  %afr = and i32 %a, 8388607
  %afz = icmp eq i32 %afr, 0
  br i1 %afz, label %a_isinf, label %nan

a_isinf:
  br i1 %be255, label %a_inf_binf, label %ret_a

a_inf_binf:
  %asgn0 = and i32 %a, 2147483648
  %bsgn0 = and i32 %b, 2147483648
  %sdiff = icmp ne i32 %asgn0, %bsgn0
  br i1 %sdiff, label %nan, label %ret_a

ret_a:
  ret i32 %a

binf:
  %bfr = and i32 %b, 8388607
  %bfz = icmp eq i32 %bfr, 0
  br i1 %bfz, label %ret_b, label %nan

ret_b:
  ret i32 %b

nan:
  ret i32 2143289344                           ; 0x7FC00000 (quiet NaN)

main:
  %an   = call { i32, i32 } @_sf_norm(i32 %a)
  %asg  = extractvalue { i32, i32 } %an, 0
  %aee  = extractvalue { i32, i32 } %an, 1
  %bn   = call { i32, i32 } @_sf_norm(i32 %b)
  %bsg  = extractvalue { i32, i32 } %bn, 0
  %bee  = extractvalue { i32, i32 } %bn, 1
  %asgn = and i32 %a, 2147483648
  %bsgn = and i32 %b, 2147483648

  %az = icmp eq i32 %asg, 0
  br i1 %az, label %a_zero, label %bzero_chk

a_zero:
  %bmag = and i32 %b, 2147483647
  %bnorm = icmp ugt i32 %bmag, 8388607
  br i1 %bnorm, label %az_ret, label %az_small

az_ret:
  ret i32 %b

az_small:
  %bzm = icmp eq i32 %bmag, 0
  %bsg2 = and i32 %b, 2147483648
  %azr = select i1 %bzm, i32 0, i32 %bsg2
  ret i32 %azr

bzero_chk:
  %bz = icmp eq i32 %bsg, 0
  br i1 %bz, label %b_zero, label %order

b_zero:
  %amag = and i32 %a, 2147483647
  %anorm = icmp ugt i32 %amag, 8388607
  br i1 %anorm, label %bz_ret, label %bz_small

bz_ret:
  ret i32 %a

bz_small:
  %asg2 = and i32 %a, 2147483648
  ret i32 %asg2

order:
  %g1 = icmp sgt i32 %aee, %bee
  br i1 %g1, label %a_big, label %cmp_eq

cmp_eq:
  %eeq = icmp eq i32 %aee, %bee
  br i1 %eeq, label %cmp_sig, label %b_big

cmp_sig:
  %sgt = icmp ugt i32 %asg, %bsg
  br i1 %sgt, label %a_big, label %b_big

a_big:
  br label %aligned

b_big:
  br label %aligned

aligned:
  %Lsgn = phi i32 [ %asgn, %a_big ], [ %bsgn, %b_big ]
  %Lsg  = phi i32 [ %asg,  %a_big ], [ %bsg,  %b_big ]
  %Le   = phi i32 [ %aee,  %a_big ], [ %bee,  %b_big ]
  %Ssgn = phi i32 [ %bsgn, %a_big ], [ %asgn, %b_big ]
  %Ssg  = phi i32 [ %bsg,  %a_big ], [ %asg,  %b_big ]
  %Se   = phi i32 [ %bee,  %a_big ], [ %aee,  %b_big ]

  ; Fixed-point accumulation with 24 guard bits.  The larger operand becomes
  ; A = Lsg << 24 (Ahi = Lsg, Alo = 0).  The smaller operand is placed in the
  ; same 2^-24 grid:
  ;   diff = Le - Se
  ;     diff <= 24 -> S = Ssg << (24 - diff)
  ;     diff >  24 -> S = Ssg >> (diff - 24)
  %diff = sub i32 %Le, %Se
  %le24 = icmp sle i32 %diff, 24
  br i1 %le24, label %s_left, label %s_right

s_left:
  %sll = sub i32 24, %diff
  %sl  = call { i32, i32 } @_sf_shl48(i32 0, i32 %Ssg, i32 %sll)
  %Sl_hi = extractvalue { i32, i32 } %sl, 0
  %Sl_lo = extractvalue { i32, i32 } %sl, 1
  br label %aligned_ready

s_right:
  %srr = sub i32 %diff, 24
  %cap = icmp sge i32 %srr, 48
  %srv = select i1 %cap, i32 48, i32 %srr
  %sr  = call { i32, i32 } @_sf_shr48(i32 0, i32 %Ssg, i32 %srv)
  %Sr_hi = extractvalue { i32, i32 } %sr, 0
  %Sr_lo = extractvalue { i32, i32 } %sr, 1
  ; sticky: any non-zero bit dropped from Ssg.  On the subtract path this
  ; forces a borrow into the significand LSB so the result truncates toward
  ; zero (matching an exact rational reference).
  %srrm = and i32 %srr, 31
  %one_s = shl i32 1, %srrm
  %mask_s = sub i32 %one_s, 1
  %low_s = and i32 %Ssg, %mask_s
  %wrap = icmp sge i32 %srr, 32
  %sbits = select i1 %wrap, i32 %Ssg, i32 %low_s
  %sbnz = icmp ne i32 %sbits, 0
  %stk_r = zext i1 %sbnz to i32
  br label %aligned_ready

aligned_ready:
  %Shi = phi i32 [ %Sl_hi, %s_left ], [ %Sr_hi, %s_right ]
  %Slo = phi i32 [ %Sl_lo, %s_left ], [ %Sr_lo, %s_right ]
  %Stk = phi i32 [ 0, %s_left ], [ %stk_r, %s_right ]
  %same = icmp eq i32 %Lsgn, %Ssgn
  br i1 %same, label %sum48, label %dif48

; 48-bit A + S, where A = Lsg << 24 = (Lsg >> 8, Lsg << 24)
sum48:
  %Asum_hi = lshr i32 %Lsg, 8
  %Asum_lo = shl i32 %Lsg, 24
  %sum_lo  = add i32 %Asum_lo, %Slo
  %sum_c = icmp ult i32 %sum_lo, %Asum_lo
  %sum_cz = zext i1 %sum_c to i32
  %sum_hi0 = add i32 %Asum_hi, %Shi
  %sum_hi = add i32 %sum_hi0, %sum_cz
  br label %have_r

; 48-bit A - S, where A = Lsg << 24 and A >= S in magnitude
dif48:
  %Adif_hi = lshr i32 %Lsg, 8
  %Adif_lo = shl i32 %Lsg, 24
  %Slo_e = add i32 %Slo, %Stk
  %dif_b0 = icmp ult i32 %Adif_lo, %Slo_e
  %dif_b  = zext i1 %dif_b0 to i32
  %dif_lo = sub i32 %Adif_lo, %Slo_e
  %dif_hi0 = sub i32 %Adif_hi, %Shi
  %dif_hi = sub i32 %dif_hi0, %dif_b
  br label %have_r

have_r:
  %Rhi = phi i32 [ %sum_hi, %sum48 ], [ %dif_hi, %dif48 ]
  %Rlo = phi i32 [ %sum_lo, %sum48 ], [ %dif_lo, %dif48 ]
  %rz0 = icmp eq i32 %Rhi, 0
  %rz1 = icmp eq i32 %Rlo, 0
  %rz  = and i1 %rz0, %rz1
  br i1 %rz, label %ret_zero, label %find_p

ret_zero:
  ret i32 0

find_p:
  %hz = icmp eq i32 %Rhi, 0
  br i1 %hz, label %use_lo, label %use_hi

use_hi:
  %phs = call i32 @_sf_msb(i32 %Rhi)
  %ph  = add i32 %phs, 32
  br label %have_p

use_lo:
  %pls = call i32 @_sf_msb(i32 %Rlo)
  br label %have_p

have_p:
  ; value = R * 2^(Le - 174); msb at bit p -> biased exp = Le - 47 + p
  %p    = phi i32 [ %ph, %use_hi ], [ %pls, %use_lo ]
  %ge23 = icmp sge i32 %p, 23
  br i1 %ge23, label %shr_p, label %shl_p

shr_p:
  %d  = sub i32 %p, 23
  %sr2 = call { i32, i32 } @_sf_shr48(i32 %Rhi, i32 %Rlo, i32 %d)
  %fr_hi = extractvalue { i32, i32 } %sr2, 0
  %fr_lo = extractvalue { i32, i32 } %sr2, 1
  br label %packed

shl_p:
  %l   = sub i32 23, %p
  %sl2 = call { i32, i32 } @_sf_shl48(i32 %Rhi, i32 %Rlo, i32 %l)
  %fl_hi = extractvalue { i32, i32 } %sl2, 0
  %fl_lo = extractvalue { i32, i32 } %sl2, 1
  br label %packed

packed:
  %flo  = phi i32 [ %fr_lo, %shr_p ], [ %fl_lo, %shl_p ]
  %frac = and i32 %flo, 8388607
  %ep   = add i32 %Le, %p
  %e    = sub i32 %ep, 47
  %res  = call i32 @_sf_pack(i32 %Lsgn, i32 %e, i32 %frac)
  ret i32 %res
}

define i32 @__subsf3(i32 %a, i32 %b) {
  %bn = xor i32 %b, 2147483648
  %r  = call i32 @__addsf3(i32 %a, i32 %bn)
  ret i32 %r
}

; ---------------------------------------------------------------------------
; float multiplication
; ---------------------------------------------------------------------------

define i32 @__mulsf3(i32 %a, i32 %b) {
entry:
  %aexpf = and i32 %a, 2139095040
  %bexpf = and i32 %b, 2139095040
  %ae    = lshr i32 %aexpf, 23
  %be    = lshr i32 %bexpf, 23
  %ae255 = icmp eq i32 %ae, 255
  %be255 = icmp eq i32 %be, 255
  %sgn   = xor i32 %a, %b
  %sgnb  = and i32 %sgn, 2147483648
  %any255 = or i1 %ae255, %be255
  br i1 %any255, label %special, label %body

special:
  %afr = and i32 %a, 8388607
  %bfr = and i32 %b, 8388607
  %aNf = icmp ne i32 %afr, 0
  %bNf = icmp ne i32 %bfr, 0
  %aN  = and i1 %ae255, %aNf
  %bN  = and i1 %be255, %bNf
  %anyN = or i1 %aN, %bN
  br i1 %anyN, label %nan, label %inf_or_zero

nan:
  ret i32 2143289344

inf_or_zero:
  ; here an operand has exponent 255 and no NaN payload
  %az0   = icmp eq i32 %ae, 0
  %afz   = icmp eq i32 %afr, 0
  %azero = and i1 %az0, %afz
  %bz0   = icmp eq i32 %be, 0
  %bfz   = icmp eq i32 %bfr, 0
  %bzero = and i1 %bz0, %bfz
  ; inf * 0 -> NaN ; inf * inf -> Inf ; inf * finite -> Inf
  %aInfz = and i1 %ae255, %bzero
  %bInfz = and i1 %be255, %azero
  %infzero = or i1 %aInfz, %bInfz
  br i1 %infzero, label %nan, label %m_inf

m_inf:
  %infb = or i32 %sgnb, 2139095040
  ret i32 %infb

body:
  %an   = call { i32, i32 } @_sf_norm(i32 %a)
  %asg  = extractvalue { i32, i32 } %an, 0
  %aee  = extractvalue { i32, i32 } %an, 1
  %bn   = call { i32, i32 } @_sf_norm(i32 %b)
  %bsg  = extractvalue { i32, i32 } %bn, 0
  %bee  = extractvalue { i32, i32 } %bn, 1

  %az2 = icmp eq i32 %asg, 0
  %bz2 = icmp eq i32 %bsg, 0
  %z2  = or i1 %az2, %bz2
  br i1 %z2, label %zero_res, label %muloop_head

zero_res:
  ret i32 %sgnb

muloop_head:
  ; 48-bit multiplicand m = sig_a held as (mhi = bits 47..32, mlo = bits 31..0)
  %aho0 = xor i32 %asg, %asg
  %alo0 = shl i32 %asg, 0
  br label %muloop

muloop:
  %i     = phi i32 [ 0,  %muloop_head ], [ %in,  %muloop ]
  %bcur  = phi i32 [ %bsg, %muloop_head ], [ %bshr, %muloop ]
  %chi   = phi i32 [ 0,  %muloop_head ], [ %chinv, %muloop ]
  %clo   = phi i32 [ 0,  %muloop_head ], [ %clinv, %muloop ]
  %ahi   = phi i32 [ %aho0, %muloop_head ], [ %ahinv, %muloop ]
  %alo   = phi i32 [ %alo0, %muloop_head ], [ %alinv, %muloop ]

  %b1   = and i32 %bcur, 1
  %set  = icmp ne i32 %b1, 0

  ; 64-bit accumulator (chi, clo) += m
  %slo    = add i32 %clo, %alo
  %wrp    = icmp ult i32 %slo, %clo
  %carry  = zext i1 %wrp to i32
  %chisum = add i32 %chi, %ahi
  %chicum = add i32 %chisum, %carry
  %chinv  = select i1 %set, i32 %chicum, i32 %chi
  %clinv  = select i1 %set, i32 %slo, i32 %clo

  ; m <<= 1 (48-bit shift, bit 31 of low word carries into bit 0 of high word)
  %mhi2  = shl i32 %ahi, 1
  %mlo31 = lshr i32 %alo, 31
  %ahinv = or i32 %mhi2, %mlo31
  %alinv = shl i32 %alo, 1

  %bshr = lshr i32 %bcur, 1
  %in   = add i32 %i, 1
  %done = icmp eq i32 %in, 24
  br i1 %done, label %muldone, label %muloop

muldone:
  ; the loop just exited, so the final product is %chinv * 2^32 + %clinv
  %nzHigh = icmp ne i32 %chinv, 0
  br i1 %nzHigh, label %msb_hi, label %msb_lo

msb_hi:
  %p1 = call i32 @_sf_msb(i32 %chinv)
  %p  = add i32 %p1, 32
  br label %msb_known

msb_lo:
  %p2 = call i32 @_sf_msb(i32 %clinv)
  br label %msb_known

msb_known:
  ; keep the top 24 bits of P (MSB at bit 23); bit 23 becomes the implicit "1."
  %p3    = phi i32 [ %p,  %msb_hi ], [ %p2, %msb_lo ]
  %shft  = sub i32 %p3, 23
  %invsh = sub i32 32, %shft
  %topH  = shl i32 %chinv, %invsh
  %topL  = lshr i32 %clinv, %shft
  %top24 = or i32 %topH, %topL
  %frac  = and i32 %top24, 8388607
  ; e = e_a + e_b + p - 173  (see derivation above)
  %esum = add i32 %aee, %bee
  %padd = add i32 %esum, %p3
  %e    = sub i32 %padd, 173
  %r    = call i32 @_sf_pack(i32 %sgnb, i32 %e, i32 %frac)
  ret i32 %r
}

; ---------------------------------------------------------------------------
; float division
; ---------------------------------------------------------------------------

define i32 @__divsf3(i32 %a, i32 %b) {
entry:
  %aexpf = and i32 %a, 2139095040
  %bexpf = and i32 %b, 2139095040
  %ae    = lshr i32 %aexpf, 23
  %be    = lshr i32 %bexpf, 23
  %ae255 = icmp eq i32 %ae, 255
  %be255 = icmp eq i32 %be, 255
  %sgn   = xor i32 %a, %b
  %sgnb  = and i32 %sgn, 2147483648
  %any255 = or i1 %ae255, %be255
  br i1 %any255, label %special, label %body

special:
  %afr = and i32 %a, 8388607
  %bfr = and i32 %b, 8388607
  %aNf = icmp ne i32 %afr, 0
  %bNf = icmp ne i32 %bfr, 0
  %aN  = and i1 %ae255, %aNf
  %bN  = and i1 %be255, %bNf
  %anyN = or i1 %aN, %bN
  br i1 %anyN, label %nan, label %inf_split

nan:
  ret i32 2143289344

inf_split:
  ; a infinite: inf/inf -> NaN, inf/x (x finite) -> Inf
  br i1 %ae255, label %a_inf, label %b_inf

a_inf:
  br i1 %be255, label %nan, label %d_inf

d_inf:
  %infb = or i32 %sgnb, 2139095040
  ret i32 %infb

b_inf:
  ; finite/inf -> signed zero
  ret i32 %sgnb

body:
  %an   = call { i32, i32 } @_sf_norm(i32 %a)
  %asg  = extractvalue { i32, i32 } %an, 0
  %aee  = extractvalue { i32, i32 } %an, 1
  %bn   = call { i32, i32 } @_sf_norm(i32 %b)
  %bsg  = extractvalue { i32, i32 } %bn, 0
  %bee  = extractvalue { i32, i32 } %bn, 1

  %bdz = icmp eq i32 %bsg, 0
  br i1 %bdz, label %div_zero, label %b_ok

div_zero:
  %adz = icmp eq i32 %asg, 0
  br i1 %adz, label %nan, label %d_inf2

d_inf2:
  %finf = or i32 %sgnb, 2139095040
  ret i32 %finf

b_ok:
  %adz2 = icmp eq i32 %asg, 0
  br i1 %adz2, label %q_zero, label %do_div

q_zero:
  ret i32 %sgnb

do_div:
  ; N = sig_a * 2^23 then N / sig_b  (quotient MSB at bit 23 or 22)
  %nhi = lshr i32 %asg, 9
  %nlo = shl i32 %asg, 23
  %q   = call i32 @_sf_div48(i32 %nhi, i32 %nlo, i32 %bsg)
  ; value = q * 2^(e_a - e_b - 23), seeded so _sf_norm_sig lands on the
  ; biased exponent field: e_seed = e_a - e_b + 127
  %ediff = sub i32 %aee, %bee
  %eseed = add i32 %ediff, 127
  %ns    = call { i32, i32 } @_sf_norm_sig(i32 %q, i32 %eseed)
  %frac  = extractvalue { i32, i32 } %ns, 0
  %ee    = extractvalue { i32, i32 } %ns, 1
  %r     = call i32 @_sf_pack(i32 %sgnb, i32 %ee, i32 %frac)
  ret i32 %r
}

; ---------------------------------------------------------------------------
; integer <-> float conversions
; ---------------------------------------------------------------------------

; int32 -> float
define i32 @__floatsisf(i32 %x) {
entry:
  %neg  = ashr i32 %x, 31
  %xr   = xor i32 %x, %neg
  %mag  = sub i32 %xr, %neg                    ; abs(x)
  %z    = icmp eq i32 %mag, 0
  br i1 %z, label %zero, label %nonz

zero:
  ret i32 0

nonz:
  %sgn  = and i32 %neg, 2147483648
  %ns   = call { i32, i32 } @_sf_norm_sig(i32 %mag, i32 150)
  %frac = extractvalue { i32, i32 } %ns, 0
  %ee   = extractvalue { i32, i32 } %ns, 1
  %r    = call i32 @_sf_pack(i32 %sgn, i32 %ee, i32 %frac)
  ret i32 %r
}

; uint32 -> float
define i32 @__floatunsisf(i32 %u) {
entry:
  %z = icmp eq i32 %u, 0
  br i1 %z, label %zero, label %nonz

zero:
  ret i32 0

nonz:
  %ns   = call { i32, i32 } @_sf_norm_sig(i32 %u, i32 150)
  %frac = extractvalue { i32, i32 } %ns, 0
  %ee   = extractvalue { i32, i32 } %ns, 1
  %r    = call i32 @_sf_pack(i32 0, i32 %ee, i32 %frac)
  ret i32 %r
}

; float -> int32 (truncates toward zero)
define i32 @__fixsfsi(i32 %b) {
entry:
  %sgn1 = lshr i32 %b, 31
  %isneg = icmp ne i32 %sgn1, 0
  %expf  = and i32 %b, 2139095040
  %e8    = lshr i32 %expf, 23
  %E     = sub i32 %e8, 127                    ; unbiased exponent
  %is255 = icmp eq i32 %e8, 255
  %frnb  = and i32 %b, 8388607
  %hasfr = icmp ne i32 %frnb, 0
  %isnan = and i1 %is255, %hasfr
  %lt    = icmp slt i32 %E, 0
  %zf    = or i1 %lt, %isnan
  br i1 %zf, label %zero, label %ovr

zero:
  ret i32 0

ovr:
  %sat0 = icmp sge i32 %E, 31
  br i1 %sat0, label %sat, label %int

sat:
  %sval  = select i1 %isneg, i32 2147483648, i32 2147483647
  ret i32 %sval

int:
  %f23   = and i32 %b, 8388607
  %sig   = or i32 %f23, 8388608
  %ge23  = icmp sge i32 %E, 23
  %sl    = sub i32 %E, 23
  %sr    = sub i32 23, %E
  %slc   = select i1 %ge23, i32 %sl, i32 0
  %src   = select i1 %ge23, i32 0, i32 %sr
  %vl    = shl i32 %sig, %slc
  %vr    = lshr i32 %sig, %src
  %v     = select i1 %ge23, i32 %vl, i32 %vr
  %negv  = sub i32 0, %v
  %r     = select i1 %isneg, i32 %negv, i32 %v
  ret i32 %r
}

; float -> uint32 (truncates toward zero)
define i32 @__fixunssfsi(i32 %b) {
entry:
  %sgn1 = lshr i32 %b, 31
  %negl = icmp ne i32 %sgn1, 0
  br i1 %negl, label %zeroN, label %sign_ok

zeroN:
  ret i32 0

sign_ok:
  %expf = and i32 %b, 2139095040
  %e8   = lshr i32 %expf, 23
  %E    = sub i32 %e8, 127
  %is255 = icmp eq i32 %e8, 255
  %frnb  = and i32 %b, 8388607
  %hasfr = icmp ne i32 %frnb, 0
  %isnan = and i1 %is255, %hasfr
  %lt   = icmp slt i32 %E, 0
  %zf   = or i1 %lt, %isnan
  br i1 %zf, label %zeroN, label %ovr

ovr:
  %sat0 = icmp sge i32 %E, 32
  br i1 %sat0, label %sat, label %int

sat:
  ret i32 4294967295                            ; 0xFFFFFFFF

int:
  %f23  = and i32 %b, 8388607
  %sig  = or i32 %f23, 8388608
  %ge23 = icmp sge i32 %E, 23
  %sl   = sub i32 %E, 23
  %sr   = sub i32 23, %E
  %slc  = select i1 %ge23, i32 %sl, i32 0
  %src  = select i1 %ge23, i32 0, i32 %sr
  %vl   = shl i32 %sig, %slc
  %vr   = lshr i32 %sig, %src
  %v    = select i1 %ge23, i32 %vl, i32 %vr
  ret i32 %v
}

; ---------------------------------------------------------------------------
; float comparisons (libgcc __*.sf2 ABI: NaN compares "unordered")
; ---------------------------------------------------------------------------

; Ordered comparison core: -1 if a < b, 0 if equal (incl. +-0), 1 if a > b,
; 2 if unordered (NaN present).
define internal i32 @_sf_cmp(i32 %a, i32 %b) {
entry:
  %aexpf = and i32 %a, 2139095040
  %bexpf = and i32 %b, 2139095040
  %ae    = lshr i32 %aexpf, 23
  %be    = lshr i32 %bexpf, 23
  %ae255 = icmp eq i32 %ae, 255
  %be255 = icmp eq i32 %be, 255
  %afr   = and i32 %a, 8388607
  %bfr   = and i32 %b, 8388607
  %aNf   = icmp ne i32 %afr, 0
  %bNf   = icmp ne i32 %bfr, 0
  %aNv   = and i1 %ae255, %aNf
  %bNv   = and i1 %be255, %bNf
  %uord  = or i1 %aNv, %bNv
  br i1 %uord, label %unordered, label %ordered

unordered:
  ret i32 2

ordered:
  %as   = and i32 %a, 2147483648
  %bs   = and i32 %b, 2147483648
  %aNeg = icmp ne i32 %as, 0
  %bNeg = icmp ne i32 %bs, 0
  %ua   = and i32 %a, 2147483647
  %ub   = and i32 %b, 2147483647
  %az   = icmp eq i32 %ua, 0
  %bz   = icmp eq i32 %ub, 0
  %zero = and i1 %az, %bz
  br i1 %zero, label %eq_res, label %notzero

notzero:
  %sd = xor i1 %aNeg, %bNeg
  br i1 %sd, label %sign_diff, label %same_sign

sign_diff:
  br i1 %aNeg, label %lt_res, label %gt_res

same_sign:
  %al = icmp ult i32 %ua, %ub
  %ag = icmp ugt i32 %ua, %ub
  br i1 %aNeg, label %both_neg, label %both_pos

both_pos:
  br i1 %al, label %lt_res, label %bp2

bp2:
  br i1 %ag, label %gt_res, label %eq_res

both_neg:
  br i1 %ag, label %lt_res, label %bn2

bn2:
  br i1 %al, label %gt_res, label %eq_res

lt_res:
  ret i32 -1

eq_res:
  ret i32 0

gt_res:
  ret i32 1
}

; libgcc ABI: __ltsf2 -> <0 iff a<b; 1 if unordered
define i32 @__ltsf2(i32 %a, i32 %b) {
  %c = call i32 @_sf_cmp(i32 %a, i32 %b)
  %u = icmp eq i32 %c, 2
  br i1 %u, label %un, label %ord

un:
  ret i32 1

ord:
  %lt = icmp slt i32 %c, 0
  %r  = select i1 %lt, i32 -1, i32 0
  ret i32 %r
}

; libgcc ABI: __lesf2 -> 0 iff a<=b; 1 if a>b or unordered
define i32 @__lesf2(i32 %a, i32 %b) {
  %c = call i32 @_sf_cmp(i32 %a, i32 %b)
  %u = icmp eq i32 %c, 2
  %gt = icmp sgt i32 %c, 0
  %bad = or i1 %u, %gt
  %r  = select i1 %bad, i32 1, i32 0
  ret i32 %r
}

; libgcc ABI: __gtsf2 -> >0 iff a>b; -1 if unordered
define i32 @__gtsf2(i32 %a, i32 %b) {
  %c = call i32 @_sf_cmp(i32 %a, i32 %b)
  %u = icmp eq i32 %c, 2
  br i1 %u, label %un, label %ord

un:
  ret i32 -1

ord:
  %gt = icmp sgt i32 %c, 0
  %r  = select i1 %gt, i32 1, i32 0
  ret i32 %r
}

; libgcc ABI: __gesf2 -> 0 iff a>=b; -1 if a<b or unordered
define i32 @__gesf2(i32 %a, i32 %b) {
  %c = call i32 @_sf_cmp(i32 %a, i32 %b)
  %u = icmp eq i32 %c, 2
  %lt = icmp slt i32 %c, 0
  %bad = or i1 %u, %lt
  %r  = select i1 %bad, i32 -1, i32 0
  ret i32 %r
}

; libgcc ABI: __eqsf2 -> 0 iff a==b; 1 otherwise (incl. unordered)
define i32 @__eqsf2(i32 %a, i32 %b) {
  %c = call i32 @_sf_cmp(i32 %a, i32 %b)
  %z = icmp eq i32 %c, 0
  %r = select i1 %z, i32 0, i32 1
  ret i32 %r
}

; libgcc ABI: __nesf2 -> 1 iff a!=b; 0 otherwise
define i32 @__nesf2(i32 %a, i32 %b) {
  %c = call i32 @_sf_cmp(i32 %a, i32 %b)
  %z = icmp eq i32 %c, 0
  %r = select i1 %z, i32 0, i32 1
  ret i32 %r
}
; EspressoMachine AVR runtime — random number generator
;
; Linear Congruential Generator (LCG) with constants from the GNU C library.
; state = state * 1103515245 + 12345
;
; __espressomachine_random_long(bound)  → state % bound   (returns lower 31 bits, unsigned)
; __espressomachine_random_range(min, max) → min + (state % (max - min))

@__espressomachine_random_seed = internal global i32 1

define i32 @__espressomachine_random_long(i32 %bound) {
entry:
  ; Advance state: state = state * 1103515245 + 12345
  %old = load i32, ptr @__espressomachine_random_seed
  %mul = mul i32 %old, 1103515245
  %new = add i32 %mul, 12345
  store i32 %new, ptr @__espressomachine_random_seed

  ; Extract upper 16 bits and shift to 31-bit unsigned range [0, 2147483647)
  %upper = lshr i32 %new, 16
  %val = and i32 %upper, 2147483647

  ; If bound is 0, return raw value
  %is_zero = icmp eq i32 %bound, 0
  br i1 %is_zero, label %ret_raw, label %ret_mod

ret_mod:
  %result = urem i32 %val, %bound
  ret i32 %result

ret_raw:
  ret i32 %val
}

define i32 @__espressomachine_random_range(i32 %min, i32 %max) {
entry:
  ; Advance state: state = state * 1103515245 + 12345
  %old = load i32, ptr @__espressomachine_random_seed
  %mul = mul i32 %old, 1103515245
  %new = add i32 %mul, 12345
  store i32 %new, ptr @__espressomachine_random_seed

  ; Extract upper 16 bits and shift to 31-bit unsigned range
  %upper = lshr i32 %new, 16
  %val = and i32 %upper, 2147483647

  ; range = max - min
  %range = sub i32 %max, %min

  ; If range is 0, just return min
  %is_zero = icmp eq i32 %range, 0
  br i1 %is_zero, label %ret_min, label %ret_offset

ret_offset:
  %offset = urem i32 %val, %range
  %result = add i32 %min, %offset
  ret i32 %result

ret_min:
  ret i32 %min
}

; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)


define void @Percent__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define i32 @Percent_ninetyPercent(i32 %v1) {
BB0:
  br label %BB1
BB1:
  %v2 = add i32 0, 9
  %v3 = mul i32 %v1, 9
  %v4 = add i32 0, 10
  %v5 = sdiv i32 %v3, 10
  ret i32 %v5
}

define void @Percent_main() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 1023
  call i32 @Percent_ninetyPercent(i32 1023)
  ret void
}


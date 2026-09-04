; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare i32  @__espressomachine_gpio_analogread(i32 %pin)
declare void @__espressomachine_gpio_analogWrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)

define void @FunctionsExample__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @FunctionsExample_main() {
BB0:
  br label %BB1
BB1:
  %v4 = add i32 0, 127
  %v1 = add i32 0, 9
  call void @__espressomachine_gpio_analogWrite(i32 9, i32 127)
  %v2 = add i32 0, 255
  %v3 = add i32 0, 10
  call void @__espressomachine_gpio_analogWrite(i32 10, i32 255)
  ret void
}


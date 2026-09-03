; ByteLight Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

@ArduinoBlink_counter = global i32 0

declare void @__bytelight_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__bytelight_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__bytelight_delay_ms(i32 %ms)

define void @ArduinoBlink__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @ArduinoBlink_setup() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 0
  store i32 0, ptr @ArduinoBlink_counter
  ret void
}

define void @ArduinoBlink_loop() {
BB0:
  br label %BB1
BB1:
  %v1 = load i32, ptr @ArduinoBlink_counter
  %v2 = add i32 0, 1
  %v3 = add i32 %v1, 1
  store i32 %v3, ptr @ArduinoBlink_counter
  ret void
}

define void @ArduinoBlink_main() {
entry:
  call void @ArduinoBlink_setup()
  br label %loop
loop:
  call void @ArduinoBlink_loop()
  br label %loop
}


; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

@MillisBlink_lastToggle = global i32 0
@MillisBlink_ledState = global i32 0

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)
declare i32  @__espressomachine_time_millis()
declare void @__espressomachine_time_init()

define void @MillisBlink__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @MillisBlink_setup() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 13
  %v2 = add i32 0, 1
  %_t0 = load volatile i8, ptr inttoptr (i16 36 to ptr)
  %_t1 = or i8 %_t0, 32
  store volatile i8 %_t1, ptr inttoptr (i16 36 to ptr)
  %v3 = call i32 @__espressomachine_time_millis()
  store i32 %v3, ptr @MillisBlink_lastToggle
  %v4 = add i32 0, 0
  store i32 0, ptr @MillisBlink_ledState
  ret void
}

define void @MillisBlink_loop() {
BB0:
  br label %BB1
BB1:
  %v1 = call i32 @__espressomachine_time_millis()
  %v2 = load i32, ptr @MillisBlink_lastToggle
  %v3 = sub i32 %v1, %v2
  %v4 = add i32 0, 500
  %v5 = sub i32 %v3, 500
  %cond0 = icmp slt i32 %v3, 500
  br i1 %cond0, label %BB2, label %BB3
BB2:
  ret void
BB3:
  %v6 = add i32 0, 1
  %v7 = load i32, ptr @MillisBlink_ledState
  %v8 = sub i32 1, %v7
  store i32 %v8, ptr @MillisBlink_ledState
  %v9 = add i32 0, 13
  call void @__espressomachine_gpio_digitalwrite(i32 13, i32 %v8)
  store i32 %v1, ptr @MillisBlink_lastToggle
  br label %BB2
}

define void @MillisBlink_main() {
entry:
  call void @__espressomachine_time_init()
  call void @MillisBlink_setup()
  br label %loop
loop:
  call void @MillisBlink_loop()
  br label %loop
}


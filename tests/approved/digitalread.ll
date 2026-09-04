; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare i32  @__espressomachine_gpio_digitalread(i32 %pin)
declare void @__espressomachine_delay_ms(i32 %ms)

define void @DigitalRead__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @DigitalRead_setup() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 2
  %v2 = add i32 0, 2
  %_t0 = load volatile i8, ptr inttoptr (i16 42 to ptr)
  %_t1 = and i8 %_t0, 251
  store volatile i8 %_t1, ptr inttoptr (i16 42 to ptr)
  %_t2 = load volatile i8, ptr inttoptr (i16 43 to ptr)
  %_t3 = or i8 %_t2, 4
  store volatile i8 %_t3, ptr inttoptr (i16 43 to ptr)
  %v3 = add i32 0, 13
  %v4 = add i32 0, 1
  %_t4 = load volatile i8, ptr inttoptr (i16 36 to ptr)
  %_t5 = or i8 %_t4, 32
  store volatile i8 %_t5, ptr inttoptr (i16 36 to ptr)
  ret void
}

define void @DigitalRead_loop() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 13
  %v2 = add i32 0, 2
  %_t0 = load volatile i8, ptr inttoptr (i16 41 to ptr)
  %_t1 = and i8 %_t0, 4
  %_t2 = icmp ne i8 %_t1, 0
  %v3 = zext i1 %_t2 to i32
  call void @__espressomachine_gpio_digitalwrite(i32 13, i32 %v3)
  ret void
}

define void @DigitalRead_main() {
entry:
  call void @DigitalRead_setup()
  br label %loop
loop:
  call void @DigitalRead_loop()
  br label %loop
}


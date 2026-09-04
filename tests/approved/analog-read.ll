; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare i32  @__espressomachine_gpio_analogread(i32 %pin)
declare void @__espressomachine_gpio_analogWrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)

define void @AnalogRead__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @AnalogRead_main() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 13
  %v2 = add i32 0, 1
  %_t0 = load volatile i8, ptr inttoptr (i16 36 to ptr)
  %_t1 = or i8 %_t0, 32
  store volatile i8 %_t1, ptr inttoptr (i16 36 to ptr)
  br label %BB5
BB2:
  %v7 = add i32 0, 13
  %v8 = add i32 0, 0
  %_t2 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t3 = and i8 %_t2, 223
  store volatile i8 %_t3, ptr inttoptr (i16 37 to ptr)
  %v9 = add i32 0, 500
  call void @__espressomachine_delay_ms(i32 500)
  br label %BB4
BB3:
  %v10 = add i32 0, 13
  %v11 = add i32 0, 1
  %_t4 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t5 = or i8 %_t4, 32
  store volatile i8 %_t5, ptr inttoptr (i16 37 to ptr)
  %v12 = add i32 0, 100
  call void @__espressomachine_delay_ms(i32 100)
  br label %BB4
BB4:
  br label %BB5
BB5:
  %v3 = add i32 0, 0
  %v4 = call i32 @__espressomachine_gpio_analogread(i32 0)
  %v5 = add i32 0, 512
  %v6 = sub i32 %v4, 512
  %cond6 = icmp sle i32 %v4, 512
  br i1 %cond6, label %BB2, label %BB3
}


; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%java_lang_Enum_t = type { ptr, i32 }
%GpioShowcase_t = type { ptr }

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare i32  @__espressomachine_gpio_digitalread(i32 %pin)
declare void @__espressomachine_delay_ms(i32 %ms)


define void @GpioShowcase__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ; ERROR: allocation of com.github.pfichtner.espressomachine.api.Gpio escapes stack frame — heap allocation not supported on ATmega328P
  ; This method cannot be compiled for the embedded target.
  %v1 = inttoptr i32 0 to ptr ; UNSUPPORTED_ESCAPE
  %gep0 = getelementptr %GpioShowcase_t, ptr %v0, i32 0, i32 0
  store ptr %v1, ptr %gep0
  ret void
}

define void @GpioShowcase_main() {
BB0:
  br label %BB1
BB1:
  %v1 = alloca %GpioShowcase_t
  call void @GpioShowcase__init_(ptr %v1)
  call void @GpioShowcase_run(ptr %v1)
  ret void
}

define void @GpioShowcase_run(ptr %v0) {
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
  br label %BB4
BB2:
  %v9 = add i32 0, 13
  %v10 = add i32 0, 0
  %_t6 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t7 = and i8 %_t6, 223
  store volatile i8 %_t7, ptr inttoptr (i16 37 to ptr)
  br label %BB4
BB3:
  %v11 = add i32 0, 13
  %v12 = add i32 0, 1
  %_t8 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t9 = or i8 %_t8, 32
  store volatile i8 %_t9, ptr inttoptr (i16 37 to ptr)
  ; init_class java.util.concurrent.TimeUnit
  %v13 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_MILLISECONDS, i32 0
  %v14 = add i64 0, 200
  call void @__espressomachine_delay_ms(i32 200)
  %v15 = add i32 0, 13
  %v16 = add i32 0, 0
  %_t10 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t11 = and i8 %_t10, 223
  store volatile i8 %_t11, ptr inttoptr (i16 37 to ptr)
  %v17 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_MILLISECONDS, i32 0
  %v18 = add i64 0, 200
  call void @__espressomachine_delay_ms(i32 200)
  br label %BB4
BB4:
  %v5 = add i32 0, 2
  %_t12 = load volatile i8, ptr inttoptr (i16 41 to ptr)
  %_t13 = and i8 %_t12, 4
  %_t14 = icmp ne i8 %_t13, 0
  %v6 = zext i1 %_t14 to i32
  %v7 = add i32 0, 1
  %v8 = sub i32 %v6, 1
  %cond15 = icmp ne i32 %v6, 1
  br i1 %cond15, label %BB2, label %BB3
}


@java_util_concurrent_TimeUnit_MILLISECONDS = global i8 0

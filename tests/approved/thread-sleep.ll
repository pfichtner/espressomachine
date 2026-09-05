; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%java_lang_Enum_t = type { ptr, i32 }

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)

define void @ThreadSleep__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @ThreadSleep_main() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 13
  %v2 = add i32 0, 1
  %_t0 = load volatile i8, ptr inttoptr (i16 36 to ptr)
  %_t1 = or i8 %_t0, 32
  store volatile i8 %_t1, ptr inttoptr (i16 36 to ptr)
  br label %BB2
BB2:
  %v3 = add i32 0, 13
  %v4 = add i32 0, 1
  %_t2 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t3 = or i8 %_t2, 32
  store volatile i8 %_t3, ptr inttoptr (i16 37 to ptr)
  %v5 = add i64 0, 1000
  %_t4 = trunc i64 1000 to i32
  call void @__espressomachine_delay_ms(i32 %_t4)
  %v6 = add i32 0, 13
  %v7 = add i32 0, 0
  %_t5 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t6 = and i8 %_t5, 223
  store volatile i8 %_t6, ptr inttoptr (i16 37 to ptr)
  %v8 = add i64 0, 500
  %_t7 = trunc i64 500 to i32
  call void @__espressomachine_delay_ms(i32 %_t7)
  %v9 = add i32 0, 13
  %v10 = add i32 0, 1
  %_t8 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t9 = or i8 %_t8, 32
  store volatile i8 %_t9, ptr inttoptr (i16 37 to ptr)
  ; init_class java.util.concurrent.TimeUnit
  %v11 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_SECONDS, i32 0
  %v12 = add i64 0, 2
  call void @__espressomachine_delay_ms(i32 2000)
  %v13 = add i32 0, 13
  %v14 = add i32 0, 0
  %_t10 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t11 = and i8 %_t10, 223
  store volatile i8 %_t11, ptr inttoptr (i16 37 to ptr)
  %v15 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_MILLISECONDS, i32 0
  %v16 = add i64 0, 250
  call void @__espressomachine_delay_ms(i32 250)
  br label %BB2
}


@java_util_concurrent_TimeUnit_SECONDS = global i8 0
@java_util_concurrent_TimeUnit_MILLISECONDS = global i8 0

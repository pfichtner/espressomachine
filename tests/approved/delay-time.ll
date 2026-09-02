; ByteLight Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%java_lang_Enum_t = type { ptr, i32 }

declare void @__bytelight_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__bytelight_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__bytelight_delay_ms(i32 %ms)

define void @DelayTime__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @DelayTime_main() {
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
  %v4 = add i32 0, 13
  %v5 = add i32 0, 1
  %_t2 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t3 = or i8 %_t2, 32
  store volatile i8 %_t3, ptr inttoptr (i16 37 to ptr)
  %v6 = add i64 0, 1
  %v3 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_SECONDS, i32 0
  call void @__bytelight_delay_ms(i32 1000)
  %v7 = add i32 0, 13
  %v8 = add i32 0, 0
  %_t4 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t5 = and i8 %_t4, 223
  store volatile i8 %_t5, ptr inttoptr (i16 37 to ptr)
  %v9 = add i64 0, 500
  %v10 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_MILLISECONDS, i32 0
  call void @__bytelight_delay_ms(i32 500)
  br label %BB2
}


@java_util_concurrent_TimeUnit_SECONDS = global i8 0
@java_util_concurrent_TimeUnit_MILLISECONDS = global i8 0

; ByteLight Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%java_lang_Enum_t = type { ptr, i32 }
%bytelight_api_TimeUnit_t = type { ptr, i32 }

@bytelight_api_TimeUnit_NANOSECONDS = global %bytelight_api_TimeUnit_t zeroinitializer
@bytelight_api_TimeUnit_MICROSECONDS = global %bytelight_api_TimeUnit_t zeroinitializer
@bytelight_api_TimeUnit_MILLISECONDS = global %bytelight_api_TimeUnit_t zeroinitializer
@bytelight_api_TimeUnit_SECONDS = global %bytelight_api_TimeUnit_t zeroinitializer
@bytelight_api_TimeUnit_MINUTES = global %bytelight_api_TimeUnit_t zeroinitializer
@bytelight_api_TimeUnit_HOURS = global %bytelight_api_TimeUnit_t zeroinitializer
@bytelight_api_TimeUnit_DAYS = global %bytelight_api_TimeUnit_t zeroinitializer
@bytelight_api_TimeUnit_$VALUES = global ptr null

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
  %v3 = getelementptr i8, ptr @bytelight_api_TimeUnit_SECONDS, i32 0
  call void @__bytelight_delay_ms(i32 1000)
  %v7 = add i32 0, 13
  %v8 = add i32 0, 0
  %_t4 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t5 = and i8 %_t4, 223
  store volatile i8 %_t5, ptr inttoptr (i16 37 to ptr)
  %v9 = add i64 0, 500
  %v10 = getelementptr i8, ptr @bytelight_api_TimeUnit_MILLISECONDS, i32 0
  call void @__bytelight_delay_ms(i32 500)
  br label %BB2
}

define void @bytelight_api_TimeUnit__clinit_() {
BB0:
  br label %BB1
BB1:
  %v1 = getelementptr %bytelight_api_TimeUnit_t, ptr @bytelight_api_TimeUnit_NANOSECONDS, i32 0
  ; static object already initialized as global: @bytelight_api_TimeUnit_NANOSECONDS
  %v2 = getelementptr %bytelight_api_TimeUnit_t, ptr @bytelight_api_TimeUnit_MICROSECONDS, i32 0
  ; static object already initialized as global: @bytelight_api_TimeUnit_MICROSECONDS
  %v3 = getelementptr %bytelight_api_TimeUnit_t, ptr @bytelight_api_TimeUnit_MILLISECONDS, i32 0
  ; static object already initialized as global: @bytelight_api_TimeUnit_MILLISECONDS
  %v4 = getelementptr %bytelight_api_TimeUnit_t, ptr @bytelight_api_TimeUnit_SECONDS, i32 0
  ; static object already initialized as global: @bytelight_api_TimeUnit_SECONDS
  %v5 = getelementptr %bytelight_api_TimeUnit_t, ptr @bytelight_api_TimeUnit_MINUTES, i32 0
  ; static object already initialized as global: @bytelight_api_TimeUnit_MINUTES
  %v6 = getelementptr %bytelight_api_TimeUnit_t, ptr @bytelight_api_TimeUnit_HOURS, i32 0
  ; static object already initialized as global: @bytelight_api_TimeUnit_HOURS
  %v7 = getelementptr %bytelight_api_TimeUnit_t, ptr @bytelight_api_TimeUnit_DAYS, i32 0
  ; static object already initialized as global: @bytelight_api_TimeUnit_DAYS
  %v8 = add i32 0, 7
  %v10 = inttoptr i32 0 to ptr
  %v9 = add i32 0, 0
  %v12 = add i32 0, 1
  %v13 = add i32 0, 2
  %v14 = add i32 0, 3
  %v15 = add i32 0, 4
  %v16 = add i32 0, 5
  %v17 = add i32 0, 6
  store ptr %v10, ptr @bytelight_api_TimeUnit_$VALUES
  ret void
}


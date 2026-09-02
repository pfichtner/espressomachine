; TinyJava Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%java_lang_Enum_t = type { ptr, i32 }
%TimeUnit_t = type { ptr, i32 }

@TimeUnit_NANOSECONDS = global %TimeUnit_t zeroinitializer
@TimeUnit_MICROSECONDS = global %TimeUnit_t zeroinitializer
@TimeUnit_MILLISECONDS = global %TimeUnit_t zeroinitializer
@TimeUnit_SECONDS = global %TimeUnit_t zeroinitializer
@TimeUnit_MINUTES = global %TimeUnit_t zeroinitializer
@TimeUnit_HOURS = global %TimeUnit_t zeroinitializer
@TimeUnit_DAYS = global %TimeUnit_t zeroinitializer
@TimeUnit_$VALUES = global ptr null

declare void @__tinyjava_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__tinyjava_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__tinyjava_delay_ms(i32 %ms)


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
  %v3 = getelementptr i8, ptr @TimeUnit_SECONDS, i32 0
  call void @__tinyjava_delay_ms(i32 1000)
  %v7 = add i32 0, 13
  %v8 = add i32 0, 0
  %_t4 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t5 = and i8 %_t4, 223
  store volatile i8 %_t5, ptr inttoptr (i16 37 to ptr)
  %v9 = add i64 0, 500
  %v10 = getelementptr i8, ptr @TimeUnit_MILLISECONDS, i32 0
  call void @__tinyjava_delay_ms(i32 500)
  br label %BB2
}

define void @TimeUnit__init_(ptr %v0, ptr %v1, i32 %v2) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %java_lang_Enum_t, ptr %v0, i32 0, i32 0
  store ptr %v1, ptr %gep0
  %gep1 = getelementptr %java_lang_Enum_t, ptr %v0, i32 0, i32 1
  store i32 %v2, ptr %gep1
  ret void
}

define void @TimeUnit__clinit_() {
BB0:
  br label %BB1
BB1:
  %v1 = getelementptr %TimeUnit_t, ptr @TimeUnit_NANOSECONDS, i32 0
  %v2 = inttoptr i32 0 to ptr
  %v3 = add i32 0, 0
  %gep0 = getelementptr %java_lang_Enum_t, ptr %v1, i32 0, i32 1
  store i32 0, ptr %gep0
  ; static object already initialized as global: @TimeUnit_NANOSECONDS
  %v4 = getelementptr %TimeUnit_t, ptr @TimeUnit_MICROSECONDS, i32 0
  %v5 = inttoptr i32 0 to ptr
  %v6 = add i32 0, 1
  %gep1 = getelementptr %java_lang_Enum_t, ptr %v4, i32 0, i32 1
  store i32 1, ptr %gep1
  ; static object already initialized as global: @TimeUnit_MICROSECONDS
  %v7 = getelementptr %TimeUnit_t, ptr @TimeUnit_MILLISECONDS, i32 0
  %v8 = inttoptr i32 0 to ptr
  %v9 = add i32 0, 2
  %gep2 = getelementptr %java_lang_Enum_t, ptr %v7, i32 0, i32 1
  store i32 2, ptr %gep2
  ; static object already initialized as global: @TimeUnit_MILLISECONDS
  %v10 = getelementptr %TimeUnit_t, ptr @TimeUnit_SECONDS, i32 0
  %v11 = inttoptr i32 0 to ptr
  %v12 = add i32 0, 3
  %gep3 = getelementptr %java_lang_Enum_t, ptr %v10, i32 0, i32 1
  store i32 3, ptr %gep3
  ; static object already initialized as global: @TimeUnit_SECONDS
  %v13 = getelementptr %TimeUnit_t, ptr @TimeUnit_MINUTES, i32 0
  %v14 = inttoptr i32 0 to ptr
  %v15 = add i32 0, 4
  %gep4 = getelementptr %java_lang_Enum_t, ptr %v13, i32 0, i32 1
  store i32 4, ptr %gep4
  ; static object already initialized as global: @TimeUnit_MINUTES
  %v16 = getelementptr %TimeUnit_t, ptr @TimeUnit_HOURS, i32 0
  %v17 = inttoptr i32 0 to ptr
  %v18 = add i32 0, 5
  %gep5 = getelementptr %java_lang_Enum_t, ptr %v16, i32 0, i32 1
  store i32 5, ptr %gep5
  ; static object already initialized as global: @TimeUnit_HOURS
  %v19 = getelementptr %TimeUnit_t, ptr @TimeUnit_DAYS, i32 0
  %v20 = inttoptr i32 0 to ptr
  %v21 = add i32 0, 6
  %gep6 = getelementptr %java_lang_Enum_t, ptr %v19, i32 0, i32 1
  store i32 6, ptr %gep6
  ; static object already initialized as global: @TimeUnit_DAYS
  %v22 = add i32 0, 7
  %v25 = inttoptr i32 0 to ptr
  %v23 = add i32 0, 0
  %v24 = getelementptr i8, ptr @TimeUnit_NANOSECONDS, i32 0
  %v27 = add i32 0, 1
  %v28 = getelementptr i8, ptr @TimeUnit_MICROSECONDS, i32 0
  %v29 = add i32 0, 2
  %v30 = getelementptr i8, ptr @TimeUnit_MILLISECONDS, i32 0
  %v31 = add i32 0, 3
  %v32 = getelementptr i8, ptr @TimeUnit_SECONDS, i32 0
  %v33 = add i32 0, 4
  %v34 = getelementptr i8, ptr @TimeUnit_MINUTES, i32 0
  %v35 = add i32 0, 5
  %v36 = getelementptr i8, ptr @TimeUnit_HOURS, i32 0
  %v37 = add i32 0, 6
  store ptr %v25, ptr @TimeUnit_$VALUES
  ret void
}


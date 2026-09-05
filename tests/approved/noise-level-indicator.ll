; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%java_lang_Enum_t = type { ptr, i32 }
%NoiseLevelIndicator_t = type { i32, ptr }

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)


define void @NoiseLevelIndicator__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 13
  %gep0 = getelementptr %NoiseLevelIndicator_t, ptr %v0, i32 0, i32 0
  store i32 13, ptr %gep0
  ; ERROR: allocation of com.github.pfichtner.espressomachine.api.Gpio escapes stack frame — heap allocation not supported on ATmega328P
  ; This method cannot be compiled for the embedded target.
  %v2 = inttoptr i32 0 to ptr ; UNSUPPORTED_ESCAPE
  %gep1 = getelementptr %NoiseLevelIndicator_t, ptr %v0, i32 0, i32 1
  store ptr %v2, ptr %gep1
  ret void
}

define void @NoiseLevelIndicator_main(ptr %v1) {
BB0:
  br label %BB1
BB1:
  %v2 = alloca %NoiseLevelIndicator_t
  call void @NoiseLevelIndicator__init_(ptr %v2)
  call void @NoiseLevelIndicator_main(ptr %v2)
  ret void
}

define void @NoiseLevelIndicator_main(ptr %v0) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %NoiseLevelIndicator_t, ptr %v0, i32 0, i32 0
  %v2 = load i32, ptr %gep0
  %v1 = add i32 0, 1
  call void @__espressomachine_gpio_pinmode(i32 %v2, i32 1)
  br label %BB2
BB2:
  %gep1 = getelementptr %NoiseLevelIndicator_t, ptr %v0, i32 0, i32 1
  %v3 = load ptr, ptr %gep1
  %v4 = add i32 0, 13
  %v5 = add i32 0, 1
  call void @com_github_pfichtner_espressomachine_api_Gpio_digitalWrite(ptr %v3, i32 13, i32 1)
  ; init_class java.util.concurrent.TimeUnit
  %v6 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_MILLISECONDS, i32 0
  %v7 = add i64 0, 500
  call void @__espressomachine_delay_ms(i32 500)
  %gep2 = getelementptr %NoiseLevelIndicator_t, ptr %v0, i32 0, i32 1
  %v8 = load ptr, ptr %gep2
  %v9 = add i32 0, 13
  %v10 = add i32 0, 0
  call void @com_github_pfichtner_espressomachine_api_Gpio_digitalWrite(ptr %v8, i32 13, i32 0)
  %v11 = getelementptr i8, ptr @java_util_concurrent_TimeUnit_MILLISECONDS, i32 0
  %v12 = add i64 0, 500
  call void @__espressomachine_delay_ms(i32 500)
  br label %BB2
}

define void @com_github_pfichtner_espressomachine_api_Gpio_digitalWrite(ptr %v0, i32 %v1, i32 %v2) {
BB0:
  br label %BB1
BB1:
  call void @__espressomachine_gpio_digitalwrite(i32 %v1, i32 %v2)
  ret void
}


@java_util_concurrent_TimeUnit_MILLISECONDS = global i8 0

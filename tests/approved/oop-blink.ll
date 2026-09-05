; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%Led_t = type { i32 }

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)


define void @OopBlink__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @OopBlink_main() {
BB0:
  br label %BB1
BB1:
  %v1 = alloca %Led_t
  %v2 = add i32 0, 13
  call void @Led__init_(ptr %v1, i32 13)
  %v3 = alloca %Led_t
  %v4 = add i32 0, 12
  call void @Led__init_(ptr %v3, i32 12)
  br label %BB2
BB2:
  call void @Led_on(ptr %v1)
  call void @Led_off(ptr %v3)
  %v5 = add i32 0, 500
  call void @__espressomachine_delay_ms(i32 500)
  call void @Led_off(ptr %v1)
  call void @Led_on(ptr %v3)
  %v6 = add i32 0, 500
  call void @__espressomachine_delay_ms(i32 500)
  br label %BB2
}

define void @Led__init_(ptr %v0, i32 %v1) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %Led_t, ptr %v0, i32 0, i32 0
  store i32 %v1, ptr %gep0
  %v2 = add i32 0, 1
  call void @__espressomachine_gpio_pinmode(i32 %v1, i32 1)
  ret void
}

define void @Led_on(ptr %v0) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %Led_t, ptr %v0, i32 0, i32 0
  %v2 = load i32, ptr %gep0
  %v1 = add i32 0, 1
  call void @__espressomachine_gpio_digitalwrite(i32 %v2, i32 1)
  ret void
}

define void @Led_off(ptr %v0) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %Led_t, ptr %v0, i32 0, i32 0
  %v2 = load i32, ptr %gep0
  %v1 = add i32 0, 0
  call void @__espressomachine_gpio_digitalwrite(i32 %v2, i32 0)
  ret void
}


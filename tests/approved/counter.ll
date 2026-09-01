; TinyJava Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%Counter_t = type { i32 }

declare void @__tinyjava_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__tinyjava_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__tinyjava_delay_ms(i32 %ms)


define void @Counter__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @Counter_increment(ptr %v0) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %Counter_t, ptr %v0, i32 0, i32 0
  %v2 = load i32, ptr %gep0
  %v1 = add i32 0, 1
  %v3 = add i32 %v2, 1
  %gep1 = getelementptr %Counter_t, ptr %v0, i32 0, i32 0
  store i32 %v3, ptr %gep1
  ret void
}

define i32 @Counter_get(ptr %v0) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %Counter_t, ptr %v0, i32 0, i32 0
  %v1 = load i32, ptr %gep0
  ret i32 %v1
}

define void @Counter_main() {
BB0:
  br label %BB1
BB1:
  %v1 = alloca %Counter_t
  call void @Counter_increment(ptr %v1)
  call void @Counter_increment(ptr %v1)
  ret void
}


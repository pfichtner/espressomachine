; TinyJava Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%Led_t = type { i32 }

declare void @__tinyjava_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__tinyjava_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__tinyjava_delay_ms(i32 %ms)

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
  %v1 = add i32 0, 13
  br label %BB2
BB2:
  %v5 = add i32 0, 1
  %_t0 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t1 = or i8 %_t0, 32
  store volatile i8 %_t1, ptr inttoptr (i16 37 to ptr)
  %v2 = add i32 0, 500
  call void @__tinyjava_delay_ms(i32 500)
  %v4 = add i32 0, 0
  %_t2 = load volatile i8, ptr inttoptr (i16 37 to ptr)
  %_t3 = and i8 %_t2, 223
  store volatile i8 %_t3, ptr inttoptr (i16 37 to ptr)
  %v3 = add i32 0, 500
  call void @__tinyjava_delay_ms(i32 500)
  br label %BB2
}


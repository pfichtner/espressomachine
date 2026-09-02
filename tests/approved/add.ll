; ByteLight Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__bytelight_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__bytelight_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__bytelight_delay_ms(i32 %ms)

define void @Add__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define i32 @Add_add(i32 %v1, i32 %v2) {
BB0:
  br label %BB1
BB1:
  %v3 = add i32 %v1, %v2
  ret i32 %v3
}

define void @Add_main() {
BB0:
  br label %BB1
BB1:
  ret void
}


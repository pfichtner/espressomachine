; ByteLight Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__bytelight_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__bytelight_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__bytelight_delay_ms(i32 %ms)
declare void @__bytelight_serial_begin(i32 %baud)
declare void @__bytelight_serial_write(i32 %b)

define void @Echo__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @Echo_main() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 9600
  store volatile i8 0, ptr inttoptr (i16 197 to ptr)
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)
  store volatile i8 24, ptr inttoptr (i16 193 to ptr)
  store volatile i8 6,  ptr inttoptr (i16 194 to ptr)
  br label %BB2
BB2:
  %_t0 = load volatile i8, ptr inttoptr (i16 192 to ptr)
  %_t1 = and i8 %_t0, -128
  %_t2 = icmp ne i8 %_t1, 0
  %v2 = zext i1 %_t2 to i32
  %cond3 = icmp sle i32 %v2, 0
  br i1 %cond3, label %BB2, label %BB3
BB3:
  %_t4 = load volatile i8, ptr inttoptr (i16 198 to ptr)
  %v3 = zext i8 %_t4 to i32
  call void @__bytelight_serial_write(i32 %v3)
  br label %BB2
}


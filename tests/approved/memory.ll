; ByteLight Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%Counter_t = type { i32 }

@MemoryTest_counter = global %Counter_t zeroinitializer

declare void @__bytelight_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__bytelight_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__bytelight_delay_ms(i32 %ms)


define void @MemoryTest__init_(ptr %v0) {
BB0:
  ; init_class MemoryTest
  br label %BB1
BB1:
  ret void
}

define void @MemoryTest_localUse() {
BB0:
  ; init_class MemoryTest
  br label %BB1
BB1:
  %v1 = alloca %Counter_t
  call void @Counter_increment(ptr %v1)
  ret void
}

define ptr @MemoryTest_escape() {
BB0:
  ; init_class MemoryTest
  br label %BB1
BB1:
  ; ERROR: allocation of Counter escapes stack frame — heap allocation not supported on ATmega328P
  ; This method cannot be compiled for the embedded target.
  %v1 = inttoptr i32 0 to ptr ; UNSUPPORTED_ESCAPE
  ret ptr %v1
}

define void @MemoryTest_main() {
BB0:
  ; init_class MemoryTest
  br label %BB1
BB1:
  %v1 = getelementptr i8, ptr @MemoryTest_counter, i32 0
  call void @Counter_increment(ptr %v1)
  call void @MemoryTest_localUse()
  ret void
}

define void @MemoryTest__clinit_() {
BB0:
  br label %BB1
BB1:
  %v1 = getelementptr %Counter_t, ptr @MemoryTest_counter, i32 0
  ; static object already initialized as global: @MemoryTest_counter
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


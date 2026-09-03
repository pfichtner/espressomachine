; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)
declare void @__espressomachine_serial_begin(i32 %baud)
declare void @__espressomachine_serial_write(i32 %b)
declare void @__espressomachine_serial_print_int(i32 %n)
declare void @__espressomachine_serial_print_str(ptr %s)

define void @HelloSerial__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @HelloSerial_main() {
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
  %v2 = add i32 0, 65
  call void @__espressomachine_serial_write(i32 65)
  %v5 = add i32 0, 13
  call void @__espressomachine_serial_write(i32 13)
  %v6 = add i32 0, 10
  call void @__espressomachine_serial_write(i32 10)
  %v3 = getelementptr i8, ptr @espressomachine_string_0, i32 0
  call void @__espressomachine_serial_print_str(ptr @espressomachine_string_0)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v4 = add i32 0, 1000
  call void @__espressomachine_delay_ms(i32 1000)
  br label %BB2
}

@espressomachine_string_0 = private unnamed_addr constant [24 x i8] c"Hello, EspressoMachine!\00"


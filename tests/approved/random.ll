; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)
declare void @__espressomachine_serial_begin(i32 %baud)
declare void @__espressomachine_serial_write(i32 %b)
declare void @__espressomachine_serial_print_int(i32 %n)
declare void @__espressomachine_serial_print_str(ptr %s)
declare i32 @__espressomachine_random_long(i32 %bound)
declare i32 @__espressomachine_random_range(i32 %min, i32 %max)

define void @RandomExample__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @RandomExample_main(ptr %v1) {
BB0:
  br label %BB1
BB1:
  %v2 = add i32 0, 9600
  store volatile i8 0, ptr inttoptr (i16 197 to ptr)
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)
  store volatile i8 24, ptr inttoptr (i16 193 to ptr)
  store volatile i8 6, ptr inttoptr (i16 194 to ptr)
  %v4 = add i32 0, 100
  %v5 = call i32 @__espressomachine_random_long(i32 100)
  %v6 = add i32 0, 1
  %v3 = add i32 0, 10
  %v7 = call i32 @__espressomachine_random_range(i32 1, i32 10)
  call void @__espressomachine_serial_print_int(i32 %v5)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  call void @__espressomachine_serial_print_int(i32 %v7)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  ret void
}


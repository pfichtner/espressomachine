; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)
declare void @__espressomachine_serial_begin(i32 %baud)
declare void @__espressomachine_serial_write(i32 %b)
declare void @__espressomachine_serial_print_int(i32 %n)
declare void @__espressomachine_serial_print_str(ptr %s)

define void @JavaRandom__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @JavaRandom_main(ptr %v1) {
BB0:
  br label %BB1
BB1:
  %v7 = add i32 0, 9600
  store volatile i8 0, ptr inttoptr (i16 197 to ptr)
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)
  store volatile i8 24, ptr inttoptr (i16 193 to ptr)
  store volatile i8 6, ptr inttoptr (i16 194 to ptr)
  ; unsupported: new java.util.Random (JDK class, not available on embedded)
  %v9 = inttoptr i32 0 to ptr
  %v8 = add i32 0, %v9
  %v2 = add i32 0, %v9
  %v10 = add i32 0, %v2
  %v11 = call i32 @__random_next_int()
  %v3 = add i32 0, %v11
  %v12 = add i32 0, %v2
  %v13 = add i32 0, 100
  %v14 = call i32 @__random_next_int_bound(i32 100)
  %v4 = add i32 0, %v14
  %v15 = add i32 0, %v2
  %_t0 = call i32 @__random_next_long_lo()
  %v16 = zext i32 %_t0 to i64
  %v5 = add i32 0, %v16
  %v17 = add i32 0, %v3
  call void @__espressomachine_serial_print_int(i32 %v17)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v18 = add i32 0, %v4
  call void @__espressomachine_serial_print_int(i32 %v18)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v19 = add i32 0, %v5
  %v20 = trunc i64 %v19 to i32
  call void @__espressomachine_serial_print_int(i32 %v20)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  ret void
}


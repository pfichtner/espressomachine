; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)
declare void @__espressomachine_serial_begin(i32 %baud)
declare void @__espressomachine_serial_write(i32 %b)
declare void @__espressomachine_serial_print_int(i32 %n)
declare void @__espressomachine_serial_print_str(ptr %s)

define void @JavaMath__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @JavaMath_main(ptr %v1) {
BB0:
  br label %BB1
BB1:
  %v8 = add i32 0, 9600
  store volatile i8 0, ptr inttoptr (i16 197 to ptr)
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)
  store volatile i8 24, ptr inttoptr (i16 193 to ptr)
  store volatile i8 6, ptr inttoptr (i16 194 to ptr)
  %v11 = add i32 0, 5
  %v2 = add i32 0, 5
  %v12 = add i32 0, 3
  %v3 = add i32 0, 3
  %v13 = add i32 0, 5
  %v9 = add i32 0, 3
  %v14 = call i32 @llvm.smin.i32(i32 5, i32 3)
  call void @__espressomachine_serial_print_int(i32 %v14)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v15 = add i32 0, 5
  %v16 = add i32 0, 3
  %v17 = call i32 @llvm.smax.i32(i32 5, i32 3)
  call void @__espressomachine_serial_print_int(i32 %v17)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v18 = add i32 0, -7
  %v19 = call i32 @llvm.abs.i32(i32 -7, i1 false)
  call void @__espressomachine_serial_print_int(i32 %v19)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v20 = fadd double 0.0, 0x4000000000000000
  %v10 = fadd double 0.0, 0x4024000000000000
  %v21 = call double @pow(double %v20, double %v10)
  %v4 = add i32 0, %v21
  %v22 = fadd double 0.0, 0x4022000000000000
  %v23 = call double @sqrt(double %v22)
  %v6 = add i32 0, %v23
  %v24 = add i32 0, %v4
  %v25 = fptosi double %v24 to i32
  call void @__espressomachine_serial_print_int(i32 %v25)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v26 = add i32 0, %v6
  %v27 = fptosi double %v26 to i32
  call void @__espressomachine_serial_print_int(i32 %v27)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  ret void
}


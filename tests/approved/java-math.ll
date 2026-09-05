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

define void @JavaMath_main() {
BB0:
  br label %BB1
BB1:
  %v7 = add i32 0, 9600
  store volatile i8 0, ptr inttoptr (i16 197 to ptr)
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)
  store volatile i8 24, ptr inttoptr (i16 193 to ptr)
  store volatile i8 6, ptr inttoptr (i16 194 to ptr)
  %v10 = add i32 0, 5
  %v1 = add i32 0, 5
  %v11 = add i32 0, 3
  %v2 = add i32 0, 3
  %v12 = add i32 0, 5
  %v8 = add i32 0, 3
  %v13 = call i32 @llvm.smin.i32(i32 5, i32 3)
  call void @__espressomachine_serial_print_int(i32 %v13)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v14 = add i32 0, 5
  %v15 = add i32 0, 3
  %v16 = call i32 @llvm.smax.i32(i32 5, i32 3)
  call void @__espressomachine_serial_print_int(i32 %v16)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v17 = add i32 0, -7
  %v18 = call i32 @llvm.abs.i32(i32 -7, i1 false)
  call void @__espressomachine_serial_print_int(i32 %v18)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v19 = fadd double 0.0, 0x4000000000000000
  %v9 = fadd double 0.0, 0x4024000000000000
  %v20 = call double @pow(double %v19, double %v9)
  %v3 = add i32 0, %v20
  %v21 = fadd double 0.0, 0x4022000000000000
  %v22 = call double @sqrt(double %v21)
  %v5 = add i32 0, %v22
  %v23 = add i32 0, %v3
  %v24 = fptosi double %v23 to i32
  call void @__espressomachine_serial_print_int(i32 %v24)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  %v25 = add i32 0, %v5
  %v26 = fptosi double %v25 to i32
  call void @__espressomachine_serial_print_int(i32 %v26)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  ret void
}


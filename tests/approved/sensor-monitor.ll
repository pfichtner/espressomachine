; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare i32  @__espressomachine_gpio_analogread(i32 %pin)
declare void @__espressomachine_gpio_analogWrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)
declare void @__espressomachine_serial_begin(i32 %baud)
declare void @__espressomachine_serial_write(i32 %b)
declare void @__espressomachine_serial_print_int(i32 %n)
declare void @__espressomachine_serial_print_str(ptr %s)

define void @SensorMonitor__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @SensorMonitor_main() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 9600
  store volatile i8 0, ptr inttoptr (i16 197 to ptr)
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)
  store volatile i8 24, ptr inttoptr (i16 193 to ptr)
  store volatile i8 6, ptr inttoptr (i16 194 to ptr)
  %v3 = add i32 0, 13
  %v2 = add i32 0, 1
  %_t0 = load volatile i8, ptr inttoptr (i16 36 to ptr)
  %_t1 = or i8 %_t0, 32
  store volatile i8 %_t1, ptr inttoptr (i16 36 to ptr)
  %v4 = getelementptr i8, ptr @espressomachine_string_0, i32 0
  call void @__espressomachine_serial_print_str(ptr @espressomachine_string_0)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  br label %BB2
BB2:
  %v5 = add i32 0, 0
  %v6 = call i32 @__espressomachine_gpio_analogread(i32 0)
  %v7 = sitofp i32 %v6 to float
  %v8 = fadd float 0.0, 0x4014000000000000
  %v9 = fmul float %v7, %v8
  %v10 = fadd float 0.0, 0x408FF80000000000
  %v11 = fdiv float %v9, %v10
  %v12 = fadd float 0.0, 0x406FE00000000000
  %v13 = fmul float %v11, %v12
  %v14 = fadd float 0.0, 0x4014000000000000
  %v15 = fdiv float %v13, %v14
  %v16 = fptosi float %v15 to i32
  %v17 = add i32 0, 13
  call void @__espressomachine_gpio_analogWrite(i32 13, i32 %v16)
  %v18 = getelementptr i8, ptr @espressomachine_string_1, i32 0
  call void @__espressomachine_serial_print_str(ptr @espressomachine_string_1)
  call void @__espressomachine_serial_print_int(i32 %v6)
  %v19 = getelementptr i8, ptr @espressomachine_string_2, i32 0
  call void @__espressomachine_serial_print_str(ptr @espressomachine_string_2)
  %v20 = fptosi float %v11 to i32
  call void @__espressomachine_serial_print_int(i32 %v20)
  %v21 = getelementptr i8, ptr @espressomachine_string_3, i32 0
  call void @__espressomachine_serial_print_str(ptr @espressomachine_string_3)
  call void @__espressomachine_serial_print_int(i32 %v16)
  %v23 = add i32 0, 13
  call void @__espressomachine_serial_write(i32 13)
  %v24 = add i32 0, 10
  call void @__espressomachine_serial_write(i32 10)
  %v22 = add i32 0, 1000
  call void @__espressomachine_delay_ms(i32 1000)
  br label %BB2
}

@espressomachine_string_0 = private unnamed_addr constant [31 x i8] c"=== Sensor Monitor Started ===\00"
@espressomachine_string_1 = private unnamed_addr constant [8 x i8] c"Sensor:\00"
@espressomachine_string_2 = private unnamed_addr constant [4 x i8] c",V:\00"
@espressomachine_string_3 = private unnamed_addr constant [6 x i8] c",PWM:\00"


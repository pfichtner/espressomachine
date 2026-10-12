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
declare void @__espressomachine_serial_print_str(ptr addrspace(1) %s)
declare void @__espressomachine_serial_print_uint(i32 %n)
declare void @__espressomachine_serial_print_radix(i32 %n, i32 %radix, i32 %upper)

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
  %v4 = add i32 0, 13
  %v2 = add i32 0, 1
  %_t0 = load volatile i8, ptr inttoptr (i16 36 to ptr)
  %_t1 = or i8 %_t0, 32
  store volatile i8 %_t1, ptr inttoptr (i16 36 to ptr)
  %v5 = getelementptr i8, ptr addrspace(1) @espressomachine_string_0, i32 0
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_0)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  br label %BB2
BB2:
  %v6 = add i32 0, 0
  %v7 = call i32 @__espressomachine_gpio_analogread(i32 0)
  %v8 = sitofp i32 %v7 to float
  %v9 = fadd float 0.0, 0x4014000000000000
  %v10 = fmul float %v8, %v9
  %v11 = fadd float 0.0, 0x408FF80000000000
  %v12 = fdiv float %v10, %v11
  %v13 = fadd float 0.0, 0x406FE00000000000
  %v14 = fmul float %v12, %v13
  %v15 = fadd float 0.0, 0x4014000000000000
  %v16 = fdiv float %v14, %v15
  %v17 = fptosi float %v16 to i32
  %v18 = add i32 0, 13
  call void @__espressomachine_gpio_analogWrite(i32 13, i32 %v17)
  %v19 = getelementptr i8, ptr addrspace(1) @espressomachine_string_1, i32 0
  %v25 = fptosi float %v12 to i32
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_2)
  call void @__espressomachine_serial_print_int(i32 %v7)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_3)
  call void @__espressomachine_serial_print_int(i32 %v25)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_4)
  call void @__espressomachine_serial_print_int(i32 %v17)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_5)
  %v29 = add i32 0, 1000
  call void @__espressomachine_delay_ms(i32 1000)
  br label %BB2
}

@espressomachine_string_0 = private unnamed_addr addrspace(1) constant [31 x i8] c"=== Sensor Monitor Started ===\00"
@espressomachine_string_1 = private unnamed_addr addrspace(1) constant [24 x i8] c"Sensor:%d,V:%d,PWM:%d\0D\0A\00"
@espressomachine_string_2 = private unnamed_addr addrspace(1) constant [8 x i8] c"Sensor:\00"
@espressomachine_string_3 = private unnamed_addr addrspace(1) constant [4 x i8] c",V:\00"
@espressomachine_string_4 = private unnamed_addr addrspace(1) constant [6 x i8] c",PWM:\00"
@espressomachine_string_5 = private unnamed_addr addrspace(1) constant [3 x i8] c"\0D\0A\00"


; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

@SensorMonitor_lastSample = global i32 0
@SensorMonitor_levels = global ptr null

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
  ; init_class SensorMonitor
  br label %BB1
BB1:
  ret void
}

define i32 @SensorMonitor_scaled(i32 %v1) {
BB0:
  ; init_class SensorMonitor
  br label %BB1
BB1:
  %v2 = add i32 %v1, %v1
  ret i32 %v2
}

define void @SensorMonitor_main() {
BB0:
  ; init_class SensorMonitor
  br label %BB1
BB1:
  %v1 = add i32 0, 9600
  store volatile i8 0, ptr inttoptr (i16 197 to ptr)
  store volatile i8 103, ptr inttoptr (i16 196 to ptr)
  store volatile i8 24, ptr inttoptr (i16 193 to ptr)
  store volatile i8 6, ptr inttoptr (i16 194 to ptr)
  %v5 = add i32 0, 13
  %v2 = add i32 0, 1
  %_t0 = load volatile i8, ptr inttoptr (i16 36 to ptr)
  %_t1 = or i8 %_t0, 32
  store volatile i8 %_t1, ptr inttoptr (i16 36 to ptr)
  %v6 = getelementptr i8, ptr addrspace(1) @espressomachine_string_0, i32 0
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_0)
  call void @__espressomachine_serial_write(i32 13)
  call void @__espressomachine_serial_write(i32 10)
  br label %BB2
BB2:
  %v7 = add i32 0, 0
  %v8 = call i32 @__espressomachine_gpio_analogread(i32 0)
  %v9 = sitofp i32 %v8 to float
  %v10 = fadd float 0.0, 0x4014000000000000
  %v11 = fmul float %v9, %v10
  %v12 = fadd float 0.0, 0x408FF80000000000
  %v13 = fdiv float %v11, %v12
  %v14 = fadd float 0.0, 0x406FE00000000000
  %v15 = fmul float %v13, %v14
  %v16 = fadd float 0.0, 0x4014000000000000
  %v17 = fdiv float %v15, %v16
  %v18 = fptosi float %v17 to i32
  %v19 = add i32 0, 13
  call void @__espressomachine_gpio_analogWrite(i32 13, i32 %v18)
  %v20 = getelementptr i8, ptr addrspace(1) @espressomachine_string_1, i32 0
  %v25 = fptosi float %v13 to i32
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_2)
  call void @__espressomachine_serial_print_int(i32 %v8)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_3)
  call void @__espressomachine_serial_print_int(i32 %v25)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_4)
  call void @__espressomachine_serial_print_int(i32 %v18)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_5)
  %v27 = getelementptr i8, ptr addrspace(1) @espressomachine_string_6, i32 0
  %v31 = call i32 @SensorMonitor_scaled(i32 %v8)
  %v34 = load i32, ptr @SensorMonitor_lastSample
  %v36 = load ptr, ptr @SensorMonitor_levels
  %v4 = add i32 0, 3
  %v37 = srem i32 %v8, 3
  ; array element load — arrays not supported on embedded targets
  %v39 = add i32 0, 0
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_7)
  call void @__espressomachine_serial_print_int(i32 %v31)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_8)
  call void @__espressomachine_serial_print_int(i32 %v34)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_9)
  call void @__espressomachine_serial_print_int(i32 %v39)
  call void @__espressomachine_serial_print_str(ptr addrspace(1) @espressomachine_string_5)
  %v40 = add i32 0, 1000
  call void @__espressomachine_delay_ms(i32 1000)
  br label %BB2
}

define void @SensorMonitor__clinit_() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 3
  ; ERROR: array allocation not supported on embedded targets
  %v4 = inttoptr i32 0 to ptr
  %v2 = add i32 0, 0
  %v3 = add i32 0, 0
  ; ERROR: array element store not supported on embedded targets
  %v6 = add i32 0, 1
  %v7 = add i32 0, 128
  ; ERROR: array element store not supported on embedded targets
  %v8 = add i32 0, 2
  %v9 = add i32 0, 255
  ; ERROR: array element store not supported on embedded targets
  store ptr %v4, ptr @SensorMonitor_levels
  ret void
}

@espressomachine_string_0 = private unnamed_addr addrspace(1) constant [31 x i8] c"=== Sensor Monitor Started ===\00"
@espressomachine_string_1 = private unnamed_addr addrspace(1) constant [24 x i8] c"Sensor:%d,V:%d,PWM:%d\0D\0A\00"
@espressomachine_string_2 = private unnamed_addr addrspace(1) constant [8 x i8] c"Sensor:\00"
@espressomachine_string_3 = private unnamed_addr addrspace(1) constant [4 x i8] c",V:\00"
@espressomachine_string_4 = private unnamed_addr addrspace(1) constant [6 x i8] c",PWM:\00"
@espressomachine_string_5 = private unnamed_addr addrspace(1) constant [3 x i8] c"\0D\0A\00"
@espressomachine_string_6 = private unnamed_addr addrspace(1) constant [17 x i8] c"c=%d,f=%d,a=%d\0D\0A\00"
@espressomachine_string_7 = private unnamed_addr addrspace(1) constant [3 x i8] c"c=\00"
@espressomachine_string_8 = private unnamed_addr addrspace(1) constant [4 x i8] c",f=\00"
@espressomachine_string_9 = private unnamed_addr addrspace(1) constant [4 x i8] c",a=\00"


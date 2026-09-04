; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare i32  @__espressomachine_gpio_analogread(i32 %pin)
declare void @__espressomachine_gpio_analogWrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)

define void @PwmFade__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define void @PwmFade_main() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 0
  %v2 = add i32 0, 1
  %v10 = add i32 0, 255
  %v11 = add i32 0, -1
  %v12 = add i32 0, 0
  %v13 = add i32 0, 1
  br label %BB4
BB2:
  %cond0 = icmp sgt i32 %v7, 0
  br i1 %cond0, label %BB4, label %BB5
BB3:
  br label %BB4
BB4:
  %v3 = phi i32 [ 0, %BB1 ], [ %v7, %BB2 ], [ 0, %BB5 ], [ 255, %BB3 ]
  %v4 = phi i32 [ 1, %BB1 ], [ %v4, %BB2 ], [ 1, %BB5 ], [ -1, %BB3 ]
  %v5 = add i32 0, 9
  call void @__espressomachine_gpio_analogWrite(i32 9, i32 %v3)
  %v6 = add i32 0, 10
  call void @__espressomachine_delay_ms(i32 10)
  %v7 = add i32 %v3, %v4
  %v8 = add i32 0, 255
  %v9 = sub i32 %v7, 255
  %cond1 = icmp slt i32 %v7, 255
  br i1 %cond1, label %BB2, label %BB3
BB5:
  br label %BB4
}


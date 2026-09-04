; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)


define void @ControlFlow__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define i32 @ControlFlow_test(i32 %v1) {
BB0:
  br label %BB1
BB1:
  %v2 = add i32 0, 10
  %v3 = sub i32 %v1, 10
  %cond0 = icmp sle i32 %v1, 10
  br i1 %cond0, label %BB2, label %BB3
BB2:
  %v4 = add i32 0, 0
  ret i32 0
BB3:
  %v5 = add i32 0, 1
  ret i32 1
}

define i32 @ControlFlow_count() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 0
  br label %BB4
BB2:
  ret i32 %v3
BB3:
  %v5 = add i32 0, 1
  %v6 = add i32 %v3, 1
  br label %BB4
BB4:
  %v3 = phi i32 [ 0, %BB1 ], [ %v6, %BB3 ]
  %v2 = add i32 0, 10
  %v4 = sub i32 %v3, 10
  %cond0 = icmp sge i32 %v3, 10
  br i1 %cond0, label %BB2, label %BB3
}

define void @ControlFlow_main() {
BB0:
  br label %BB1
BB1:
  %v1 = add i32 0, 5
  call i32 @ControlFlow_test(i32 5)
  call i32 @ControlFlow_count()
  ret void
}


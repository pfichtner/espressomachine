; EspressoMachine Phase 2 LLVM IR
; Generated from TeaVM 0.12.0 optimized IR

%java_lang_Enum_t = type { ptr, i32 }
%Direction_t = type { ptr, i32 }
%Pin_t = type { ptr, i32, i32 }

@Direction_NORTH = global %Direction_t zeroinitializer
@Direction_SOUTH = global %Direction_t zeroinitializer
@Direction_EAST = global %Direction_t zeroinitializer
@Direction_WEST = global %Direction_t zeroinitializer
@Direction_ENUM$VALUES = global ptr null
@Pin_LED = global %Pin_t zeroinitializer
@Pin_BUTTON = global %Pin_t zeroinitializer
@Pin_ENUM$VALUES = global ptr null

declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
declare void @__espressomachine_delay_ms(i32 %ms)


define void @EnumTest__init_(ptr %v0) {
BB0:
  br label %BB1
BB1:
  ret void
}

define i32 @EnumTest_ordinalOf(ptr %v1) {
BB0:
  br label %BB1
BB1:
  %v2 = add i32 0, 0
  ret i32 0
}

define i8 @EnumTest_isNorth(ptr %v1) {
BB0:
  br label %BB1
BB1:
  ; init_class Direction
  %v2 = getelementptr i8, ptr @Direction_NORTH, i32 0
  %cond0 = icmp ne ptr %v1, %v2
  br i1 %cond0, label %BB2, label %BB3
BB2:
  %v3 = add i32 0, 0
  %rettrunc1 = trunc i32 0 to i8
  ret i8 %rettrunc1
BB3:
  %v4 = add i32 0, 1
  %rettrunc2 = trunc i32 1 to i8
  ret i8 %rettrunc2
}

define i32 @EnumTest_encode(ptr %v1) {
BB0:
  br label %BB1
BB1:
  ; init_class Direction
  %v2 = getelementptr i8, ptr @Direction_NORTH, i32 0
  %cond0 = icmp ne ptr %v1, %v2
  br i1 %cond0, label %BB2, label %BB3
BB2:
  %v3 = getelementptr i8, ptr @Direction_SOUTH, i32 0
  %cond1 = icmp ne ptr %v1, %v3
  br i1 %cond1, label %BB4, label %BB5
BB3:
  %v8 = add i32 0, 0
  ret i32 0
BB4:
  %v4 = getelementptr i8, ptr @Direction_EAST, i32 0
  %cond2 = icmp ne ptr %v1, %v4
  br i1 %cond2, label %BB6, label %BB7
BB5:
  %v7 = add i32 0, 1
  ret i32 1
BB6:
  %v5 = add i32 0, 3
  ret i32 3
BB7:
  %v6 = add i32 0, 2
  ret i32 2
}

define i32 @EnumTest_pinNumber(ptr %v1) {
BB0:
  br label %BB1
BB1:
  %gep0 = getelementptr %Pin_t, ptr %v1, i32 0, i32 2
  %v2 = load i32, ptr %gep0
  ret i32 %v2
}

define void @EnumTest_main() {
BB0:
  br label %BB1
BB1:
  ; init_class Direction
  %v1 = getelementptr i8, ptr @Direction_NORTH, i32 0
  call i8 @EnumTest_isNorth(ptr %v1)
  %v2 = getelementptr i8, ptr @Direction_WEST, i32 0
  call i32 @EnumTest_encode(ptr %v2)
  ; init_class Pin
  ret void
}

define void @Direction__clinit_() {
BB0:
  br label %BB1
BB1:
  %v1 = getelementptr %Direction_t, ptr @Direction_NORTH, i32 0
  %v2 = inttoptr i32 0 to ptr
  %v3 = add i32 0, 0
  %gep0 = getelementptr %java_lang_Enum_t, ptr %v1, i32 0, i32 1
  store i32 0, ptr %gep0
  ; static object already initialized as global: @Direction_NORTH
  %v4 = getelementptr %Direction_t, ptr @Direction_SOUTH, i32 0
  %v5 = inttoptr i32 0 to ptr
  %v6 = add i32 0, 1
  %gep1 = getelementptr %java_lang_Enum_t, ptr %v4, i32 0, i32 1
  store i32 1, ptr %gep1
  ; static object already initialized as global: @Direction_SOUTH
  %v7 = getelementptr %Direction_t, ptr @Direction_EAST, i32 0
  %v8 = inttoptr i32 0 to ptr
  %v9 = add i32 0, 2
  %gep2 = getelementptr %java_lang_Enum_t, ptr %v7, i32 0, i32 1
  store i32 2, ptr %gep2
  ; static object already initialized as global: @Direction_EAST
  %v10 = getelementptr %Direction_t, ptr @Direction_WEST, i32 0
  %v11 = inttoptr i32 0 to ptr
  %v12 = add i32 0, 3
  %gep3 = getelementptr %java_lang_Enum_t, ptr %v10, i32 0, i32 1
  store i32 3, ptr %gep3
  ; static object already initialized as global: @Direction_WEST
  %v13 = add i32 0, 4
  %v14 = inttoptr i32 0 to ptr
  %v15 = add i32 0, 0
  %v16 = getelementptr i8, ptr @Direction_NORTH, i32 0
  %v18 = add i32 0, 1
  %v19 = getelementptr i8, ptr @Direction_SOUTH, i32 0
  %v20 = add i32 0, 2
  %v21 = getelementptr i8, ptr @Direction_EAST, i32 0
  %v22 = add i32 0, 3
  store ptr %v14, ptr @Direction_ENUM$VALUES
  ret void
}

define void @Direction__init_(ptr %v0, ptr %v1, i32 %v2) {
BB0:
  ; init_class Direction
  br label %BB1
BB1:
  ret void
}

define void @Pin__clinit_() {
BB0:
  br label %BB1
BB1:
  %v1 = getelementptr %Pin_t, ptr @Pin_LED, i32 0
  %v2 = inttoptr i32 0 to ptr
  %v3 = add i32 0, 0
  %v4 = add i32 0, 13
  %gep0 = getelementptr %java_lang_Enum_t, ptr %v1, i32 0, i32 1
  store i32 0, ptr %gep0
  ; static object already initialized as global: @Pin_LED
  %v5 = getelementptr %Pin_t, ptr @Pin_BUTTON, i32 0
  %v6 = inttoptr i32 0 to ptr
  %v7 = add i32 0, 1
  %v8 = add i32 0, 2
  %gep1 = getelementptr %java_lang_Enum_t, ptr %v5, i32 0, i32 1
  store i32 1, ptr %gep1
  ; static object already initialized as global: @Pin_BUTTON
  %v9 = add i32 0, 2
  %v10 = inttoptr i32 0 to ptr
  %v11 = add i32 0, 0
  %v12 = getelementptr i8, ptr @Pin_LED, i32 0
  %v14 = add i32 0, 1
  store ptr %v10, ptr @Pin_ENUM$VALUES
  ret void
}

define void @Pin__init_(ptr %v0, ptr %v1, i32 %v2, i32 %v3) {
BB0:
  ; init_class Pin
  br label %BB1
BB1:
  %gep0 = getelementptr %Pin_t, ptr %v0, i32 0, i32 2
  store i32 %v3, ptr %gep0
  ret void
}


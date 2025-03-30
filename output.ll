; ModuleID = 'shch_module'
source_filename = "shch_module"

@fmt = private constant [4 x i8] c"%d\0A\00"

define i32 @main() {
entry:
  %x = alloca i32, align 4
  store i32 10, ptr %x, align 4
  %x1 = load i32, ptr %x, align 4
  %addtmp = add i32 %x1, 1
  store i32 %addtmp, ptr %x, align 4
  %x2 = load i32, ptr %x, align 4
  %printfcall = call i32 (ptr, ...) @printf(ptr @fmt, i32 %x2)
  ret i32 0
}

declare i32 @printf(ptr, ...)

; ModuleID = 'shch_module'
source_filename = "shch_module"

@fmt_read = private constant [3 x i8] c"%d\00"
@fmt_read.1 = private constant [4 x i8] c"%lf\00"
@fmt = private constant [4 x i8] c"%f\0A\00"

define i32 @main() {
entry:
  %x = alloca i32, align 4
  %y = alloca double, align 8
  %scanfcall = call i32 (ptr, ...) @scanf(ptr @fmt_read, ptr %x)
  %scanfcall1 = call i32 (ptr, ...) @scanf(ptr @fmt_read.1, ptr %y)
  %z = alloca double, align 8
  %x2 = load i32, ptr %x, align 4
  %y3 = load double, ptr %y, align 8
  %fmultmp = fmul double %y3, 2.000000e+00
  %intToFloat = sitofp i32 %x2 to double
  %faddtmp = fadd double %intToFloat, %fmultmp
  store double %faddtmp, ptr %z, align 8
  %z4 = load double, ptr %z, align 8
  %printfcall = call i32 (ptr, ...) @printf(ptr @fmt, double %z4)
  ret i32 0
}

declare i32 @scanf(ptr, ...)

declare i32 @printf(ptr, ...)

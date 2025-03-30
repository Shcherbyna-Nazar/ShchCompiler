; ModuleID = 'shch_module'
source_filename = "shch_module"

@fmt_read = private constant [3 x i8] c"%d\00"
@fmt_read.1 = private constant [4 x i8] c"%lf\00"

declare i32 @scanf(ptr, ...)

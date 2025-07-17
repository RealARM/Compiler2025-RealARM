; Sum two integers read from stdin and print the result

@.prompt = private constant [17 x i8] c"Enter two nums: \00"
@.fmt_in  = private constant [6 x i8]  c"%d %d\00"
@.fmt_out = private constant [12 x i8] c"Sum is: %d\0A\00"

declare i32 @printf(ptr, ...)
declare i32 @scanf(ptr, ...)

define i32 @main() {
entry:
  %a = alloca i32
  %b = alloca i32
  
  ; print prompt
  %p0 = call i32 @printf(ptr @.prompt)
  
  ; read two ints
  %p1 = call i32 @scanf(ptr @.fmt_in, ptr %a, ptr %b)
  
  ; load values and add
  %v0 = load i32, ptr %a
  %v1 = load i32, ptr %b
  %sum = add i32 %v0, %v1
  
  ; print result
  %p2 = call i32 @printf(ptr @.fmt_out, i32 %sum)
  
  ret i32 0
} 
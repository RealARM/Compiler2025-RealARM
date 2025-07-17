; Program that prints a message then returns 42

@.str = private constant [27 x i8] c"Program returning value 42\00"

declare i32 @puts(ptr)

define i32 @main() {
entry:
  %call = call i32 @puts(ptr getelementptr inbounds ([27 x i8], ptr @.str, i64 0, i64 0))
  ret i32 42
} 
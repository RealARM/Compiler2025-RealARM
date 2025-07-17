; Simple Hello World for lli on Windows and other platforms

@.str = private constant [14 x i8] c"Hello, World!\00"

declare i32 @puts(ptr)

define i32 @main() {
entry:
  ; puts returns an int that we ignore
  %call = call i32 @puts(ptr getelementptr inbounds ([14 x i8], ptr @.str, i64 0, i64 0))
  ret i32 0
} 
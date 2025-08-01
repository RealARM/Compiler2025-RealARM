package Frontend.Lexer;

/**
 * SysY语言的词法单元类型
 */
public enum SysYTokenType {
    // 标识符
    IDENTIFIER,  // 标识符
    
    // 常量
    INT_CONST,   // 整型常量
    FLOAT_CONST, // 浮点常量
    
    // 整型常量的具体形式
    DEC_CONST,   // 十进制整型常量
    OCT_CONST,   // 八进制整型常量
    HEX_CONST,   // 十六进制整型常量
    
    // 浮点常量的具体形式
    DEC_FLOAT,   // 十进制浮点常量
    HEX_FLOAT,   // 十六进制浮点常量
    
    // 字符串常量(用于库函数参数)
    STRING_CONST,
    
    // 关键字
    CONST,       // const关键字
    INT,         // int关键字
    FLOAT,       // float关键字
    VOID,        // void关键字
    IF,          // if关键字
    ELSE,        // else关键字
    WHILE,       // while关键字
    BREAK,       // break关键字
    CONTINUE,    // continue关键字
    RETURN,      // return关键字
    
    // 运算符
    PLUS,        // +
    MINUS,       // -
    MULTIPLY,    // *
    DIVIDE,      // /
    MODULO,      // %
    
    // 关系运算符
    LESS,        // <
    GREATER,     // >
    LESS_EQUAL,  // <=
    GREATER_EQUAL,// >=
    EQUAL,       // ==
    NOT_EQUAL,   // !=
    
    // 逻辑运算符
    LOGICAL_AND, // &&
    LOGICAL_OR,  // ||
    LOGICAL_NOT, // !
    
    // 赋值运算符
    ASSIGN,      // =
    
    // 分隔符
    SEMICOLON,   // ;
    COMMA,       // ,
    LEFT_PAREN,  // (
    RIGHT_PAREN, // )
    LEFT_BRACKET,// [
    RIGHT_BRACKET,// ]
    LEFT_BRACE,  // {
    RIGHT_BRACE, // }
    
    // 特殊标记
    EOF          // 文件结束
} 
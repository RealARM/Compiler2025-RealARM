package Frontend.Lexer;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SysYLexer {
    private final PushbackReader reader;
    private int line = 1;
    private int column = 0;
    private char currentChar;
    private boolean reachedEOF = false;
    
    private static final Map<String, SysYTokenType> KEYWORDS = new HashMap<>();
    
    static {
        KEYWORDS.put("const", SysYTokenType.CONST);
        KEYWORDS.put("int", SysYTokenType.INT);
        KEYWORDS.put("float", SysYTokenType.FLOAT);
        KEYWORDS.put("void", SysYTokenType.VOID);
        KEYWORDS.put("if", SysYTokenType.IF);
        KEYWORDS.put("else", SysYTokenType.ELSE);
        KEYWORDS.put("while", SysYTokenType.WHILE);
        KEYWORDS.put("break", SysYTokenType.BREAK);
        KEYWORDS.put("continue", SysYTokenType.CONTINUE);
        KEYWORDS.put("return", SysYTokenType.RETURN);
    }
    
    public SysYLexer(Reader input) {
        this.reader = new PushbackReader(input);
    }
    
    public SysYLexer(String filePath) throws FileNotFoundException {
        this(new FileReader(filePath));
    }
    
    private void readChar() throws IOException {
        int read = reader.read();
        if (read == -1) {
            reachedEOF = true;
            return;
        }
        
        currentChar = (char) read;
        column++;
        
        if (currentChar == '\n') {
            line++;
            column = 0;
        }
    }
    
    private void unreadChar() throws IOException {
        if (!reachedEOF) {
            reader.unread(currentChar);
            column--;
            

        }
    }
    
    public TokenStream tokenize() throws IOException {
        TokenStream tokens = new TokenStream();
        SysYToken token;
        
        readChar();
        while (!reachedEOF) {
            token = nextToken();
            if (token != null) {
                tokens.add(token);
            }
        }
        
        tokens.add(new SysYToken(SysYTokenType.EOF, "", line, column));
        return tokens;
    }
    
    private SysYToken nextToken() throws IOException {
        skipWhitespace();
        
        if (reachedEOF) {
            return null;
        }
        
        if (currentChar == '/') {
            readChar();
            if (currentChar == '/') {
                skipSingleLineComment();
                return nextToken();
            } else if (currentChar == '*') {
                skipMultiLineComment();
                return nextToken();
            } else {
                unreadChar();
                currentChar = '/';
            }
        }
        
        int startLine = line;
        int startColumn = column;
        
        if (isAlpha(currentChar) || currentChar == '_') {
            return scanIdentifier(startLine, startColumn);
        }
        
        if (isDigit(currentChar) || (currentChar == '.' && peek() != null && isDigit(peek()))) {
            return scanNumber(startLine, startColumn);
        }
        
        if (currentChar == '"') {
            return scanString(startLine, startColumn);
        }

        switch (currentChar) {
            case '+':
                readChar();
                return new SysYToken(SysYTokenType.PLUS, "+", startLine, startColumn);
            case '-':
                readChar();
                return new SysYToken(SysYTokenType.MINUS, "-", startLine, startColumn);
            case '*':
                readChar();
                return new SysYToken(SysYTokenType.MULTIPLY, "*", startLine, startColumn);
            case '%':
                readChar();
                return new SysYToken(SysYTokenType.MODULO, "%", startLine, startColumn);
            case '!':
                if (peek() != null && peek() == '=') {
                    char next = peek();
                    readChar();
                    readChar();
                    return new SysYToken(SysYTokenType.NOT_EQUAL, "!=", startLine, startColumn);
                } else {
                    readChar();
                    return new SysYToken(SysYTokenType.LOGICAL_NOT, "!", startLine, startColumn);
                }
            case '&':
                if (peek() != null && peek() == '&') {
                    readChar();
                    readChar();
                    return new SysYToken(SysYTokenType.LOGICAL_AND, "&&", startLine, startColumn);
                }
                break;
            case '|':
                if (peek() != null && peek() == '|') {
                    readChar();
                    readChar();
                    return new SysYToken(SysYTokenType.LOGICAL_OR, "||", startLine, startColumn);
                }
                break;
            case '<':
                if (peek() != null && peek() == '=') {
                    readChar();
                    readChar();
                    return new SysYToken(SysYTokenType.LESS_EQUAL, "<=", startLine, startColumn);
                } else {
                    readChar();
                    return new SysYToken(SysYTokenType.LESS, "<", startLine, startColumn);
                }
            case '>':
                if (peek() != null && peek() == '=') {
                    readChar();
                    readChar();
                    return new SysYToken(SysYTokenType.GREATER_EQUAL, ">=", startLine, startColumn);
                } else {
                    readChar();
                    return new SysYToken(SysYTokenType.GREATER, ">", startLine, startColumn);
                }
            case '=':
                if (peek() != null && peek() == '=') {
                    readChar();
                    readChar();
                    return new SysYToken(SysYTokenType.EQUAL, "==", startLine, startColumn);
                } else {
                    readChar();
                    return new SysYToken(SysYTokenType.ASSIGN, "=", startLine, startColumn);
                }
            case ';':
                readChar();
                return new SysYToken(SysYTokenType.SEMICOLON, ";", startLine, startColumn);
            case ',':
                readChar();
                return new SysYToken(SysYTokenType.COMMA, ",", startLine, startColumn);
            case '(':
                readChar();
                return new SysYToken(SysYTokenType.LEFT_PAREN, "(", startLine, startColumn);
            case ')':
                readChar();
                return new SysYToken(SysYTokenType.RIGHT_PAREN, ")", startLine, startColumn);
            case '[':
                readChar();
                return new SysYToken(SysYTokenType.LEFT_BRACKET, "[", startLine, startColumn);
            case ']':
                readChar();
                return new SysYToken(SysYTokenType.RIGHT_BRACKET, "]", startLine, startColumn);
            case '{':
                readChar();
                return new SysYToken(SysYTokenType.LEFT_BRACE, "{", startLine, startColumn);
            case '}':
                readChar();
                return new SysYToken(SysYTokenType.RIGHT_BRACE, "}", startLine, startColumn);
            case '/':
                readChar();
                return new SysYToken(SysYTokenType.DIVIDE, "/", startLine, startColumn);
        }

        throw new IOException("Unexpected character '" + currentChar + "' at line " + line + ", column " + column);
    }
    
    /**
     * 跳过空白字符
     */
    private void skipWhitespace() throws IOException {
        while (!reachedEOF && Character.isWhitespace(currentChar)) {
            readChar();
        }
    }
    
    /**
     * 跳过单行注释
     */
    private void skipSingleLineComment() throws IOException {
        // 跳过当前行，直到遇到换行符或文件结束
        while (!reachedEOF && currentChar != '\n') {
            readChar();
        }
    }
    
    /**
     * 跳过多行注释
     */
    private void skipMultiLineComment() throws IOException {
        // 已经读取了 '/*'，现在查找 '*/'
        readChar(); // 读取 '/*' 后的第一个字符
        
        while (!reachedEOF) {
            if (currentChar == '*') {
                readChar();
                if (currentChar == '/') {
                    readChar(); // 读取 '*/' 后的下一个字符
                    return;
                }
            } else {
                readChar();
            }
        }
    }
    
    /**
     * 检查字符是否为字母
     */
    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
    
    /**
     * 检查字符是否为数字
     */
    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
    
    /**
     * 检查字符是否为十六进制数字
     */
    private boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
    
    /**
     * 检查字符是否为字母、数字或下划线
     */
    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c) || c == '_';
    }
    
    /**
     * 扫描标识符或关键字
     */
    private SysYToken scanIdentifier(int startLine, int startColumn) throws IOException {
        StringBuilder sb = new StringBuilder();
        
        // 收集标识符的所有字符
        do {
            sb.append(currentChar);
            readChar();
        } while (!reachedEOF && isAlphaNumeric(currentChar));
        
        String lexeme = sb.toString();
        
        // 检查是否为关键字
        SysYTokenType type = KEYWORDS.getOrDefault(lexeme, SysYTokenType.IDENTIFIER);
        
        return new SysYToken(type, lexeme, startLine, startColumn);
    }
    
    /**
     * 扫描数字常量（整型或浮点型）
     */
    private SysYToken scanNumber(int startLine, int startColumn) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean isFloat = false;
        boolean isHex = false;
        boolean isOct = false;
        
        // 检查是否为十六进制或八进制
        if (currentChar == '0') {
            sb.append(currentChar);
            readChar();
            
            if (currentChar == 'x' || currentChar == 'X') {
                // 十六进制
                sb.append(currentChar);
                readChar();
                isHex = true;
                
                // 读取十六进制数字和小数点，允许形如 0x.8p+0 / 0x8.p0 / 0x8p0
                boolean seenHexDigit = false;
                while (!reachedEOF && (isHexDigit(currentChar) || currentChar == '.')) {
                    if (isHexDigit(currentChar)) seenHexDigit = true;
                    sb.append(currentChar);
                    readChar();
                }

                if (!seenHexDigit) {
                    throw new IOException("Invalid hexadecimal number format at line " + line + ", column " + column);
                }
                
                // 检查是否为十六进制浮点数
                if (currentChar == '.') {
                    // 若小数点尚未被读取（意味着之前没有读到'.')
                    isFloat = true;
                    sb.append(currentChar);
                    readChar();

                    while (!reachedEOF && isHexDigit(currentChar)) {
                        sb.append(currentChar);
                        readChar();
                    }
                }
                
                // 处理十六进制浮点数的指数部分
                if (currentChar == 'p' || currentChar == 'P') {
                    isFloat = true;
                    sb.append(currentChar);
                    readChar();
                    
                    // 指数符号
                    if (currentChar == '+' || currentChar == '-') {
                        sb.append(currentChar);
                        readChar();
                    }
                    
                    // 读取指数值
                    if (!isDigit(currentChar)) {
                        throw new IOException("Invalid hexadecimal floating point exponent at line " + line + ", column " + column);
                    }
                    
                    while (!reachedEOF && isDigit(currentChar)) {
                        sb.append(currentChar);
                        readChar();
                    }
                }
            } else if (currentChar == '.') {
                // 处理形如 0.xxx 的十进制浮点数
                isFloat = true;
                sb.append(currentChar); // '.'
                readChar();

                // 读取小数部分
                while (!reachedEOF && isDigit(currentChar)) {
                    sb.append(currentChar);
                    readChar();
                }

                // 处理可选指数部分 0.xxx[eE][+-]?digits
                if (currentChar == 'e' || currentChar == 'E') {
                    sb.append(currentChar);
                    readChar();

                    // 指数符号
                    if (currentChar == '+' || currentChar == '-') {
                        sb.append(currentChar);
                        readChar();
                    }

                    if (!isDigit(currentChar)) {
                        throw new IOException("Invalid decimal floating point exponent at line " + line + ", column " + column);
                    }

                    while (!reachedEOF && isDigit(currentChar)) {
                        sb.append(currentChar);
                        readChar();
                    }
                }
            } else if (currentChar == 'e' || currentChar == 'E') {
                // 处理形如 0e10, 0E-1 的十进制浮点数
                isFloat = true;
                sb.append(currentChar);
                readChar();

                // 指数符号
                if (currentChar == '+' || currentChar == '-') {
                    sb.append(currentChar);
                    readChar();
                }

                if (!isDigit(currentChar)) {
                    throw new IOException("Invalid decimal floating point exponent at line " + line + ", column " + column);
                }

                while (!reachedEOF && isDigit(currentChar)) {
                    sb.append(currentChar);
                    readChar();
                }
            } else if (isDigit(currentChar) && currentChar != '8' && currentChar != '9') {
                // 八进制
                isOct = true;
                
                // 读取八进制数字部分，但若后续出现'.'或'e/E'，则按十进制浮点处理
                while (!reachedEOF && isDigit(currentChar) && currentChar != '8' && currentChar != '9') {
                    sb.append(currentChar);
                    readChar();
                }

                // 若出现 8/9，视为十进制常量而非八进制
                if (isDigit(currentChar) && (currentChar == '8' || currentChar == '9')) {
                    isOct = false;
                }

                // 检查是否转成浮点: 0xx.xxx 或 0xxe±n
                if (currentChar == '.' || currentChar == 'e' || currentChar == 'E') {
                    isFloat = true;
                    isOct = false;

                    if (currentChar == '.') {
                        sb.append(currentChar);
                        readChar();

                        while (!reachedEOF && isDigit(currentChar)) {
                            sb.append(currentChar);
                            readChar();
                        }
                    }

                    // 指数部分
                    if (currentChar == 'e' || currentChar == 'E') {
                        sb.append(currentChar);
                        readChar();

                        if (currentChar == '+' || currentChar == '-') {
                            sb.append(currentChar);
                            readChar();
                        }

                        if (!isDigit(currentChar)) {
                            throw new IOException("Invalid decimal floating point exponent at line " + line + ", column " + column);
                        }

                        while (!reachedEOF && isDigit(currentChar)) {
                            sb.append(currentChar);
                            readChar();
                        }
                    }
                } else if (isOct && isDigit(currentChar)) {
                    // 如果仍是数字(8/9)，抛 octal 格式错误
                    throw new IOException("Invalid octal number format at line " + line + ", column " + column);
                }
            }
            // 否则就是单个0
        } else {
            // 十进制数字
            
            // 读取整数部分
            while (!reachedEOF && isDigit(currentChar)) {
                sb.append(currentChar);
                readChar();
            }
            
            // 检查是否为十进制浮点数
            if (currentChar == '.') {
                isFloat = true;
                sb.append(currentChar);
                readChar();
                
                // 读取小数部分
                while (!reachedEOF && isDigit(currentChar)) {
                    sb.append(currentChar);
                    readChar();
                }
            }
            
            // 处理科学计数法表示的浮点数
            if (currentChar == 'e' || currentChar == 'E') {
                isFloat = true;
                sb.append(currentChar);
                readChar();
                
                // 指数符号
                if (currentChar == '+' || currentChar == '-') {
                    sb.append(currentChar);
                    readChar();
                }
                
                // 读取指数值
                if (!isDigit(currentChar)) {
                    throw new IOException("Invalid decimal floating point exponent at line " + line + ", column " + column);
                }
                
                while (!reachedEOF && isDigit(currentChar)) {
                    sb.append(currentChar);
                    readChar();
                }
            }
        }
        
        String lexeme = sb.toString();
        SysYTokenType type;
        Object value = null;
        
        if (isFloat) {
            // 浮点数
            type = isHex ? SysYTokenType.HEX_FLOAT : SysYTokenType.DEC_FLOAT;
            value = Float.parseFloat(lexeme);
            return new SysYToken(type, lexeme, startLine, startColumn, value);
        } else {
            // 整数
            if (isHex) {
                type = SysYTokenType.HEX_CONST;
                value = Integer.parseInt(lexeme.substring(2), 16);
            } else if (isOct) {
                type = SysYTokenType.OCT_CONST;
                value = Integer.parseInt(lexeme, 8);
            } else {
                type = SysYTokenType.DEC_CONST;
                value = Integer.parseInt(lexeme);
            }
            return new SysYToken(type, lexeme, startLine, startColumn, value);
        }
    }
    
    /**
     * 扫描字符串常量
     */
    private SysYToken scanString(int startLine, int startColumn) throws IOException {
        StringBuilder sb = new StringBuilder();
        
        // 添加开始的双引号
        sb.append(currentChar);
        readChar();
        
        // 读取字符串内容直到遇到结束的双引号
        while (!reachedEOF && currentChar != '"') {
            // 处理转义字符
            if (currentChar == '\\') {
                sb.append(currentChar);
                readChar();
                
                if (reachedEOF) {
                    throw new IOException("Unexpected end of file in string literal at line " + line + ", column " + column);
                }
                
                // 支持的转义序列：\n, \t, \\, \", \'
                switch (currentChar) {
                    case 'n':
                    case 't':
                    case '\\':
                    case '"':
                    case '\'':
                        sb.append(currentChar);
                        break;
                    default:
                        throw new IOException("Invalid escape sequence \\" + currentChar + " in string literal at line " + line + ", column " + column);
                }
            } else {
                sb.append(currentChar);
            }
            
            readChar();
        }
        
        if (reachedEOF) {
            throw new IOException("Unexpected end of file in string literal at line " + line + ", column " + column);
        }
        
        // 添加结束的双引号
        sb.append(currentChar);
        readChar();
        
        String lexeme = sb.toString();
        // 字符串值是去掉两端引号的内容
        String value = lexeme.substring(1, lexeme.length() - 1);
        
        return new SysYToken(SysYTokenType.STRING_CONST, lexeme, startLine, startColumn, value);
    }
    
    /**
     * 预览下一个字符，但不前进
     */
    private Character peek() throws IOException {
        if (reachedEOF) {
            return null;
        }
        
        int nextChar = reader.read();
        if (nextChar == -1) {
            return null;
        }
        
        reader.unread(nextChar);
        return (char) nextChar;
    }
} 
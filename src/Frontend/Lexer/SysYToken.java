package Frontend.Lexer;


public class SysYToken {
    private final SysYTokenType type;
    private final String lexeme;
    private final int line;
    private final int column;
    private Object value; // 常量的实际值

    public SysYToken(SysYTokenType type, String lexeme, int line, int column) {
        this.type = type;
        this.lexeme = lexeme;
        this.line = line;
        this.column = column;
    }

    public SysYToken(SysYTokenType type, String lexeme, int line, int column, Object value) {
        this(type, lexeme, line, column);
        this.value = value;
    }

    public SysYTokenType getType() {
        return type;
    }

    public String getLexeme() {
        return lexeme;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return String.format("Token(%s, '%s', line=%d, col=%d%s)", 
            type, lexeme, line, column,
            value != null ? ", value=" + value : "");
    }
} 
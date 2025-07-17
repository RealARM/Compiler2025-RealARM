package Frontend;

/**
 * SysY语言的词法单元
 */
public class SysYToken {
    private final SysYTokenType type;  // 词法单元类型
    private final String lexeme;       // 词法单元的文本表示
    private final int line;            // 行号
    private final int column;          // 列号
    private Object value;              // 对于常量，存储其实际值

    /**
     * 创建一个基本的词法单元
     */
    public SysYToken(SysYTokenType type, String lexeme, int line, int column) {
        this.type = type;
        this.lexeme = lexeme;
        this.line = line;
        this.column = column;
    }

    /**
     * 创建一个带有值的词法单元（用于常量）
     */
    public SysYToken(SysYTokenType type, String lexeme, int line, int column, Object value) {
        this(type, lexeme, line, column);
        this.value = value;
    }

    /**
     * 获取词法单元类型
     */
    public SysYTokenType getType() {
        return type;
    }

    /**
     * 获取词法单元的文本表示
     */
    public String getLexeme() {
        return lexeme;
    }

    /**
     * 获取行号
     */
    public int getLine() {
        return line;
    }

    /**
     * 获取列号
     */
    public int getColumn() {
        return column;
    }

    /**
     * 获取词法单元的值（如果是常量）
     */
    public Object getValue() {
        return value;
    }

    /**
     * 设置词法单元的值
     */
    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return String.format("Token(%s, '%s', line=%d, col=%d%s)", 
            type, lexeme, line, column,
            value != null ? ", value=" + value : "");
    }
    
    /**
     * 返回用于测试输出的字符串表示
     * 格式: TOKENTYPE lexeme
     */
    public String toTestString() {
        return String.format("%s %s", type, lexeme);
    }
} 
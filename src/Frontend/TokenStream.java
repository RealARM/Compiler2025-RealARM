package Frontend;

import java.util.ArrayList;
import java.util.List;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 词法单元流，用于管理和访问词法分析器生成的词法单元序列
 */
public class TokenStream {
    private final List<SysYToken> tokens = new ArrayList<>();
    private int currentPosition = 0;

    /**
     * 添加一个词法单元到流中
     */
    public void add(SysYToken token) {
        tokens.add(token);
    }

    /**
     * 添加多个词法单元到流中
     */
    public void addAll(List<SysYToken> tokens) {
        this.tokens.addAll(tokens);
    }

    /**
     * 获取当前位置的词法单元，但不前进
     */
    public SysYToken peek() {
        if (currentPosition >= tokens.size()) {
            return null;
        }
        return tokens.get(currentPosition);
    }

    /**
     * 获取当前位置之后指定偏移量的词法单元，但不前进
     */
    public SysYToken peek(int offset) {
        int index = currentPosition + offset;
        if (index >= tokens.size() || index < 0) {
            return null;
        }
        return tokens.get(index);
    }

    /**
     * 获取当前位置的词法单元，并将位置前进一步
     */
    public SysYToken next() {
        if (currentPosition >= tokens.size()) {
            return null;
        }
        return tokens.get(currentPosition++);
    }

    /**
     * 如果当前词法单元的类型匹配指定类型，则前进并返回该词法单元
     * 否则返回null
     */
    public SysYToken match(SysYTokenType type) {
        if (check(type)) {
            return next();
        }
        return null;
    }

    /**
     * 如果当前词法单元的类型匹配任一指定类型，则前进并返回该词法单元
     * 否则返回null
     */
    public SysYToken matchAny(SysYTokenType... types) {
        for (SysYTokenType type : types) {
            SysYToken token = match(type);
            if (token != null) {
                return token;
            }
        }
        return null;
    }

    /**
     * 期望当前词法单元类型为指定类型，如果匹配则前进并返回该词法单元
     * 否则抛出异常
     */
    public SysYToken expect(SysYTokenType type) throws SyntaxException {
        if (check(type)) {
            return next();
        }
        SysYToken token = peek();
        throw new SyntaxException(
            String.format("Expected token type %s but got %s at line %d, column %d", 
                type, token.getType(), token.getLine(), token.getColumn())
        );
    }

    /**
     * 期望当前词法单元类型为指定类型之一，如果匹配则前进并返回该词法单元
     * 否则抛出异常
     */
    public SysYToken expectAny(SysYTokenType... types) throws SyntaxException {
        for (SysYTokenType type : types) {
            if (check(type)) {
                return next();
            }
        }
        
        SysYToken token = peek();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                expected.append(" or ");
            }
            expected.append(types[i]);
        }
        
        throw new SyntaxException(
            String.format("Expected token type %s but got %s at line %d, column %d", 
                expected, token.getType(), token.getLine(), token.getColumn())
        );
    }

    /**
     * 检查当前词法单元是否为指定类型
     */
    public boolean check(SysYTokenType type) {
        SysYToken token = peek();
        return token != null && token.getType() == type;
    }

    /**
     * 检查当前词法单元是否为指定类型之一
     */
    public boolean checkAny(SysYTokenType... types) {
        for (SysYTokenType type : types) {
            if (check(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 回退一个位置
     */
    public void backup() {
        if (currentPosition > 0) {
            currentPosition--;
        }
    }

    /**
     * 重置位置到流的开始
     */
    public void reset() {
        currentPosition = 0;
    }

    /**
     * 检查是否还有更多词法单元
     */
    public boolean hasMore() {
        return currentPosition < tokens.size();
    }

    /**
     * 获取所有词法单元数量
     */
    public int size() {
        return tokens.size();
    }

    /**
     * 获取当前位置
     */
    public int position() {
        return currentPosition;
    }

    /**
     * 设置当前位置
     */
    public void setPosition(int position) {
        if (position >= 0 && position <= tokens.size()) {
            this.currentPosition = position;
        }
    }
    
    /**
     * 返回用于测试的字符串表示，格式类似于:
     * =================== Token List ===================
     * 1: INTTK int
     * 2: IDENFR main
     * ...
     * =================================================
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=================== Token List ===================\n");
        
        int lineNumber = 1;
        // 不包括EOF标记
        for (int i = 0; i < tokens.size() - 1; i++) {
            SysYToken token = tokens.get(i);
            sb.append(String.format("%d: %s\n", lineNumber++, token.toTestString()));
        }
        
        sb.append("\n=================================================\n");
        return sb.toString();
    }
    
    /**
     * 打印词法单元列表到标准输出
     */
    public void print() {
        System.out.println(this.toString());
    }
    
    /**
     * 打印词法单元列表到文件
     * @param filePath 输出文件路径
     * @throws IOException 如果写入失败
     */
    public void printToFile(String filePath) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.print(this.toString());
        }
    }
}

/**
 * 语法异常类
 */
class SyntaxException extends Exception {
    public SyntaxException(String message) {
        super(message);
    }
} 
package Frontend.Lexer;

import java.util.ArrayList;
import java.util.List;

import Frontend.Parser.SyntaxException;

/**
 * 词法单元流
 */
public class TokenStream {
    private final List<SysYToken> tokens = new ArrayList<>();
    private int currentPosition = 0;

    public void add(SysYToken token) {
        tokens.add(token);
    }

    public void addAll(List<SysYToken> tokens) {
        this.tokens.addAll(tokens);
    }

    // 获取当前位置的词法单元，但不前进
    public SysYToken peek() {
        if (currentPosition >= tokens.size()) {
            return null;
        }
        return tokens.get(currentPosition);
    }

    public SysYToken peek(int offset) {
        int index = currentPosition + offset;
        if (index >= tokens.size() || index < 0) {
            return null;
        }
        return tokens.get(index);
    }

    // 获取当前位置的词法单元，并前进
    public SysYToken next() {
        if (currentPosition >= tokens.size()) {
            return null;
        }
        return tokens.get(currentPosition++);
    }

    // 匹配指定类型，成功则前进并返回词法单元
    public SysYToken match(SysYTokenType type) {
        if (check(type)) {
            return next();
        }
        return null;
    }

    public SysYToken matchAny(SysYTokenType... types) {
        for (SysYTokenType type : types) {
            SysYToken token = match(type);
            if (token != null) {
                return token;
            }
        }
        return null;
    }

    // 期望指定类型，不匹配则抛出异常
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

    public boolean check(SysYTokenType type) {
        SysYToken token = peek();
        return token != null && token.getType() == type;
    }

    public boolean checkAny(SysYTokenType... types) {
        for (SysYTokenType type : types) {
            if (check(type)) {
                return true;
            }
        }
        return false;
    }

    public void backup() {
        if (currentPosition > 0) {
            currentPosition--;
        }
    }

    public void reset() {
        currentPosition = 0;
    }

    public boolean hasMore() {
        return currentPosition < tokens.size();
    }

    public int size() {
        return tokens.size();
    }

    public int position() {
        return currentPosition;
    }

    public void setPosition(int position) {
        if (position >= 0 && position <= tokens.size()) {
            this.currentPosition = position;
        }
    }
} 
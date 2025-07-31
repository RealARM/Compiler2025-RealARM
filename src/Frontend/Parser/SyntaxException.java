package Frontend.Parser;

/**
 * 语法分析异常
 */
public class SyntaxException extends Exception {
    public SyntaxException(String message) {
        super(message);
    }
    
    public SyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
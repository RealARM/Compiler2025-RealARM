import Frontend.SysYLexer;
import Frontend.TokenStream;
import Frontend.SysYToken;
import Frontend.SysYTokenType;

import java.io.FileReader;
import java.io.IOException;

/**
 * 编译器入口，目前仅执行词法分析并打印Token序列
 */
public class Compiler {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java Compiler <source_file.sy>");
            System.exit(1);
        }

        String sourcePath = args[0];
        try {
            SysYLexer lexer = new SysYLexer(new FileReader(sourcePath));
            TokenStream tokens = lexer.tokenize();

            // 打印所有Token（不包括EOF）
            while (tokens.hasMore()) {
                SysYToken token = tokens.next();
                if (token.getType() == SysYTokenType.EOF) {
                    break;
                }
                System.out.println(token);
            }
        } catch (IOException e) {
            System.err.println("Lexical analysis failed: " + e.getMessage());
        }
    }
} 
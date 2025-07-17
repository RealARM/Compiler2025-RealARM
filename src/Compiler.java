import Frontend.SysYLexer;
import Frontend.SysYParser;
import Frontend.SyntaxTree;
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
            TokenStream tokenStream = lexer.tokenize();

            // 可选：打印Token序列进行调试
            /*
            tokenStream.reset();
            while (tokenStream.hasMore()) {
                SysYToken t = tokenStream.next();
                System.out.println(t);
                if (t.getType() == SysYTokenType.EOF) break;
            }
            tokenStream.reset();
            */

            // 语法分析
            SysYParser parser = new SysYParser(tokenStream);
            SyntaxTree.CompilationUnit ast = parser.parseCompilationUnit();

            System.out.println("Parse succeeded. Top-level definitions: " + ast.getDefs().size());
        } catch (Exception e) {
            System.err.println("Compilation failed: " + e.getMessage());
        }
    }
} 
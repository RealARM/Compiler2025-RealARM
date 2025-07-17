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
            System.err.println("Usage: java src/Compiler.java [--parse] <source_file.sy>");
            System.exit(1);
        }

        boolean doParse = false;
        String sourcePath;
        if (args[0].equals("--parse") || args[0].equals("-p")) {
            if (args.length < 2) {
                System.err.println("Usage: java src/Compiler.java [--parse] <source_file.sy>");
                System.exit(1);
            }
            doParse = true;
            sourcePath = args[1];
        } else {
            // 默认词法分析模式
            sourcePath = args[0];
        }

        try {
            SysYLexer lexer = new SysYLexer(new FileReader(sourcePath));
            TokenStream tokenStream = lexer.tokenize();

            if (!doParse) {
                // 词法分析模式：打印所有 Token
                tokenStream.reset();
                while (tokenStream.hasMore()) {
                    SysYToken t = tokenStream.next();
                    System.out.println(t);
                    if (t.getType() == SysYTokenType.EOF) break;
                }
                return;
            }

            // 语法分析模式
            SysYParser parser = new SysYParser(tokenStream);
            SyntaxTree.CompilationUnit ast = parser.parseCompilationUnit();
            System.out.println("Parse succeeded. Top-level definitions: " + ast.getDefs().size());
        } catch (Exception e) {
            System.err.println("Compilation failed: " + e.getMessage());
        }
    }
} 
import Frontend.SysYLexer;
import Frontend.SysYParser;
import Frontend.SyntaxTree;
import Frontend.TokenStream;
import Frontend.SysYToken;
import Frontend.SysYTokenType;
import IR.Module;
import IR.Visitor.IRVisitor;

import java.io.FileReader;
import java.io.IOException;

/**
 * 编译器入口，目前仅执行词法分析并打印Token序列
 */
public class Compiler {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java src/Compiler.java [--parse|--ast|--ir] <source_file.sy>");
            System.exit(1);
        }

        boolean doParse = false;
        boolean printAST = false;
        boolean generateIR = false;
        String sourcePath;
        
        if (args[0].equals("--parse") || args[0].equals("-p")) {
            if (args.length < 2) {
                System.err.println("Usage: java src/Compiler.java [--parse|--ast|--ir] <source_file.sy>");
                System.exit(1);
            }
            doParse = true;
            sourcePath = args[1];
        } else if (args[0].equals("--ast") || args[0].equals("-a")) {
            if (args.length < 2) {
                System.err.println("Usage: java src/Compiler.java [--parse|--ast|--ir] <source_file.sy>");
                System.exit(1);
            }
            doParse = true;
            printAST = true;
            sourcePath = args[1];
        } else if (args[0].equals("--ir") || args[0].equals("-i")) {
            if (args.length < 2) {
                System.err.println("Usage: java src/Compiler.java [--parse|--ast|--ir] <source_file.sy>");
                System.exit(1);
            }
            doParse = true;
            generateIR = true;
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
            
            if (printAST) {
                // 打印完整AST树
                parser.printSyntaxTree(ast);
                return;
            } else if (!generateIR) {
                System.out.println("Parse succeeded. Top-level definitions: " + ast.getDefs().size());
                return;
            }
            
            // IR生成模式
            IRVisitor irVisitor = new IRVisitor();
            Module irModule = irVisitor.visitCompilationUnit(ast);
            
            System.out.println("IR generation succeeded.");
            System.out.println("Module contains:");
            System.out.println("  - " + irModule.functions().size() + " functions");
            System.out.println("  - " + irModule.globalVars().size() + " global variables");
            System.out.println("  - " + irModule.libFunctions().size() + " library functions");
            
            // 打印IR（可选）
            // TODO: 实现IR打印功能
            
        } catch (Exception e) {
            System.err.println("Compilation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 
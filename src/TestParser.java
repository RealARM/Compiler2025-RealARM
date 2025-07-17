import Frontend.SysYLexer;
import Frontend.TokenStream;
import Frontend.SysYParser;
import Frontend.SyntaxTree.CompilationUnit;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 用于测试语法分析器的类，供评测脚本调用。
 * 调用方式: java TestParser <输入文件> <输出文件>
 */
public class TestParser {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法: java TestParser <输入文件> <输出文件>");
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args[1];

        try {
            // 词法分析 -> TokenStream
            SysYLexer lexer = new SysYLexer(inputFile);
            TokenStream tokenStream = lexer.tokenize();

            // 语法分析
            SysYParser parser = new SysYParser(tokenStream);
            CompilationUnit ast = parser.parseCompilationUnit();

            // 将结果写入文件
            try (PrintWriter writer = new PrintWriter(outputFile)) {
                writer.println("====================== AST ======================");
                writer.println(ast);
                writer.println("=================================================");
            }
        } catch (FileNotFoundException e) {
            System.err.println("找不到输入文件: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("IO 错误: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("语法分析错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
} 
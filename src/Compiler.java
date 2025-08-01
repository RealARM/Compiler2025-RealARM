import Frontend.Lexer.*;
import Frontend.Parser.*;
import MiddleEnd.IR.IRPrinter;
import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.OptimizeManager;
import MiddleEnd.IR.Visitor.IRVisitor;
import Backend.AArch64Visitor;

import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

public class Compiler {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("错误: 请提供源文件路径");
            System.exit(1);
        }

        String sourcePath = args[0];
        String targetPath = args.length > 1 ? args[1] : null;
        String armOutputPath = args.length > 2 ? args[2] : "armv8_backend.s";

        
        boolean lexOnly = false;
        boolean printAST = false;
        boolean generateIR = true;
        boolean printIR = true;
        boolean generateARM = true;
        int optimizationLevel = 1;
        boolean debug = true;
        
        try {
            SysYLexer lexer = new SysYLexer(new FileReader(sourcePath));
            TokenStream tokenStream = lexer.tokenize();

            if (lexOnly) {
                tokenStream.reset();
                while (tokenStream.hasMore()) {
                    SysYToken t = tokenStream.next();
                    System.out.println(t);
                    if (t.getType() == SysYTokenType.EOF) break;
                }
                return;
            }

            SysYParser parser = new SysYParser(tokenStream);
            SyntaxTree.CompilationUnit ast = parser.parseCompilationUnit();
            
            if (printAST) {
                parser.printSyntaxTree(ast);
                return;
            }
            
            if (!generateIR) {
                System.out.println("语法分析成功。顶层定义数量: " + ast.getDefs().size());
                return;
            }
            
            IRVisitor irVisitor = new IRVisitor();
            Module irModule = irVisitor.visitCompilationUnit(ast);
            
            System.out.println("IR生成成功。");
            System.out.println("模块包含:");
            System.out.println("  - " + irModule.functions().size() + " 个函数");
            System.out.println("  - " + irModule.globalVars().size() + " 个全局变量");
            System.out.println("  - " + irModule.libFunctions().size() + " 个库函数");
            
            if (optimizationLevel > 0) {
                runOptimizations(irModule, optimizationLevel, debug);
            }
            
            if (printIR) {
                PrintStream outputStream = System.out;
                if (targetPath != null) {
                    try {
                        outputStream = new PrintStream(new FileOutputStream(targetPath));
                        System.out.println("\nIR代码已输出到文件: " + targetPath);
                    } catch (IOException e) {
                        System.err.println("无法创建输出文件: " + e.getMessage());
                        System.out.println("将使用标准输出...");
                    }
                } else {
                System.out.println("\n生成的IR代码:");
                }
                
                IRPrinter printer = new IRPrinter(outputStream);
                printer.printModule(irModule);
                
                if (outputStream != System.out) {
                    outputStream.close();
                }
            }
            
            if (generateARM) {
                generateARMCode(irModule, armOutputPath);
            }
            
        } catch (Exception e) {
            System.err.println("编译失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void generateARMCode(Module irModule, String outputPath) {
        try {
            System.out.println("\n开始生成ARM汇编代码...");
            
            AArch64Visitor armv8CodeGen = new AArch64Visitor(irModule);
            
            armv8CodeGen.run();
            
            if (outputPath != null) {
                armv8CodeGen.dump(outputPath);
                System.out.println("ARM汇编代码已输出到文件: " + outputPath);
            } else {
                System.out.println("ARM汇编代码生成完成，但未指定输出文件。");
            }
        } catch (Exception e) {
            System.err.println("ARM代码生成失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void runOptimizations(Module irModule, int optimizationLevel, boolean debug) {
        System.out.println("\n正在运行优化 (级别 O" + optimizationLevel + ")...");
        
        OptimizeManager.getInstance().runAllOptimizers(irModule);
        
        System.out.println("优化完成。");
    }
} 
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

        String sourcePath = null;
        String outputPath = null;
        boolean generateAsm = false;
        int optimizationLevel = 0;
        boolean debug = false;
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-S":
                    generateAsm = true;
                    break;
                case "-o":
                    if (i + 1 < args.length) {
                        outputPath = args[++i];
                    } else {
                        System.err.println("错误: -o 选项需要指定输出文件");
                        System.exit(1);
                    }
                    break;
                case "-O1":
                    optimizationLevel = 1;
                    break;
                case "-O0":
                    optimizationLevel = 0;
                    break;
                default:
                    if (!arg.startsWith("-")) {
                        sourcePath = arg;
                    } else {
                        System.err.println("警告: 未知选项 " + arg);
                    }
                    break;
            }
        }
        
        if (sourcePath == null) {
            System.err.println("错误: 请提供源文件路径");
            System.exit(1);
        }
        
        boolean lexOnly = false;
        boolean printAST = false;
        boolean generateIR = true;
        boolean printIR = true;
        boolean generateARM = generateAsm;
        optimizationLevel = 1;
        String targetPath = "IR_output.ll";
        String armOutputPath = generateAsm ? outputPath : "armv8_backend.s";
        
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
            
            if (debug) {
                System.out.println("IR生成成功。");
                System.out.println("模块包含:");
                System.out.println("  - " + irModule.functions().size() + " 个函数");
                System.out.println("  - " + irModule.globalVars().size() + " 个全局变量");
                System.out.println("  - " + irModule.libFunctions().size() + " 个库函数");
            }
            
            if (optimizationLevel > 0) {
                runOptimizations(irModule, optimizationLevel, debug);
            }
            
            if (printIR) {
                PrintStream outputStream = System.out;
                if (targetPath != null) {
                    try {
                        outputStream = new PrintStream(new FileOutputStream(targetPath));
                        if (debug) {
                            System.out.println("\nIR代码已输出到文件: " + targetPath);
                        }
                    } catch (IOException e) {
                        System.err.println("无法创建输出文件: " + e.getMessage());
                        if (debug) {
                            System.out.println("将使用标准输出...");
                        }
                    }
                } else if (debug) {
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
            AArch64Visitor armv8CodeGen = new AArch64Visitor(irModule);
            
            armv8CodeGen.run();
            
            if (outputPath != null) {
                armv8CodeGen.dump(outputPath);
            }
        } catch (Exception e) {
            System.err.println("ARM代码生成失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void runOptimizations(Module irModule, int optimizationLevel, boolean debug) {
        if (debug) {
            System.out.println("\n正在运行优化 (级别 O" + optimizationLevel + ")...");
        }
        
        OptimizeManager.getInstance().runAllOptimizers(irModule);
        
        if (debug) {
            System.out.println("优化完成。");
        }
    }
} 
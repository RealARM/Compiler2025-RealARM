import Frontend.SysYLexer;
import Frontend.SysYParser;
import Frontend.SyntaxTree;
import Frontend.TokenStream;
import Frontend.SysYToken;
import Frontend.SysYTokenType;
import IR.IRBuilder;
import IR.IRPrinter;
import IR.Module;
import IR.Pass.PassManager;
import IR.Visitor.IRVisitor;
import Backend.Armv8.Armv8Visitor;

import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 编译器入口类
 */
public class Compiler {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("错误: 请提供源文件路径");
            System.exit(1);
        }

        String sourcePath = args[0];
        String targetPath = args.length > 1 ? args[1] : null;       // IR输出文件路径，为null时输出到控制台
        String armOutputPath = args.length > 2 ? args[2] : "armv8_backend.s"; // ARM汇编输出文件路径

        
        // 配置选项
        boolean lexOnly = false;        // 是否仅执行词法分析
        boolean printAST = false;       // 是否打印语法树
        boolean generateIR = true;      // 是否生成IR
        boolean printIR = true;         // 是否打印IR
        boolean generateARM = true;     // 是否生成ARM汇编代码
        int optimizationLevel = 2;      // 优化级别 (0-3)
        boolean debug = true;          // 是否打印调试信息
        
        try {
            // 词法分析
            SysYLexer lexer = new SysYLexer(new FileReader(sourcePath));
            TokenStream tokenStream = lexer.tokenize();

            if (lexOnly) {
                // 词法分析模式：打印所有 Token
                tokenStream.reset();
                while (tokenStream.hasMore()) {
                    SysYToken t = tokenStream.next();
                    System.out.println(t);
                    if (t.getType() == SysYTokenType.EOF) break;
                }
                return;
            }

            // 语法分析
            SysYParser parser = new SysYParser(tokenStream);
            SyntaxTree.CompilationUnit ast = parser.parseCompilationUnit();
            
            if (printAST) {
                // 打印语法树
                parser.printSyntaxTree(ast);
                return;
            }
            
            if (!generateIR) {
                System.out.println("语法分析成功。顶层定义数量: " + ast.getDefs().size());
                return;
            }
            
            // IR生成
            IRVisitor irVisitor = new IRVisitor();
            Module irModule = irVisitor.visitCompilationUnit(ast);
            
            System.out.println("IR生成成功。");
            System.out.println("模块包含:");
            System.out.println("  - " + irModule.functions().size() + " 个函数");
            System.out.println("  - " + irModule.globalVars().size() + " 个全局变量");
            System.out.println("  - " + irModule.libFunctions().size() + " 个库函数");
            
            // 如果需要优化，运行优化Pass
            if (optimizationLevel > 0) {
                runOptimizations(irModule, optimizationLevel, debug);
            }
            
            // 打印IR
            if (printIR) {
                PrintStream outputStream = System.out;
                
                // 如果指定了输出文件路径，创建文件输出流
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
                
                // 输出IR到指定流
                IRPrinter printer = new IRPrinter(outputStream);
                printer.printModule(irModule);
                
                // 如果是文件输出流，关闭它
                if (outputStream != System.out) {
                    outputStream.close();
                }
            }
            
            // 生成ARM汇编代码
            if (generateARM) {
                generateARMCode(irModule, armOutputPath);
            }
            
        } catch (Exception e) {
            System.err.println("编译失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 生成ARM汇编代码
     */
    private static void generateARMCode(Module irModule, String outputPath) {
        try {
            System.out.println("\n开始生成ARM汇编代码...");
            
            // 创建ARM代码生成器
            Armv8Visitor armv8CodeGen = new Armv8Visitor(irModule);
            
            // 运行代码生成
            armv8CodeGen.run();
            
            // 输出ARM代码到文件
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
    
    /**
     * 运行优化Pass
     */
    private static void runOptimizations(Module irModule, int optimizationLevel, boolean debug) {
        System.out.println("\n正在运行优化 (级别 O" + optimizationLevel + ")...");
        
        // 获取PassManager实例
        PassManager passManager = PassManager.getInstance();
        passManager.setDebug(debug);
        
        // 根据优化级别运行优化
        passManager.optimize(irModule, optimizationLevel);
        
        System.out.println("优化完成。");
    }
} 
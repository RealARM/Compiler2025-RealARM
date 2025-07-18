import IR.*;
import IR.Type.*;
import IR.Value.*;
import IR.Value.Instructions.*;
import Frontend.*;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class TestIR {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java TestIR <source_file.sy> [--simple]");
            createAndPrintSimpleIR();
            return;
        }

        String sourcePath = args[0];
        boolean simple = args.length > 1 && args[1].equals("--simple");
        
        try {
            if (simple) {
                createAndPrintSimpleIR();
            } else {
                // Parse SysY and convert to IR
                IR.Module module = buildIRFromSourceFile(sourcePath);
                
                // Print the generated IR
                System.out.println(module);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Build IR from a SysY source file
     */
    private static IR.Module buildIRFromSourceFile(String sourcePath) throws Exception {
        // Create lexer and tokenize input
        SysYLexer lexer = new SysYLexer(new FileReader(sourcePath));
        TokenStream tokenStream = lexer.tokenize();
        
        // Create parser and parse input
        SysYParser parser = new SysYParser(tokenStream);
        SyntaxTree.CompilationUnit ast = parser.parseCompilationUnit();
        
        // Create a module with the source file name
        String moduleName = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        if (moduleName.endsWith(".sy")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3);
        }
        IR.Module module = IRBuilder.createModule(moduleName);
        
        // Create necessary types
        IntegerType i32 = new IntegerType(32);
        FloatType f32 = FloatType.F32;
        
        // Add standard library functions
        Function printfFunc = IRBuilder.createExternalFunction("printf", i32, module);
        IRBuilder.createArgument("format", new PointerType(i32), printfFunc, 0);
        
        // TODO: Traverse AST and generate IR
        // For now, we'll just create a simple main function
        Function mainFunc = IRBuilder.createFunction("main", i32, module);
        BasicBlock entryBlock = IRBuilder.createBasicBlock("entry", mainFunc);
        IRBuilder.createReturn(IRBuilder.createConstantInt(0), entryBlock);
        
        return module;
    }
    
    /**
     * Create a simple IR example (without parsing SysY)
     */
    private static void createAndPrintSimpleIR() {
        // Create a new module
        IR.Module module = IRBuilder.createModule("test_module");
        
        // Create some basic types
        IntegerType i32 = new IntegerType(32);
        VoidType voidType = VoidType.VOID;
        
        // Create external functions (printf, etc.)
        Function printfFunc = IRBuilder.createExternalFunction("printf", i32, module);
        IRBuilder.createArgument("format", new PointerType(i32), printfFunc, 0);
        
        // Create a global variable
        GlobalVariable globalVar = IRBuilder.createGlobalVariable("global_var", i32, module);
        
        // Create a main function
        Function mainFunc = IRBuilder.createFunction("main", i32, module);
        BasicBlock entryBlock = IRBuilder.createBasicBlock("entry", mainFunc);
        
        // Create some instructions in the entry block
        AllocaInstruction localVar = IRBuilder.createAlloca(i32, entryBlock);
        StoreInstruction storeInst = IRBuilder.createStore(IRBuilder.createConstantInt(42), localVar, entryBlock);
        LoadInstruction loadInst = IRBuilder.createLoad(localVar, entryBlock);
        
        // Create a binary instruction (add)
        BinaryInstruction addInst = IRBuilder.createBinaryInst(
                OpCode.ADD, 
                loadInst, 
                IRBuilder.createConstantInt(10), 
                entryBlock
        );
        
        // Create a return instruction
        IRBuilder.createReturn(addInst, entryBlock);
        
        // Print the generated IR
        System.out.println(module);
        
        // Print individual functions
        System.out.println("\nFunctions:");
        for (Function func : module.getFunctions()) {
            System.out.println(func);
        }
        
        // Print global variables
        System.out.println("\nGlobal Variables:");
        for (GlobalVariable var : module.getGlobalVariables()) {
            System.out.println(var);
        }
    }
}

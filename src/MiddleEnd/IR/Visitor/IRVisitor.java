package MiddleEnd.IR.Visitor;

import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Module;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import Frontend.Parser.SyntaxTree;

public class IRVisitor {
    private Module module;                  
    private Function currentFunction;       
    private BasicBlock currentBlock;        
    private Value currentValue;             
    
    private final ArrayList<HashMap<String, Value>> symbolTables = new ArrayList<>();  
    private final ArrayList<HashMap<String, List<Integer>>> arrayDimensions = new ArrayList<>(); 
    
    private final Stack<BasicBlock> loopConditionBlocks = new Stack<>();  // 循环条件块栈
    private final Stack<BasicBlock> loopExitBlocks = new Stack<>();       // 循环出口块栈
    
    private final IRBuilder builder = new IRBuilder();  // IR构建器
    
    private static int tmpCounter = 0;
    
    private final Stack<BasicBlock> mergeBlockStack = new Stack<>();
    private final Stack<BasicBlock> loopEndBlockStack = new Stack<>();
    

    public IRVisitor() {
        // 初始化符号表栈（最外层为全局符号表）
        symbolTables.add(new HashMap<>());
        arrayDimensions.add(new HashMap<>());
    }
    
    public Module visitCompilationUnit(SyntaxTree.CompilationUnit unit) {
        module = new Module("SysYModule");
        
        initializeLibraryFunctions();
        
        for (SyntaxTree.TopLevelDef def : unit.getDefs()) {
            if (def instanceof SyntaxTree.FuncDef) {
                visitFuncDef((SyntaxTree.FuncDef) def);
            } else if (def instanceof SyntaxTree.VarDecl) {
                visitGlobalVarDecl((SyntaxTree.VarDecl) def);
            }
        }
        
        validateAllPhiNodesInModule();
        
        return module;
    }
    
    private void validateAllPhiNodesInModule() {
        if (module == null) return;
        
        for (Function function : module.functions()) {
            IRBuilder.validateAllPhiNodes(function);
        }
    }
    
    private void initializeLibraryFunctions() {
        declareLibFunction("getint", IntegerType.I32);
        declareLibFunction("getch", IntegerType.I32);
        declareLibFunction("getfloat", FloatType.F64);
        declareLibFunction("getarray", IntegerType.I32, new PointerType(IntegerType.I32));
        declareLibFunction("getfarray", IntegerType.I32, new PointerType(FloatType.F64));

        declareLibFunction("putint", VoidType.VOID, IntegerType.I32);
        declareLibFunction("putch", VoidType.VOID, IntegerType.I32);
        declareLibFunction("putfloat", VoidType.VOID, FloatType.F64);
        declareLibFunction("putarray", VoidType.VOID, IntegerType.I32, new PointerType(IntegerType.I32));
        declareLibFunction("putfarray", VoidType.VOID, IntegerType.I32, new PointerType(FloatType.F64));
        
        Function startTimeFunc = declareLibFunction("_sysy_starttime", VoidType.VOID, IntegerType.I32);
        Function stopTimeFunc = declareLibFunction("_sysy_stoptime", VoidType.VOID, IntegerType.I32);
        
        symbolTables.get(0).put("starttime", startTimeFunc);
        symbolTables.get(0).put("stoptime", stopTimeFunc);
        
        declareLibFunction("memset", VoidType.VOID, new PointerType(IntegerType.I32), IntegerType.I32, IntegerType.I32);
    }
    
    private Function declareLibFunction(String name, Type returnType, Type... argTypes) {
        Function func = IRBuilder.createExternalFunction("@" + name, returnType, module);
        
        for (int i = 0; i < argTypes.length; i++) {
            IRBuilder.createArgument("%" + name + "_arg" + i, argTypes[i], func, i);
        }

        symbolTables.get(0).put(name, func);
        
        return func;
    }
    
    private void visitGlobalVarDecl(SyntaxTree.VarDecl varDecl) {
        String baseTypeStr = varDecl.baseType;
        boolean isConst = varDecl.isConst;
        
        Type baseType;
        switch (baseTypeStr) {
            case "int":
                baseType = IntegerType.I32;
                break;
            case "float":
                baseType = FloatType.F64;
                break;
            default:
                throw new RuntimeException("不支持的变量类型: " + baseTypeStr);
        }
        
        for (SyntaxTree.VarDef varDef : varDecl.variables) {
            String name = varDef.ident;
            List<Integer> dims = varDef.dims;
            
            if (dims.isEmpty()) {
                Value initValue;
                
                if (varDef.init != null) {
                    currentValue = null;
                    visitExpr(varDef.init);
                    
                    if (currentValue != null) {
                        Constant constResult = MiddleEnd.Optimization.Constant.ConstantExpressionEvaluator.evaluate(currentValue);
                        if (constResult != null) {
                            initValue = constResult;
                            
                            if (baseType == IntegerType.I32 && initValue instanceof ConstantFloat) {
                                initValue = new ConstantInt((int)((ConstantFloat)initValue).getValue());
                            } else if (baseType == FloatType.F64 && initValue instanceof ConstantInt) {
                                initValue = new ConstantFloat((double)((ConstantInt)initValue).getValue());
                            }
                        } else if (currentValue instanceof Constant) {
                            initValue = (Constant) currentValue;
                            
                            if (baseType == IntegerType.I32 && initValue instanceof ConstantFloat) {
                                initValue = new ConstantInt((int)((ConstantFloat)initValue).getValue());
                            } else if (baseType == FloatType.F64 && initValue instanceof ConstantInt) {
                                initValue = new ConstantFloat((double)((ConstantInt)initValue).getValue());
                            }
                        } else {
                            throw new RuntimeException("全局变量初始化必须是常量表达式");
                        }
                    } else {
                        throw new RuntimeException("全局变量初始化失败");
                    }
                } else {
                    if (baseType == IntegerType.I32) {
                        initValue = new ConstantInt(0);
                    } else { // FloatType
                        initValue = new ConstantFloat(0.0);
                    }
                }
                
                GlobalVariable globalVar;
                if (isConst) {
                    globalVar = new GlobalVariable("@" + name, baseType, true);
                    globalVar.setInitializer(initValue);
                    module.addGlobalVariable(globalVar);
                } else {
                    globalVar = IRBuilder.createGlobalVariable("@" + name, baseType, module);
                    globalVar.setInitializer(initValue);
                }
                
                addVariable(name, globalVar);
            } else {
                int totalSize = 1;
                for (int dim : dims) {
                    totalSize *= dim;
                }
                
                GlobalVariable arrayVar;
                if (isConst) {
                    arrayVar = new GlobalVariable("@" + name, baseType, totalSize, true);
                    module.addGlobalVariable(arrayVar);
                } else {
                    arrayVar = IRBuilder.createGlobalArray("@" + name, baseType, totalSize, module);
                }
                
                if (varDef.init != null) {
                    if (varDef.init instanceof SyntaxTree.ArrayInitExpr) {
                        boolean isAllZero = isAllZeroInitializer((SyntaxTree.ArrayInitExpr) varDef.init);
                        
                        if (isAllZero) {
                            arrayVar.setZeroInitialized(totalSize);
                        } else {
                            List<Value> initValues = processGlobalArrayInit((SyntaxTree.ArrayInitExpr) varDef.init, 
                                                                           dims, 
                                                                           baseType);
                            arrayVar.setArrayValues(initValues);
                        }
                    } else {
                        throw new RuntimeException("全局数组初始化必须使用数组初始化表达式");
                    }
                } else {
                    arrayVar.setZeroInitialized(totalSize);
                }
                
                addArrayDimensions(name, dims);
                addVariable(name, arrayVar);
            }
        }
    }
    
    private boolean isAllZeroInitializer(SyntaxTree.ArrayInitExpr initExpr) {
        if (initExpr.elements.isEmpty()) {
            return true;
        }
        
        for (SyntaxTree.Expr expr : initExpr.elements) {
            if (expr instanceof SyntaxTree.ArrayInitExpr) {
                if (!isAllZeroInitializer((SyntaxTree.ArrayInitExpr) expr)) {
                    return false;
                }
            } else if (expr instanceof SyntaxTree.LiteralExpr) {
                Object value = ((SyntaxTree.LiteralExpr) expr).value;
                if (value instanceof Integer && (Integer) value != 0) {
                    return false;
                } else if (value instanceof Float && (Float) value != 0.0f) {
                    return false;
                } else if (value instanceof Double && (Double) value != 0.0) {
                    return false;
                }
            } else {
                return false;
            }
        }
        
        return true;
    }
    
    private List<Value> processGlobalArrayInit(SyntaxTree.ArrayInitExpr initExpr, 
                                              List<Integer> dims, 
                                              Type elementType) {
        int totalSize = 1;
        for (int dim : dims) {
            totalSize *= dim;
        }
        
        boolean isAllZero = isAllZeroInitializer(initExpr);
        
        if (isAllZero) {
            return new ArrayList<>();
        }
        
        List<Value> result = new ArrayList<>(totalSize);
        for (int i = 0; i < totalSize; i++) {
            if (elementType == IntegerType.I32) {
                result.add(new ConstantInt(0));
            } else { // FloatType
                result.add(new ConstantFloat(0.0));
            }
        }
        
        int[] indices = new int[dims.size()];
        fillArrayInitValues(initExpr, result, elementType, dims, indices, 0);
        
        return result;
    }

    private void fillArrayInitValues(SyntaxTree.Expr expr, List<Value> result,
                                   Type elementType, List<Integer> dims, 
                                   int[] indices, int dimLevel) {
        if (expr instanceof SyntaxTree.ArrayInitExpr) {
            SyntaxTree.ArrayInitExpr arrayInit = (SyntaxTree.ArrayInitExpr) expr;
            
            for (int i = 0; i < Math.min(arrayInit.elements.size(), dims.get(dimLevel)); i++) {
                indices[dimLevel] = i;
                
                if (dimLevel < dims.size() - 1) {
                    if (i < arrayInit.elements.size() && arrayInit.elements.get(i) instanceof SyntaxTree.ArrayInitExpr) {
                        fillArrayInitValues(arrayInit.elements.get(i), result, elementType, dims, indices, dimLevel + 1);
                    } else if (i < arrayInit.elements.size()) {
                        indices[dimLevel + 1] = 0;
                        fillSingleElement(arrayInit.elements.get(i), result, elementType, dims, indices);
                        
                        for (int j = dimLevel + 1; j < indices.length; j++) {
                            indices[j] = 0;
                        }
                    }
                } else {
                    if (i < arrayInit.elements.size()) {
                        fillSingleElement(arrayInit.elements.get(i), result, elementType, dims, indices);
                    }
                }
            }
        } else {
            fillSingleElement(expr, result, elementType, dims, indices);
        }
    }


    private void fillSingleElement(SyntaxTree.Expr expr, List<Value> result,
                                 Type elementType, List<Integer> dims, int[] indices) {
        int linearIndex = calculateLinearIndex(dims, indices);
        
        currentValue = null;
        visitExpr(expr);
        
        if (currentValue != null) {
            Constant constResult = MiddleEnd.Optimization.Constant.ConstantExpressionEvaluator.evaluate(currentValue);
            Value valueToAdd = null;
            
            if (constResult != null) {
                valueToAdd = constResult;
            } else if (currentValue instanceof Constant) {
                valueToAdd = currentValue;
            } else {
                throw new RuntimeException("全局数组初始化必须使用常量表达式");
            }
            
            if (valueToAdd instanceof ConstantInt && elementType.isFloatType()) {
                result.set(linearIndex, new ConstantFloat((double)((ConstantInt) valueToAdd).getValue()));
            } else if (valueToAdd instanceof ConstantFloat && elementType == IntegerType.I32) {
                result.set(linearIndex, new ConstantInt((int) ((ConstantFloat)valueToAdd).getValue()));
            } else {
                result.set(linearIndex, (Constant) valueToAdd);
            }
        } else {
            throw new RuntimeException("全局数组初始化表达式计算失败");
        }
    }


    private int calculateLinearIndex(List<Integer> dims, int[] indices) {
        int linearIndex = 0;
        int factor = 1;
        
        for (int i = dims.size() - 1; i >= 0; i--) {
            linearIndex += indices[i] * factor;
            if (i > 0) {
                factor *= dims.get(i);
            }
        }
        
        return linearIndex;
    }
    

    private void visitFuncDef(SyntaxTree.FuncDef funcDef) {
        String name = funcDef.name;
        String retTypeStr = funcDef.retType;
        
        Type retType;
        switch (retTypeStr) {
            case "int":
                retType = IntegerType.I32;
                break;
            case "float":
                retType = FloatType.F64;
                break;
            case "void":
                retType = VoidType.VOID;
                break;
            default:
                throw new RuntimeException("不支持的返回类型: " + retTypeStr);
        }
        
        Function function = IRBuilder.createFunction("@" + name, retType, module);
        currentFunction = function;
        
        IRBuilder.resetBlockCounter();
        
        addVariable(name, function);
        
        BasicBlock entryBlock = IRBuilder.createBasicBlock(function);
        currentBlock = entryBlock;
        
        enterScope();
        
        for (SyntaxTree.Param param : funcDef.params) {
            processParam(param, function);
        }
        
        visitBlock(funcDef.body);
        
        if (currentBlock != null) {
            if (!currentBlock.getInstructions().isEmpty()) {
                Instruction lastInst = currentBlock.getInstructions().get(currentBlock.getInstructions().size() - 1);
                if (!(lastInst instanceof ReturnInstruction)) {
                    // 如果最后一条指令不是返回指令，添加一个默认的返回指令
                    if (retType == VoidType.VOID) {
                        IRBuilder.createReturn(currentBlock); // void返回
                    } else if (retType == IntegerType.I32) {
                        Value returnValue = null;
                        
                        if (!funcDef.body.stmts.isEmpty()) {
                            SyntaxTree.Stmt lastStmt = funcDef.body.stmts.get(funcDef.body.stmts.size() - 1);
                            if (lastStmt instanceof SyntaxTree.ExprStmt) {
                                visitExpr(((SyntaxTree.ExprStmt) lastStmt).expr);
                                returnValue = currentValue;
                            }
                        }
                        
                        if (returnValue == null) {
                            returnValue = new ConstantInt(0);
                        }
                        
                        IRBuilder.createReturn(returnValue, currentBlock);
                    } else if (retType.isFloatType()) {
                        IRBuilder.createReturn(new ConstantFloat(0.0), currentBlock);
                    }
                }
            } else {
                if (retType == VoidType.VOID) {
                    IRBuilder.createReturn(currentBlock);
                } else if (retType == IntegerType.I32) {
                    IRBuilder.createReturn(new ConstantInt(0), currentBlock);
                } else if (retType.isFloatType()) {
                    IRBuilder.createReturn(new ConstantFloat(0.0), currentBlock);
                }
            }
        }
        
        exitScope();
        
        cleanupUnreachableBlocks(function);
        
        for (BasicBlock block : function.getBasicBlocks()) {
            if (block.getInstructions().isEmpty()) {
                if (function.getBasicBlocks().indexOf(block) < function.getBasicBlocks().size() - 1) {
                    BasicBlock nextBlock = function.getBasicBlocks().get(function.getBasicBlocks().indexOf(block) + 1);
                    IRBuilder.createBr(nextBlock, block);
                } else {
                    if (retType == VoidType.VOID) {
                        IRBuilder.createReturn(block);
                    } else if (retType == IntegerType.I32) {
                        IRBuilder.createReturn(new ConstantInt(0), block);
                    } else if (retType.isFloatType()) {
                        IRBuilder.createReturn(new ConstantFloat(0.0), block);
                    }
                }
            } else {
                Instruction lastInst = block.getInstructions().get(block.getInstructions().size() - 1);
                if (!(lastInst instanceof TerminatorInstruction)) {
                    if (function.getBasicBlocks().indexOf(block) < function.getBasicBlocks().size() - 1) {
                        BasicBlock nextBlock = function.getBasicBlocks().get(function.getBasicBlocks().indexOf(block) + 1);
                        IRBuilder.createBr(nextBlock, block);
                    } else {
                        if (retType == VoidType.VOID) {
                            IRBuilder.createReturn(block);
                        } else if (retType == IntegerType.I32) {
                            IRBuilder.createReturn(new ConstantInt(0), block);
                        } else if (retType.isFloatType()) {
                            IRBuilder.createReturn(new ConstantFloat(0.0), block);
                        }
                    }
                }
            }
        }

        IRBuilder.validateAllPhiNodes(function);
    }
    
    private void cleanupUnreachableBlocks(Function function) {
        BasicBlock entryBlock = function.getBasicBlocks().get(0);
        List<BasicBlock> reachableBlocks = new ArrayList<>();
        markReachableBlocks(entryBlock, reachableBlocks);
        
        List<BasicBlock> allBlocks = new ArrayList<>(function.getBasicBlocks());
        for (BasicBlock block : allBlocks) {
            if (!reachableBlocks.contains(block)) {
                function.removeBasicBlock(block);
            }
        }
    }
    
    private void markReachableBlocks(BasicBlock block, List<BasicBlock> reachableBlocks) {
        if (reachableBlocks.contains(block)) {
            return;
        }
        
        reachableBlocks.add(block);
        
        for (BasicBlock successor : block.getSuccessors()) {
            markReachableBlocks(successor, reachableBlocks);
        }
    }
    
    private void processParam(SyntaxTree.Param param, Function function) {
        String name = param.name;
        String typeStr = param.type;
        boolean isArray = param.isArray;
        
        Type type;
        switch (typeStr) {
            case "int":
                type = isArray ? new PointerType(IntegerType.I32) : IntegerType.I32;
                break;
            case "float":
                type = isArray ? new PointerType(FloatType.F64) : FloatType.F64;
                break;
            default:
                throw new RuntimeException("不支持的参数类型: " + typeStr);
        }
        
        Argument arg = IRBuilder.createArgument("%" + name, type, function, function.getArguments().size());
        
        if (isArray) {
            List<Integer> dimensions = new ArrayList<>();
            dimensions.add(0);
            
            for (SyntaxTree.Expr dimExpr : param.dimensions) {
                if (dimExpr instanceof SyntaxTree.LiteralExpr) {
                    Object dimValue = ((SyntaxTree.LiteralExpr) dimExpr).value;
                    if (dimValue instanceof Integer) {
                        dimensions.add((Integer) dimValue);
                    } else {
                        throw new RuntimeException("数组维度必须是整数常量");
                    }
                } else if (dimExpr instanceof SyntaxTree.VarExpr) {
                    String varName = ((SyntaxTree.VarExpr) dimExpr).name;
                    Value dimVar = findVariable(varName);
                    
                    if (dimVar instanceof GlobalVariable && ((GlobalVariable) dimVar).isConstant()) {
                        GlobalVariable globalVar = (GlobalVariable) dimVar;
                        Value initializer = globalVar.getInitializer();
                        if (initializer instanceof ConstantInt) {
                            dimensions.add(((ConstantInt) initializer).getValue());
                            continue;
                        }
                    } 
                    else if (dimVar instanceof ConstantInt) {
                        dimensions.add(((ConstantInt) dimVar).getValue());
                        continue;
                    }
                    
                    throw new RuntimeException("数组维度变量必须是整数常量");
                } else {
                    throw new RuntimeException("数组维度必须是常量表达式");
                }
            }
            
            addArrayDimensions(name, dimensions);
            addVariable(name, arg);
        } else {
            Value allocaInst = IRBuilder.createAlloca(type, currentBlock);
            IRBuilder.createStore(arg, allocaInst, currentBlock);
            addVariable(name, allocaInst);
        }
    }
    
    private void visitBlock(SyntaxTree.Block block) {
        enterScope();
        
        for (SyntaxTree.Stmt stmt : block.stmts) {
            if (currentBlock == null) {
                break;
            }
            
            visitStmt(stmt);
        }
        
        exitScope();
    }
    
    private void visitStmt(SyntaxTree.Stmt stmt) {
        if (currentBlock == null) {
            return;
        }
        
        if (stmt instanceof SyntaxTree.ExprStmt) {
            visitExprStmt((SyntaxTree.ExprStmt) stmt);
        } else if (stmt instanceof SyntaxTree.VarDecl) {
            visitLocalVarDecl((SyntaxTree.VarDecl) stmt);
        } else if (stmt instanceof SyntaxTree.AssignStmt) {
            visitAssignStmt((SyntaxTree.AssignStmt) stmt);
        } else if (stmt instanceof SyntaxTree.ReturnStmt) {
            visitReturnStmt((SyntaxTree.ReturnStmt) stmt);
        } else if (stmt instanceof SyntaxTree.IfStmt) {
            visitIfStmt((SyntaxTree.IfStmt) stmt);
        } else if (stmt instanceof SyntaxTree.WhileStmt) {
            visitWhileStmt((SyntaxTree.WhileStmt) stmt);
        } else if (stmt instanceof SyntaxTree.Block) {
            visitBlock((SyntaxTree.Block) stmt);
        } else if (stmt instanceof SyntaxTree.BreakStmt) {
            visitBreakStmt();
        } else if (stmt instanceof SyntaxTree.ContinueStmt) {
            visitContinueStmt();
        } else {
            throw new RuntimeException("不支持的语句类型: " + stmt.getClass().getName());
        }
    }
    
    private void visitExprStmt(SyntaxTree.ExprStmt stmt) {
        if (stmt.expr != null) {
            visitExpr(stmt.expr);
        }
    }
    
    private void visitLocalVarDecl(SyntaxTree.VarDecl varDecl) {
        String baseTypeStr = varDecl.baseType;
        boolean isConst = varDecl.isConst;
        
        Type baseType;
        switch (baseTypeStr) {
            case "int":
                baseType = IntegerType.I32;
                break;
            case "float":
                baseType = FloatType.F64;
                break;
            default:
                throw new RuntimeException("不支持的变量类型: " + baseTypeStr);
        }
        
        for (SyntaxTree.VarDef varDef : varDecl.variables) {
            String name = varDef.ident;
            List<Integer> dims = varDef.dims;
            
            if (dims.isEmpty()) {
                AllocaInstruction allocaInst = IRBuilder.createAlloca(baseType, currentBlock);
                
                if (varDef.init != null) {
                    visitExpr(varDef.init);
                    Value initValue = currentValue;
                    
                    IRBuilder.createStore(initValue, allocaInst, currentBlock);
                } else {
                    if (baseType == IntegerType.I32) {
                        IRBuilder.createStore(new ConstantInt(0), allocaInst, currentBlock);
                    } else if (baseType == FloatType.F64) {
                        IRBuilder.createStore(new ConstantFloat(0.0), allocaInst, currentBlock);
                    }
                }
                
                addVariable(name, allocaInst);
            } else {
                boolean hasArrayAccess = false;
                if (varDef.init instanceof SyntaxTree.ArrayInitExpr) {
                    SyntaxTree.ArrayInitExpr arrayInit = (SyntaxTree.ArrayInitExpr) varDef.init;
                    for (SyntaxTree.Expr expr : arrayInit.elements) {
                        if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                            hasArrayAccess = true;
                            break;
                        }
                    }
                }
                
                int totalSize = 1;
                for (Integer dim : dims) {
                    totalSize *= dim;
                }
                
                AllocaInstruction arrayPtr = IRBuilder.createArrayAlloca(baseType, totalSize, currentBlock);
                
                addVariable(name, arrayPtr);
                
                addArrayDimensions(name, dims);
                
                if (hasArrayAccess && varDef.init instanceof SyntaxTree.ArrayInitExpr) {
                    for (int i = 0; i < totalSize; i++) {
                        Value indexValue = new ConstantInt(i);
                        Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                        if (baseType == IntegerType.I32) {
                            IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                        } else {
                            IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
                        }
                    }
                    
                    List<Integer> factors = new ArrayList<>();
                    for (int i = 0; i < dims.size(); i++) {
                        int factor = 1;
                        for (int j = i + 1; j < dims.size(); j++) {
                            factor *= dims.get(j);
                        }
                        factors.add(factor);
                    }
                    
                    List<SyntaxTree.Expr> elements = ((SyntaxTree.ArrayInitExpr) varDef.init).elements;
                    int currentPos = 0;
                    
                    for (SyntaxTree.Expr expr : elements) {
                        if (currentPos >= totalSize) break;
                        
                        if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                            visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
                            Value value = currentValue;
                            
                            if (!value.getType().equals(baseType)) {
                                if (baseType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (baseType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            Value indexValue = new ConstantInt(currentPos);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        } 
                        else if (expr instanceof SyntaxTree.ArrayInitExpr) {
                            SyntaxTree.ArrayInitExpr subInit = (SyntaxTree.ArrayInitExpr) expr;
                            
                            for (int i = 0; i < Math.min(subInit.elements.size(), dims.get(dims.size() - 1)); i++) {
                                if (currentPos >= totalSize) break;
                                
                                SyntaxTree.Expr subExpr = subInit.elements.get(i);
                                
                                if (subExpr instanceof SyntaxTree.ArrayAccessExpr) {
                                    visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) subExpr);
                                } else {
                                    visitExpr(subExpr);
                                }
                                Value value = currentValue;
                                
                                if (!value.getType().equals(baseType)) {
                                    if (baseType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                        value = IRBuilder.createFloatToInt(value, currentBlock);
                                    } else if (baseType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                        value = IRBuilder.createIntToFloat(value, currentBlock);
                                    }
                                }
                                
                                Value indexValue = new ConstantInt(currentPos);
                                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                                IRBuilder.createStore(value, elemPtr, currentBlock);
                                
                                currentPos++;
                            }
                        } 
                        else {
                            visitExpr(expr);
                            Value value = currentValue;
                            
                            if (!value.getType().equals(baseType)) {
                                if (baseType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (baseType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            Value indexValue = new ConstantInt(currentPos);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        }
                        
                        currentPos++;
                    }
                } else if (varDef.init != null) {
                    SyntaxTree.ArrayInitExpr initExpr = varDef.init instanceof SyntaxTree.ArrayInitExpr ? 
                                                      (SyntaxTree.ArrayInitExpr) varDef.init : null;
                    processLocalArrayDecl(name, baseType, dims, initExpr);
                }
            }
        }
    }
    
    private Type getType(String typeName) {
        switch (typeName) {
            case "int":
                return IntegerType.I32;
            case "float":
                return FloatType.F64;
            default:
                throw new RuntimeException("不支持的类型: " + typeName);
        }
    }
    
    private void processArrayInit(SyntaxTree.ArrayInitExpr initExpr, Value arrayPtr, List<Integer> dims, Type elementType) {
        List<Integer> factors = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            int factor = 1;
            for (int j = i + 1; j < dims.size(); j++) {
                factor *= dims.get(j);
            }
            factors.add(factor);
        }
        
        int totalSize = 1;
        for (Integer dim : dims) {
            totalSize *= dim;
        }
        
        // 检查是否可以使用memset优化
        boolean useMemset = (elementType == IntegerType.I32 || elementType == IntegerType.I1 || elementType == IntegerType.I8);
        int elementSizeInBytes = elementType.getSize();
        int totalSizeInBytes = totalSize * elementSizeInBytes;
        
        if (useMemset && isLocalArrayAllZero(initExpr)) {
            Function memsetFunc = module.getLibFunction("memset");
            if (memsetFunc != null) {
                List<Value> args = new ArrayList<>();
                args.add(arrayPtr);
                args.add(new ConstantInt(0));
                args.add(new ConstantInt(totalSizeInBytes));
                IRBuilder.createCall(memsetFunc, args, currentBlock);
                return;
            }
        }
        
        if (useMemset) {
            Function memsetFunc = module.getLibFunction("memset");
            if (memsetFunc != null) {
                List<Value> args = new ArrayList<>();
                args.add(arrayPtr);
                args.add(new ConstantInt(0));
                args.add(new ConstantInt(totalSizeInBytes));
                IRBuilder.createCall(memsetFunc, args, currentBlock);
            } else {
                for (int i = 0; i < totalSize; i++) {
                    Value indexValue = new ConstantInt(i);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                }
            }
        } else {
            for (int i = 0; i < totalSize; i++) {
                Value indexValue = new ConstantInt(i);
                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
            }
        }
        
        if (isLocalArrayAllZero(initExpr)) {
            return;
        }
        
        int[] indices = new int[dims.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = 0;
        }
        
        initializeArray(initExpr, arrayPtr, dims, factors, indices, 0, elementType);
    }
    
    private void initializeArray(SyntaxTree.Expr expr, Value arrayPtr, List<Integer> dims, 
                                List<Integer> factors, int[] indices, int dimLevel, Type elementType) {
        if (expr instanceof SyntaxTree.ArrayInitExpr) {
            SyntaxTree.ArrayInitExpr arrayInit = (SyntaxTree.ArrayInitExpr) expr;
            int elementCount = arrayInit.elements.size();

            if (dimLevel == dims.size() - 1) {
                for (int i = 0; i < Math.min(elementCount, dims.get(dimLevel)); i++) {
                    indices[dimLevel] = i;
                    
                    SyntaxTree.Expr element = arrayInit.elements.get(i);
                    
                    int linearIndex = 0;
                    for (int j = 0; j < dims.size(); j++) {
                        linearIndex += indices[j] * factors.get(j);
                    }
                    
                    if (element instanceof SyntaxTree.ArrayAccessExpr) {
                        visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) element);
                    } else {
                        visitExpr(element);
                    }
                    Value value = currentValue;
                    
                    if (!value.getType().equals(elementType)) {
                        if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                            value = IRBuilder.createFloatToInt(value, currentBlock);
                        } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                            value = IRBuilder.createIntToFloat(value, currentBlock);
                        }
                    }
                    
                    Value indexValue = new ConstantInt(linearIndex);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(value, elemPtr, currentBlock);
                }
            }
            else {
                boolean hasNestedArray = false;
                for (SyntaxTree.Expr element : arrayInit.elements) {
                    if (element instanceof SyntaxTree.ArrayInitExpr) {
                        hasNestedArray = true;
                        break;
                    }
                }
                
                if (hasNestedArray) {
                    int curIdx = 0;
                    
                    for (int i = 0; i < elementCount; i++) {
                        SyntaxTree.Expr element = arrayInit.elements.get(i);
                        
                        if (element instanceof SyntaxTree.ArrayInitExpr) {
                            if (curIdx < dims.get(dimLevel)) {
                                indices[dimLevel] = curIdx++;
                                initializeArray(element, arrayPtr, dims, factors, indices, dimLevel + 1, elementType);
                            }
                        } else {
                            if (curIdx < dims.get(dimLevel)) {
                                indices[dimLevel] = curIdx++;
                                
                                for (int j = dimLevel + 1; j < dims.size(); j++) {
                                    indices[j] = 0;
                                }
                                
                                int linearIndex = 0;
                                for (int j = 0; j < dims.size(); j++) {
                                    linearIndex += indices[j] * factors.get(j);
                                }
                                
                                if (element instanceof SyntaxTree.ArrayAccessExpr) {
                                    visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) element);
                                } else {
                                    visitExpr(element);
                                }
                                Value value = currentValue;
                                
                                if (!value.getType().equals(elementType)) {
                                    if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                        value = IRBuilder.createFloatToInt(value, currentBlock);
                                    } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                        value = IRBuilder.createIntToFloat(value, currentBlock);
                                    }
                                }
                                
                                Value indexValue = new ConstantInt(linearIndex);
                                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                                IRBuilder.createStore(value, elemPtr, currentBlock);
                            }
                        }
                    }
                } else {
                    int currentPos = 0;
                    for (int i = 0; i < dims.get(dimLevel) && currentPos < elementCount; i++) {
                        indices[dimLevel] = i;
                        
                        for (int j = 0; j < dims.get(dimLevel + 1) && currentPos < elementCount; j++) {
                            indices[dimLevel + 1] = j;
                            
                            for (int k = dimLevel + 2; k < dims.size(); k++) {
                                indices[k] = 0;
                            }
                            
                            int linearIndex = 0;
                            for (int k = 0; k < dims.size(); k++) {
                                linearIndex += indices[k] * factors.get(k);
                            }
                            
                            SyntaxTree.Expr element = arrayInit.elements.get(currentPos++);
                            
                            if (element instanceof SyntaxTree.ArrayAccessExpr) {
                                visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) element);
                            } else {
                                visitExpr(element);
                            }
                            Value value = currentValue;
                            
                            if (!value.getType().equals(elementType)) {
                                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            Value indexValue = new ConstantInt(linearIndex);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        }
                    }
                }
            }
        } else {
            int linearIndex = 0;
            for (int i = 0; i < dims.size(); i++) {
                linearIndex += indices[i] * factors.get(i);
            }
            
            if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
            } else {
                visitExpr(expr);
            }
            Value value = currentValue;
            
            if (!value.getType().equals(elementType)) {
                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                    value = IRBuilder.createFloatToInt(value, currentBlock);
                } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                    value = IRBuilder.createIntToFloat(value, currentBlock);
                }
            }
            
            Value indexValue = new ConstantInt(linearIndex);
            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
            IRBuilder.createStore(value, elemPtr, currentBlock);
        }
    }
    
    private void visitAssignStmt(SyntaxTree.AssignStmt stmt) {
        if (!(stmt.target instanceof SyntaxTree.VarExpr || stmt.target instanceof SyntaxTree.ArrayAccessExpr)) {
            throw new RuntimeException("赋值语句左侧必须是变量或数组元素");
        }
        
        visitExpr(stmt.value);
        Value rightValue = currentValue;
        
        if (stmt.target instanceof SyntaxTree.VarExpr) {
            String name = ((SyntaxTree.VarExpr) stmt.target).name;
            Value varPtr = findVariable(name);
            
            if (varPtr == null) {
                throw new RuntimeException("未定义的变量: " + name);
            }
            
            if (varPtr.getType() instanceof PointerType) {
                IRBuilder.createStore(rightValue, varPtr, currentBlock);
            } else {
                throw new RuntimeException("无法对非左值表达式赋值: " + name);
            }
        } else if (stmt.target instanceof SyntaxTree.ArrayAccessExpr) {
            visitArrayAccessExpr((SyntaxTree.ArrayAccessExpr) stmt.target);
            Value elemPtr = currentValue;
            
            if (!(elemPtr.getType() instanceof PointerType)) {
                throw new RuntimeException("数组访问表达式必须返回指针");
            }
            
            IRBuilder.createStore(rightValue, elemPtr, currentBlock);
        }
    }
    
    private void visitReturnStmt(SyntaxTree.ReturnStmt stmt) {
        if (stmt.value != null) {
            visitExpr(stmt.value);
            Value retValue = currentValue;
            
            Type returnType = currentFunction.getReturnType();
            if (!retValue.getType().equals(returnType)) {
                if (returnType instanceof IntegerType && retValue.getType() instanceof FloatType) {
                    retValue = IRBuilder.createFloatToInt(retValue, currentBlock);
                } else if (returnType instanceof FloatType && retValue.getType() instanceof IntegerType) {
                    retValue = IRBuilder.createIntToFloat(retValue, currentBlock);
                } else if (returnType instanceof IntegerType && retValue.getType() instanceof IntegerType) {
                    IntegerType targetIntType = (IntegerType) returnType;
                    IntegerType retIntType = (IntegerType) retValue.getType();
                    
                    if (targetIntType.getBitWidth() > retIntType.getBitWidth()) {
                        retValue = IRBuilder.createZeroExtend(retValue, returnType, currentBlock);
                    } else if (targetIntType.getBitWidth() < retIntType.getBitWidth()) {
                        retValue = IRBuilder.createTrunc(retValue, returnType, currentBlock);
                    }
                }
            }
            
            IRBuilder.createReturn(retValue, currentBlock);
        } else {
            IRBuilder.createReturn(currentBlock);
        }
        
        currentBlock = null;
    }
    
    private void visitIfStmt(SyntaxTree.IfStmt stmt) {
        if (currentBlock == null) {
            return;
        }
        
        BasicBlock thenBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock elseBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        visitExpr(stmt.cond);
        Value condValue = convertToBoolean(currentValue);
        
        if (condValue instanceof ConstantInt) {
            int condConstValue = ((ConstantInt) condValue).getValue();
            if (condConstValue != 0) {
                IRBuilder.createBr(thenBlock, currentBlock);
                
                currentBlock = thenBlock;
                visitStmt(stmt.thenBranch);

                if (currentBlock != null && !currentBlock.hasTerminator()) {
                    IRBuilder.createBr(mergeBlock, currentBlock);
                }
                
                currentBlock = elseBlock;
                IRBuilder.createBr(mergeBlock, currentBlock);
                
                currentBlock = mergeBlock;
                return;
            } else {
                IRBuilder.createBr(elseBlock, currentBlock);
                
                currentBlock = thenBlock;
                IRBuilder.createBr(mergeBlock, currentBlock);
                
                currentBlock = elseBlock;
                if (stmt.elseBranch != null) {
                    visitStmt(stmt.elseBranch);
                }
                
                if (currentBlock != null && !currentBlock.hasTerminator()) {
                    IRBuilder.createBr(mergeBlock, currentBlock);
                }
                
                currentBlock = mergeBlock;
                return;
            }
        }
        
        IRBuilder.createCondBr(condValue, thenBlock, elseBlock, currentBlock);
        
        currentBlock = thenBlock;
        visitStmt(stmt.thenBranch);
        
        if (currentBlock != null && !currentBlock.hasTerminator()) {
            IRBuilder.createBr(mergeBlock, currentBlock);
        }
        
        currentBlock = elseBlock;
        if (stmt.elseBranch != null) {
            visitStmt(stmt.elseBranch);
        }
        
        if (currentBlock != null && !currentBlock.hasTerminator()) {
            IRBuilder.createBr(mergeBlock, currentBlock);
        }

        currentBlock = mergeBlock;
    }
    
    private void visitWhileStmt(SyntaxTree.WhileStmt stmt) {
        if (currentBlock == null) {
            return;
        }
        
        BasicBlock condBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock bodyBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock exitBlock = IRBuilder.createBasicBlock(currentFunction);
        
        IRBuilder.createBr(condBlock, currentBlock);
        
        currentBlock = condBlock;
        visitExpr(stmt.cond);
        Value condValue = convertToBoolean(currentValue);
        IRBuilder.createCondBr(condValue, bodyBlock, exitBlock, currentBlock);
        
        loopConditionBlocks.push(condBlock);
        loopExitBlocks.push(exitBlock);
        
        currentBlock = bodyBlock;
        visitStmt(stmt.body);
        
        if (currentBlock != null && !currentBlock.hasTerminator()) {
            IRBuilder.createBr(condBlock, currentBlock);
        }
        
        loopConditionBlocks.pop();
        loopExitBlocks.pop();
        
        currentBlock = exitBlock;
    }
    
    private void visitBreakStmt() {
        if (loopExitBlocks.isEmpty()) {
            throw new RuntimeException("break语句必须在循环内部");
        }
        
        IRBuilder.createBr(loopExitBlocks.peek(), currentBlock);
        
        currentBlock = null;
    }
    
    private void visitContinueStmt() {
        if (loopConditionBlocks.isEmpty()) {
            throw new RuntimeException("continue语句必须在循环内部");
        }
        
        IRBuilder.createBr(loopConditionBlocks.peek(), currentBlock);
        
        currentBlock = null;
    }
    
    private void visitExpr(SyntaxTree.Expr expr) {
        if (expr instanceof SyntaxTree.LiteralExpr) {
            visitLiteralExpr((SyntaxTree.LiteralExpr) expr);
        } else if (expr instanceof SyntaxTree.VarExpr) {
            visitVarExpr((SyntaxTree.VarExpr) expr);
        } else if (expr instanceof SyntaxTree.UnaryExpr) {
            visitUnaryExpr((SyntaxTree.UnaryExpr) expr);
        } else if (expr instanceof SyntaxTree.BinaryExpr) {
            visitBinaryExpr((SyntaxTree.BinaryExpr) expr);
        } else if (expr instanceof SyntaxTree.CallExpr) {
            visitCallExpr((SyntaxTree.CallExpr) expr);
        } else if (expr instanceof SyntaxTree.ArrayAccessExpr) {
            visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
        } else if (expr instanceof SyntaxTree.ArrayInitExpr) {
            visitArrayInitExpr((SyntaxTree.ArrayInitExpr) expr);
        } else {
            throw new RuntimeException("不支持的表达式类型: " + expr.getClass().getName());
        }
    }
    
    private void visitLiteralExpr(SyntaxTree.LiteralExpr expr) {
        Object value = expr.value;
        if (value instanceof Integer) {
            currentValue = new ConstantInt((Integer) value);
        } else if (value instanceof Float) {
            currentValue = new ConstantFloat(((Float) value).doubleValue());
        } else {
            throw new RuntimeException("不支持的字面量类型: " + value.getClass().getName());
        }
    }
    
    private void visitVarExpr(SyntaxTree.VarExpr expr) {
        String name = expr.name;
        Value value = findVariable(name);
        
        if (value == null) {
            throw new RuntimeException("未定义的变量: " + name);
        }
        
        if (currentBlock == null && value instanceof GlobalVariable) {
            GlobalVariable globalVar = (GlobalVariable) value;
            if (globalVar.isConstant() && globalVar.hasInitializer()) {
                currentValue = globalVar.getInitializer();
                return;
            }
        }
        
        if (value.getType() instanceof PointerType) {
            currentValue = IRBuilder.createLoad(value, currentBlock);
        } else {
            currentValue = value;
        }
    }
    
    private void visitExprAndLoad(SyntaxTree.Expr expr) {
        if (expr instanceof SyntaxTree.ArrayAccessExpr) {
            visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
            return;
        }
        
        visitExpr(expr);
        
        if (currentValue.getType() instanceof PointerType && !(expr instanceof SyntaxTree.ArrayAccessExpr)) {
            currentValue = IRBuilder.createLoad(currentValue, currentBlock);
        }
    }
    
    private void visitArrayAccessExprAndLoad(SyntaxTree.ArrayAccessExpr expr) {
        String arrayName = expr.arrayName;
        Value arrayPtr = findVariable(arrayName);
        
        if (arrayPtr == null) {
            throw new RuntimeException("未定义的数组: " + arrayName);
        }
        
        List<Integer> dimensions = findArrayDimensions(arrayName);
        if (dimensions == null) {
            throw new RuntimeException("无法获取数组 " + arrayName + " 的维度信息");
        }
        
        List<Value> indices = new ArrayList<>();
        for (SyntaxTree.Expr indexExpr : expr.indices) {
            visitExpr(indexExpr);
            indices.add(currentValue);
        }
        
        if (indices.size() > dimensions.size()) {
            throw new RuntimeException("数组 " + arrayName + " 的索引数量过多");
        }

        Value offset = new ConstantInt(0);
        List<Integer> factors = new ArrayList<>();
        
        for (int i = 0; i < dimensions.size(); i++) {
            int factor = 1;
            for (int j = i + 1; j < dimensions.size(); j++) {
                factor *= dimensions.get(j);
            }
            factors.add(factor);
        }
        
        for (int i = 0; i < indices.size(); i++) {
            Value indexFactor = IRBuilder.createBinaryInst(
                OpCode.MUL,
                indices.get(i),
                new ConstantInt(factors.get(i)),
                currentBlock
            );
            offset = IRBuilder.createBinaryInst(OpCode.ADD, offset, indexFactor, currentBlock);
        }
        
        Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, offset, currentBlock);
        
        currentValue = IRBuilder.createLoad(elemPtr, currentBlock);
    }
    
    private void visitUnaryExpr(SyntaxTree.UnaryExpr expr) {
        visitExpr(expr.expr);
        Value operand = currentValue;
        
        switch (expr.op) {
            case "+":
                break;
            case "-":
                if (currentBlock == null) {
                    if (operand instanceof Constant) {
                        if (operand instanceof ConstantInt) {
                            currentValue = new ConstantInt(-((ConstantInt) operand).getValue());
                        } else if (operand instanceof ConstantFloat) {
                            currentValue = new ConstantFloat(-((ConstantFloat) operand).getValue());
                        }
                    } else {
                        currentValue = new UnaryInstruction(OpCode.NEG, operand, "neg_" + operand.getName());
                    }
                } else {
                    if (operand.getType() instanceof IntegerType) {
                        IntegerType intType = (IntegerType) operand.getType();
                        if (intType.getBitWidth() == 1) {
                            operand = IRBuilder.createZeroExtend(operand, IntegerType.I32, currentBlock);
                        }
                        Value zero = new ConstantInt(0);
                        currentValue = IRBuilder.createBinaryInst(OpCode.SUB, zero, operand, currentBlock);
                    }
                    else if (operand.getType() instanceof FloatType) {
                        Value zero = new ConstantFloat(0.0);
                        currentValue = IRBuilder.createBinaryInst(OpCode.FSUB, zero, operand, currentBlock);
                    }
                }
                break;
            case "!":
                if (operand.getType() instanceof IntegerType) {
                    Value zero = new ConstantInt(0);
                    Value boolResult = IRBuilder.createICmp(OpCode.EQ, operand, zero, currentBlock);
                    
                    currentValue = IRBuilder.createZeroExtend(boolResult, IntegerType.I32, currentBlock);
                } else if (operand.getType() instanceof FloatType) {
                    Value intValue = IRBuilder.createFloatToInt(operand, currentBlock);
                    Value zero = new ConstantInt(0);
                    Value boolResult = IRBuilder.createICmp(OpCode.EQ, intValue, zero, currentBlock);
                    
                    currentValue = IRBuilder.createZeroExtend(boolResult, IntegerType.I32, currentBlock);
                }
                break;
            default:
                throw new RuntimeException("不支持的一元操作符: " + expr.op);
        }
    }
    
    private void visitBinaryExpr(SyntaxTree.BinaryExpr expr) {
        String op = expr.op;
        
        if (op.equals("&&")) {
            handleLogicalAndChain(expr);
            return;
        } else if (op.equals("||")) {
            handleLogicalOrChain(expr);
            return;
        }
        
        class ExprState {
            SyntaxTree.Expr expr;
            int state;
            Value leftValue;
            
            ExprState(SyntaxTree.Expr expr, int state) {
                this.expr = expr;
                this.state = state;
                this.leftValue = null;
            }
        }
        
        Stack<ExprState> stack = new Stack<>();
        stack.push(new ExprState(expr, 0));
        
        while (!stack.isEmpty()) {
            ExprState current = stack.peek();
            
            if (current.expr instanceof SyntaxTree.BinaryExpr) {
                SyntaxTree.BinaryExpr binaryExpr = (SyntaxTree.BinaryExpr) current.expr;
                String currentOp = binaryExpr.op;
                
                if (currentOp.equals("&&") || currentOp.equals("||")) {
                    throw new RuntimeException("逻辑运算符应该已经被单独处理");
                }
                
                if (current.state == 0) {
                    current.state = 1;
                    stack.push(new ExprState(binaryExpr.left, 0));
                } else {
                    stack.pop();
                    
                    Value leftValue = current.leftValue != null ? current.leftValue : currentValue;
                    
                    visitExpr(binaryExpr.right);
                    Value rightValue = currentValue;
                    
                    processBinaryOperation(currentOp, leftValue, rightValue);
                    
                    if (!stack.isEmpty()) {
                        stack.peek().leftValue = currentValue;
                    }
                }
            } else {
                stack.pop();
                visitExpr(current.expr);
                
                if (!stack.isEmpty()) {
                    stack.peek().leftValue = currentValue;
                }
            }
        }
    }
    
    private void processBinaryOperation(String op, Value leftValue, Value rightValue) {
        if (isCmpOp(op)) {
            OpCode compareType;
            OpCode predicate;
            
            if (leftValue.getType() instanceof FloatType || rightValue.getType() instanceof FloatType) {
                compareType = OpCode.FCMP;
                predicate = getFloatPredicate(op);
            } else {
                compareType = OpCode.ICMP;
                predicate = getIntPredicate(op);
            }
            
            Value compResult = IRBuilder.createCompare(compareType, predicate, leftValue, rightValue, currentBlock);
            
            currentValue = IRBuilder.createZeroExtend(compResult, IntegerType.I32, currentBlock);
        } else {
            if (op.equals("==") || op.equals("!=")) {
                boolean leftIsBool = leftValue.getType() instanceof IntegerType && 
                                    ((IntegerType) leftValue.getType()).getBitWidth() == 1;
                boolean rightIsBool = rightValue.getType() instanceof IntegerType && 
                                     ((IntegerType) rightValue.getType()).getBitWidth() == 1;
                
                if (leftIsBool && !rightIsBool && rightValue.getType() instanceof IntegerType) {
                    Value zero = new ConstantInt(0);
                    rightValue = IRBuilder.createICmp(OpCode.NE, rightValue, zero, currentBlock);
                } else if (rightIsBool && !leftIsBool && leftValue.getType() instanceof IntegerType) {
                    Value zero = new ConstantInt(0);
                    leftValue = IRBuilder.createICmp(OpCode.NE, leftValue, zero, currentBlock);
                }
            }
            
            OpCode opCode = getOpCodeForBinaryOp(op);
            
            currentValue = IRBuilder.createBinaryInst(opCode, leftValue, rightValue, currentBlock);
        }
    }
    
    private void handleLogicalAndChain(SyntaxTree.BinaryExpr expr) {
        List<SyntaxTree.Expr> operands = collectLogicalChain(expr, "&&");
        
        boolean allConstants = true;
        for (SyntaxTree.Expr operand : operands) {
            if (!isConstantExpr(operand)) {
                allConstants = false;
                break;
            }
        }
        
        if (allConstants) {
            boolean result = true;
            for (SyntaxTree.Expr operand : operands) {
                if (!evaluateConstantBool(operand)) {
                    result = false;
                    break;
                }
            }
            
            currentValue = new ConstantInt(result ? 1 : 0, IntegerType.I1);
            
            return;
        }
        
        BasicBlock startBlock = currentBlock;
        
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        List<BasicBlock> evalBlocks = new ArrayList<>();
        for (int i = 1; i < operands.size(); i++) {
            evalBlocks.add(IRBuilder.createBasicBlock(currentFunction));
        }
        
        visitExpr(operands.get(0));
        Value firstResult = convertToBoolean(currentValue);
        
        for (int i = 0; i < operands.size() - 1; i++) {
            Value currentResult = (i == 0) ? firstResult : currentValue;
            BasicBlock nextBlock = (i < evalBlocks.size()) ? evalBlocks.get(i) : mergeBlock;
            
            IRBuilder.createCondBr(currentResult, nextBlock, mergeBlock, currentBlock);
            
            if (i < evalBlocks.size()) {
                currentBlock = nextBlock;
                
                visitExpr(operands.get(i+1));
                Value boolValue = convertToBoolean(currentValue);
                currentValue = boolValue;
            }
        }
        
        IRBuilder.createBr(mergeBlock, currentBlock);
        
        currentBlock = mergeBlock;
        PhiInstruction phi = IRBuilder.createPhi(IntegerType.I1, mergeBlock);
        
        for (int i = 0; i < operands.size() - 1; i++) {
            BasicBlock predBlock = (i == 0) ? startBlock : evalBlocks.get(i-1);
            phi.addIncoming(new ConstantInt(0, IntegerType.I1), predBlock);
        }
        
        if (!evalBlocks.isEmpty()) {
            BasicBlock lastEvalBlock = evalBlocks.get(evalBlocks.size() - 1);
            if (!(currentValue.getType() instanceof IntegerType) || 
                ((IntegerType)currentValue.getType()).getBitWidth() != 1) {
                Value zero = new ConstantInt(0);
                currentValue = IRBuilder.createICmp(OpCode.NE, currentValue, zero, currentBlock);
            }
            phi.addIncoming(currentValue, lastEvalBlock);
        }
        
        currentValue = phi;
        
        forceFixPhiNodes(phi);
    }
    
    private void handleLogicalOrChain(SyntaxTree.BinaryExpr expr) {
        List<SyntaxTree.Expr> operands = collectLogicalChain(expr, "||");
        
        boolean allConstants = true;
        for (SyntaxTree.Expr operand : operands) {
            if (!isConstantExpr(operand)) {
                allConstants = false;
                break;
            }
        }
        
        if (allConstants) {
            boolean result = false;
            for (SyntaxTree.Expr operand : operands) {
                if (evaluateConstantBool(operand)) {
                    result = true;
                    break;
                }
            }
            
            currentValue = new ConstantInt(result ? 1 : 0, IntegerType.I1);
            
            return;
        }
        
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        List<BasicBlock> evalBlocks = new ArrayList<>();
        for (int i = 1; i < operands.size(); i++) {
            evalBlocks.add(IRBuilder.createBasicBlock(currentFunction));
        }
        
        BasicBlock startBlock = currentBlock;
        
        visitExpr(operands.get(0));
        Value firstValue = convertToBoolean(currentValue);
        
        if (operands.size() > 1) {
            IRBuilder.createCondBr(firstValue, mergeBlock, evalBlocks.get(0), currentBlock);
            
            for (int i = 1; i < operands.size(); i++) {
                currentBlock = evalBlocks.get(i-1);
                
                visitExpr(operands.get(i));
                Value currValue = convertToBoolean(currentValue);
                
                currentValue = currValue;
                
                if (i == operands.size() - 1) {
                    IRBuilder.createBr(mergeBlock, currentBlock);
                } else {    
                    IRBuilder.createCondBr(currValue, mergeBlock, evalBlocks.get(i), currentBlock);
                }
            }
        } else {
            IRBuilder.createBr(mergeBlock, currentBlock);
        }
        
        currentBlock = mergeBlock;
        
        PhiInstruction resultPhi = IRBuilder.createPhi(IntegerType.I1, mergeBlock);
        
        resultPhi.addIncoming(new ConstantInt(1, IntegerType.I1), startBlock);
        
        for (int i = 0; i < operands.size() - 1 && i < evalBlocks.size(); i++) {
            resultPhi.addIncoming(new ConstantInt(1, IntegerType.I1), evalBlocks.get(i));
        }
        
        if (!evalBlocks.isEmpty()) {
            BasicBlock lastEvalBlock = evalBlocks.get(evalBlocks.size() - 1);
            resultPhi.addIncoming(currentValue, lastEvalBlock);
        }
        
        currentValue = resultPhi;
        
        forceFixPhiNodes(resultPhi);
    }
    
    private void forceFixPhiNodes(PhiInstruction phi) {
        BasicBlock block = phi.getParent();
        if (block == null) return;
        
        List<BasicBlock> blockPreds = block.getPredecessors();
        List<BasicBlock> phiPreds = new ArrayList<>(phi.getIncomingBlocks());
        
        for (BasicBlock pred : blockPreds) {
            if (!phiPreds.contains(pred)) {
                Value defaultValue = phi.getType().toString().equals("i1") ? 
                    new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                phi.addIncoming(defaultValue, pred);
            }
        }
        
        for (BasicBlock pred : new ArrayList<>(phiPreds)) {
            if (!blockPreds.contains(pred)) {
                phi.removeIncoming(pred);
            }
        }
    }
    
    private boolean isConstantExpr(SyntaxTree.Expr expr) {
        if (expr instanceof SyntaxTree.LiteralExpr) {
            return true;
        }
        return false;
    }
    
    private boolean evaluateConstantBool(SyntaxTree.Expr expr) {
        if (expr instanceof SyntaxTree.LiteralExpr) {
            Object value = ((SyntaxTree.LiteralExpr) expr).value;
            if (value instanceof Integer) {
                return ((Integer) value) != 0;
            } else if (value instanceof Float) {
                return ((Float) value) != 0.0f;
            }
        }
        
        return false;
    }
    
    private List<SyntaxTree.Expr> collectLogicalChain(SyntaxTree.BinaryExpr expr, String operator) {
        List<SyntaxTree.Expr> operands = new ArrayList<>();
        collectOperandsHelper(expr, operator, operands);
        return operands;
    }
    
    private void collectOperandsHelper(SyntaxTree.Expr expr, String operator, List<SyntaxTree.Expr> operands) {
        if (expr instanceof SyntaxTree.BinaryExpr && ((SyntaxTree.BinaryExpr) expr).op.equals(operator)) {
            SyntaxTree.BinaryExpr binExpr = (SyntaxTree.BinaryExpr) expr;
            collectOperandsHelper(binExpr.left, operator, operands);
            collectOperandsHelper(binExpr.right, operator, operands);
        } else {
            operands.add(expr);
        }
    }
    
    private boolean isCmpOp(String op) {
        return op.equals("==") || op.equals("!=") || op.equals("<") || op.equals("<=") || 
               op.equals(">") || op.equals(">=");
    }
    
    private OpCode getOpCodeForBinaryOp(String op) {
        return switch (op) {
            case "+" -> OpCode.ADD;
            case "-" -> OpCode.SUB;
            case "*" -> OpCode.MUL;
            case "/" -> OpCode.DIV;
            case "%" -> OpCode.REM;
            case "<<" -> OpCode.SHL;
            case ">>" -> OpCode.ASHR;
            case "&" -> OpCode.AND;
            case "|" -> OpCode.OR;
            case "^" -> OpCode.XOR;
            case "==" -> OpCode.ICMP;
            case "!=" -> OpCode.ICMP;
            case "<" -> OpCode.ICMP;
            case "<=" -> OpCode.ICMP;
            case ">" -> OpCode.ICMP;
            case ">=" -> OpCode.ICMP;
            default -> throw new RuntimeException("不支持的二元运算符: " + op);
        };
    }
    
    private OpCode getIntPredicate(String op) {
        return switch (op) {
            case "==" -> OpCode.EQ;
            case "!=" -> OpCode.NE;
            case "<" -> OpCode.SLT;
            case "<=" -> OpCode.SLE;
            case ">" -> OpCode.SGT;
            case ">=" -> OpCode.SGE;
            default -> throw new RuntimeException("不支持的比较运算符: " + op);
        };
    }
    
    private OpCode getFloatPredicate(String op) {
        return switch (op) {
            case "==" -> OpCode.UEQ;
            case "!=" -> OpCode.UNE;
            case "<" -> OpCode.ULT;
            case "<=" -> OpCode.ULE;
            case ">" -> OpCode.UGT;
            case ">=" -> OpCode.UGE;
            default -> throw new RuntimeException("不支持的比较运算符: " + op);
        };
    }
    
    private Value convertToBoolean(Value value) {
        if (value.getType() instanceof IntegerType && ((IntegerType) value.getType()).getBitWidth() == 1) {
            return value;
        } else if (value.getType() instanceof IntegerType) {
            Value zero = new ConstantInt(0);
            return IRBuilder.createICmp(OpCode.NE, value, zero, currentBlock);
        } else if (value.getType() instanceof FloatType) {
            Value intValue = IRBuilder.createFloatToInt(value, currentBlock);
            Value zero = new ConstantInt(0);
            return IRBuilder.createICmp(OpCode.NE, intValue, zero, currentBlock);
        } else if (value.getType() instanceof PointerType) {
            Value zero = new ConstantInt(0);
            return IRBuilder.createICmp(OpCode.NE, value, zero, currentBlock);
        }
        throw new RuntimeException("无法将类型 " + value.getType() + " 转换为布尔值");
    }
    
    private void visitCallExpr(SyntaxTree.CallExpr expr) {
        String funcName = expr.funcName;
        Value funcValue = findVariable(funcName);
        
        if (funcValue == null || !(funcValue instanceof Function)) {
            throw new RuntimeException("未定义的函数: " + funcName);
        }
        
        Function function = (Function) funcValue;
        List<Value> args = new ArrayList<>();
        
        if (funcName.equals("starttime") || funcName.equals("stoptime")) {
            args.add(new ConstantInt(0));
            
            if (funcName.equals("starttime")) {
                currentValue = IRBuilder.createCall((Function)findVariable("_sysy_starttime"), args, currentBlock);
                return;
            } 
            else { 
                currentValue = IRBuilder.createCall((Function)findVariable("_sysy_stoptime"), args, currentBlock);
                return;
            }
        }
        
        List<Argument> funcArgs = function.getArguments();
        if (expr.args.size() != funcArgs.size()) {
            throw new RuntimeException("函数 " + funcName + " 参数数量不匹配: 期望 " + 
                                      funcArgs.size() + ", 实际 " + expr.args.size());
        }
        
        for (int i = 0; i < expr.args.size(); i++) {
            SyntaxTree.Expr argExpr = expr.args.get(i);
            Type paramType = funcArgs.get(i).getType();
            
            if (paramType instanceof PointerType) {
                if (argExpr instanceof SyntaxTree.VarExpr) {
                    String arrayName = ((SyntaxTree.VarExpr) argExpr).name;
                    Value arrayVar = findVariable(arrayName);
                    
                    if (findArrayDimensions(arrayName) != null) {
                        args.add(arrayVar);
                        continue;
                    }
                }
                else if (argExpr instanceof SyntaxTree.ArrayAccessExpr) {
                    visitArrayAccessExpr((SyntaxTree.ArrayAccessExpr) argExpr);
                    Value elemPtr = currentValue;
                    
                    if (elemPtr.getType() instanceof PointerType) {
                        args.add(elemPtr);
                        continue;
                    }
                }
            }
            
            visitExpr(argExpr);
            Value arg = currentValue;
            
            if (!arg.getType().equals(paramType)) {
                if (arg.getType() instanceof IntegerType && paramType instanceof FloatType) {
                    arg = IRBuilder.createIntToFloat(arg, currentBlock);
                } else if (arg.getType() instanceof FloatType && paramType instanceof IntegerType) {
                    arg = IRBuilder.createFloatToInt(arg, currentBlock);
                }
            }
            
            args.add(arg);
        }
        
        currentValue = IRBuilder.createCall(function, args, currentBlock);
    }
    
    private void visitArrayAccessExpr(SyntaxTree.ArrayAccessExpr expr) {
        String arrayName = expr.arrayName;
        Value arrayPtr = findVariable(arrayName);
        
        if (arrayPtr == null) {
            throw new RuntimeException("未定义的数组: " + arrayName);
        }
        
        List<Integer> dimensions = findArrayDimensions(arrayName);
        if (dimensions == null) {
            throw new RuntimeException("无法获取数组 " + arrayName + " 的维度信息");
        }
        
        List<Value> indices = new ArrayList<>();
        for (SyntaxTree.Expr indexExpr : expr.indices) {
            visitExpr(indexExpr);
            indices.add(currentValue);
        }
        
        if (indices.size() > dimensions.size()) {
            throw new RuntimeException("数组 " + arrayName + " 的索引数量过多");
        }
        
        List<Integer> factors = new ArrayList<>();
        for (int i = 0; i < dimensions.size(); i++) {
            int factor = 1;
            for (int j = i + 1; j < dimensions.size(); j++) {
                factor *= dimensions.get(j);
            }
            factors.add(factor);
        }
        
        Value offset = new ConstantInt(0);
        for (int i = 0; i < indices.size(); i++) {
            Value indexFactor = IRBuilder.createBinaryInst(
                OpCode.MUL,
                indices.get(i),
                new ConstantInt(factors.get(i)),
                currentBlock
            );
            offset = IRBuilder.createBinaryInst(OpCode.ADD, offset, indexFactor, currentBlock);
        }
        
        currentValue = IRBuilder.createGetElementPtr(arrayPtr, offset, currentBlock);
    }
    
    private void visitArrayInitExpr(SyntaxTree.ArrayInitExpr expr) {
        throw new RuntimeException("数组初始化表达式应在变量声明中处理");
    }
    
    private void processLocalArrayDecl(String name, Type elementType, List<Integer> dimensions, SyntaxTree.ArrayInitExpr initExpr) {
        int totalSize = 1;
        for (int dim : dimensions) {
            totalSize *= dim;
        }
        
        AllocaInstruction arrayPtr = IRBuilder.createArrayAlloca(elementType, totalSize, currentBlock);
        
        addVariable(name, arrayPtr);
        
        addArrayDimensions(name, dimensions);
        
        boolean useMemset = (elementType == IntegerType.I32 || elementType == IntegerType.I1 || elementType == IntegerType.I8);
        int elementSizeInBytes = elementType.getSize();
        int totalSizeInBytes = totalSize * elementSizeInBytes;
        
        if (initExpr != null) {
            if (useMemset && isLocalArrayAllZero(initExpr)) {
                Function memsetFunc = module.getLibFunction("memset");
                if (memsetFunc != null) {
                    List<Value> args = new ArrayList<>();
                    args.add(arrayPtr);
                    args.add(new ConstantInt(0));
                    args.add(new ConstantInt(totalSizeInBytes));
                    IRBuilder.createCall(memsetFunc, args, currentBlock);
                    return;
                }
            }
            
            boolean hasArrayAccess = false;
            for (SyntaxTree.Expr expr : initExpr.elements) {
                if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                    hasArrayAccess = true;
                    break;
                }
            }
            
            if (hasArrayAccess) {
                if (useMemset) {
                    Function memsetFunc = module.getLibFunction("memset");
                    if (memsetFunc != null) {
                        List<Value> args = new ArrayList<>();
                        args.add(arrayPtr);
                        args.add(new ConstantInt(0));
                        args.add(new ConstantInt(totalSizeInBytes));
                        IRBuilder.createCall(memsetFunc, args, currentBlock);
                    } else {
                        for (int i = 0; i < totalSize; i++) {
                            Value indexValue = new ConstantInt(i);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                        }
                    }
                } else {
                    for (int i = 0; i < totalSize; i++) {
                        Value indexValue = new ConstantInt(i);
                        Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                        IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
                    }
                }
                
                List<Integer> factors = new ArrayList<>();
                for (int i = 0; i < dimensions.size(); i++) {
                    int factor = 1;
                    for (int j = i + 1; j < dimensions.size(); j++) {
                        factor *= dimensions.get(j);
                    }
                    factors.add(factor);
                }
                
                int currentPos = 0;
                for (SyntaxTree.Expr expr : initExpr.elements) {
                    if (currentPos >= totalSize) break;
                    
                    int[] indices = new int[dimensions.size()];
                    int remaining = currentPos;
                    for (int i = 0; i < dimensions.size(); i++) {
                        indices[i] = remaining / factors.get(i);
                        remaining %= factors.get(i);
                    }
                    
                    int linearIndex = 0;
                    for (int i = 0; i < dimensions.size(); i++) {
                        linearIndex += indices[i] * factors.get(i);
                    }
                    
                    if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                        visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
                    } else if (expr instanceof SyntaxTree.ArrayInitExpr) {
                        SyntaxTree.ArrayInitExpr subInit = (SyntaxTree.ArrayInitExpr) expr;
                        
                        int currentDimSize = dimensions.get(0);
                        for (int i = 0; i < Math.min(subInit.elements.size(), dimensions.get(1)); i++) {
                            if (currentPos + i >= totalSize) break;
                            
                            SyntaxTree.Expr subExpr = subInit.elements.get(i);
                            
                            if (subExpr instanceof SyntaxTree.ArrayAccessExpr) {
                                visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) subExpr);
                            } else {
                                visitExpr(subExpr);
                            }
                            Value value = currentValue;
                            
                            if (!value.getType().equals(elementType)) {
                                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            Value storeIndex = new ConstantInt(linearIndex + i);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, storeIndex, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        }
                        
                        currentPos += dimensions.get(1);
                        continue;
                    } else {
                        visitExpr(expr);
                    }
                    Value value = currentValue;
                    
                    if (!value.getType().equals(elementType)) {
                        if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                            value = IRBuilder.createFloatToInt(value, currentBlock);
                        } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                            value = IRBuilder.createIntToFloat(value, currentBlock);
                        }
                    }
                    
                    Value indexValue = new ConstantInt(linearIndex);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(value, elemPtr, currentBlock);
                    
                    currentPos++;
                }
                
                if (currentPos < totalSize) {
                    int remainingElements = totalSize - currentPos;
                    int remainingBytes = remainingElements * elementSizeInBytes;
                    
                    Value ptrOffset = IRBuilder.createGetElementPtr(arrayPtr, new ConstantInt(currentPos), currentBlock);
                    
                    if (useMemset && remainingElements > 32) {
                        Function remainingMemsetFunc = module.getLibFunction("memset");
                        if (remainingMemsetFunc != null) {
                            List<Value> args = new ArrayList<>();
                            args.add(ptrOffset);
                            args.add(new ConstantInt(0));
                            args.add(new ConstantInt(remainingBytes));
                            IRBuilder.createCall(remainingMemsetFunc, args, currentBlock);
                        } else {
                            for (int i = 0; i < remainingElements; i++) {
                                Value indexValue = new ConstantInt(i);
                                Value elemPtr = IRBuilder.createGetElementPtr(ptrOffset, indexValue, currentBlock);
                                IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                            }
                        }
                    } else {
                        for (int i = 0; i < remainingElements; i++) {
                            Value indexValue = new ConstantInt(i);
                            Value elemPtr = IRBuilder.createGetElementPtr(ptrOffset, indexValue, currentBlock);
                            if (elementType == IntegerType.I32 || elementType == IntegerType.I1 || elementType == IntegerType.I8) {
                                IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                            } else { // FloatType
                                IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
                            }
                        }
                    }
                }
            } else {
                processArrayInit(initExpr, arrayPtr, dimensions, elementType);
            }
        } else {
            if (useMemset) {
                Function noInitMemsetFunc = module.getLibFunction("memset");
                if (noInitMemsetFunc != null) {
                    List<Value> args = new ArrayList<>();
                    args.add(arrayPtr);
                    args.add(new ConstantInt(0));
                    args.add(new ConstantInt(totalSizeInBytes));
                    IRBuilder.createCall(noInitMemsetFunc, args, currentBlock);
                }
            } else {
                for (int i = 0; i < totalSize; i++) {
                    Value indexValue = new ConstantInt(i);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
                }
            }
        }
    }
    
    private Value findVariable(String name) {
        for (int i = symbolTables.size() - 1; i >= 0; i--) {
            HashMap<String, Value> table = symbolTables.get(i);
            if (table.containsKey(name)) {
                return table.get(name);
            }
        }
        return null;
    }
    
    private void addVariable(String name, Value value) {
        symbolTables.get(symbolTables.size() - 1).put(name, value);
    }
    
    private void addArrayDimensions(String name, List<Integer> dimensions) {
        arrayDimensions.get(arrayDimensions.size() - 1).put(name, dimensions);
    }
    
    private List<Integer> findArrayDimensions(String name) {
        for (int i = arrayDimensions.size() - 1; i >= 0; i--) {
            HashMap<String, List<Integer>> table = arrayDimensions.get(i);
            if (table.containsKey(name)) {
                return table.get(name);
            }
        }
        return null;
    }

    private void enterScope() {
        symbolTables.add(new HashMap<>());
        arrayDimensions.add(new HashMap<>());
    }
    
    private void exitScope() {
        symbolTables.remove(symbolTables.size() - 1);
        arrayDimensions.remove(arrayDimensions.size() - 1);
    }
    
    private boolean isLocalArrayAllZero(SyntaxTree.ArrayInitExpr initExpr) {
        if (initExpr == null) {
            return true;
        }
        
        for (SyntaxTree.Expr expr : initExpr.elements) {
            if (expr instanceof SyntaxTree.LiteralExpr) {
                SyntaxTree.LiteralExpr lit = (SyntaxTree.LiteralExpr) expr;
                if (lit.value instanceof Integer && (Integer)lit.value != 0) {
                    return false;
                } else if (lit.value instanceof Float && (Float)lit.value != 0.0f) {
                    return false;
                }
            } else if (expr instanceof SyntaxTree.ArrayInitExpr) {
                if (!isLocalArrayAllZero((SyntaxTree.ArrayInitExpr) expr)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        
        return true;
    }
    
    // private boolean isRemainingArrayZero(SyntaxTree.ArrayInitExpr initExpr, int startPos) {
    //     if (initExpr == null || startPos >= initExpr.elements.size()) {
    //         return true;
    //     }
        
    //     for (int i = startPos; i < initExpr.elements.size(); i++) {
    //         SyntaxTree.Expr expr = initExpr.elements.get(i);
    //         if (expr instanceof SyntaxTree.LiteralExpr) {
    //             SyntaxTree.LiteralExpr lit = (SyntaxTree.LiteralExpr) expr;
    //             if (lit.value instanceof Integer && (Integer)lit.value != 0) {
    //                 return false;
    //             } else if (lit.value instanceof Float && (Float)lit.value != 0.0f) {
    //                 return false;
    //             }
    //         } else if (expr instanceof SyntaxTree.ArrayInitExpr) {
    //             if (!isLocalArrayAllZero((SyntaxTree.ArrayInitExpr) expr)) {
    //                 return false;
    //             }
    //         } else {
    //             return false;
    //         }
    //     }
        
    //     return true;
    // }

} 
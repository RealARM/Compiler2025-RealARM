package IR.Visitor;

import Frontend.SyntaxTree;
import IR.IRBuilder;
import IR.Module;
import IR.Type.*;
import IR.Value.*;
import IR.Value.Instructions.*;
import IR.OpCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Stack;

/**
 * IR访问者类，用于将AST转换为IR
 */
public class IRVisitor {
    // IR构建相关
    private Module module;                  // 当前模块
    private Function currentFunction;       // 当前处理的函数
    private BasicBlock currentBlock;        // 当前基本块
    private Value currentValue;             // 当前处理生成的值
    
    // 符号表相关
    private final ArrayList<HashMap<String, Value>> symbolTables = new ArrayList<>();  // 符号表栈
    private final ArrayList<HashMap<String, List<Integer>>> arrayDimensions = new ArrayList<>(); // 数组维度信息
    
    // 控制流相关
    private final Stack<BasicBlock> loopConditionBlocks = new Stack<>();  // 循环条件块栈
    private final Stack<BasicBlock> loopExitBlocks = new Stack<>();       // 循环出口块栈
    
    // 实用工具
    private final IRBuilder builder = new IRBuilder();  // IR构建器
    
    // 用于唯一命名
    private static int tmpCounter = 0;
    
    // 增加跟踪嵌套控制流结构的堆栈
    private final Stack<BasicBlock> mergeBlockStack = new Stack<>();
    
    /**
     * 创建一个IR构建访问器
     */
    public IRVisitor() {
        // 初始化符号表栈（最外层为全局符号表）
        symbolTables.add(new HashMap<>());
        arrayDimensions.add(new HashMap<>());
    }
    
    /**
     * 访问编译单元并生成IR模块
     */
    public Module visitCompilationUnit(SyntaxTree.CompilationUnit unit) {
        module = new Module("SysYModule");
        
        // 初始化库函数
        initializeLibraryFunctions();
        
        // 访问所有顶级定义
        for (SyntaxTree.TopLevelDef def : unit.getDefs()) {
            if (def instanceof SyntaxTree.FuncDef) {
                visitFuncDef((SyntaxTree.FuncDef) def);
            } else if (def instanceof SyntaxTree.VarDecl) {
                visitGlobalVarDecl((SyntaxTree.VarDecl) def);
            }
        }
        
        return module;
    }
    
    /**
     * 初始化标准库函数
     */
    private void initializeLibraryFunctions() {
        // 输入输出函数
        declareLibFunction("getint", IntegerType.I32);
        declareLibFunction("putint", VoidType.VOID, IntegerType.I32);
        declareLibFunction("getch", IntegerType.I32);
        declareLibFunction("putch", VoidType.VOID, IntegerType.I32);
        
        // 数组操作函数
        declareLibFunction("getarray", IntegerType.I32, new PointerType(IntegerType.I32));
        declareLibFunction("putarray", VoidType.VOID, IntegerType.I32, new PointerType(IntegerType.I32));
        
        // 浮点操作函数
        declareLibFunction("getfloat", FloatType.F32);
        declareLibFunction("putfloat", VoidType.VOID, FloatType.F32);
        declareLibFunction("getfarray", IntegerType.I32, new PointerType(FloatType.F32));
        declareLibFunction("putfarray", VoidType.VOID, IntegerType.I32, new PointerType(FloatType.F32));
        
        // 时间函数
        declareLibFunction("starttime", VoidType.VOID, IntegerType.I32);
        declareLibFunction("stoptime", VoidType.VOID, IntegerType.I32);
        
        // 内存操作函数
        declareLibFunction("memset", VoidType.VOID, new PointerType(IntegerType.I32), IntegerType.I32, IntegerType.I32);
    }
    
    /**
     * 声明标准库函数
     */
    private void declareLibFunction(String name, Type returnType, Type... argTypes) {
        Function func = IRBuilder.createExternalFunction("@" + name, returnType, module);
        
        // 添加参数
        for (int i = 0; i < argTypes.length; i++) {
            IRBuilder.createArgument("%" + name + "_arg" + i, argTypes[i], func, i);
        }
        
        // 将函数添加到符号表
        symbolTables.get(0).put(name, func);
    }
    
    /**
     * 访问全局变量声明
     */
    private void visitGlobalVarDecl(SyntaxTree.VarDecl varDecl) {
        String baseTypeStr = varDecl.baseType;
        boolean isConst = varDecl.isConst;
        
        // 确定基本类型
        Type baseType;
        switch (baseTypeStr) {
            case "int":
                baseType = IntegerType.I32;
                break;
            case "float":
                baseType = FloatType.F32;
                break;
            default:
                throw new RuntimeException("不支持的变量类型: " + baseTypeStr);
        }
        
        // 处理每个变量定义
        for (SyntaxTree.VarDef varDef : varDecl.variables) {
            String name = varDef.ident;
            List<Integer> dims = varDef.dims;
            
            if (dims.isEmpty()) {
                // 普通全局变量
                Value initValue;
                
                // 处理初始化值
                if (varDef.init != null) {
                    // 访问表达式获取计算结果
                    currentValue = null;
                    visitExpr(varDef.init);
                    
                    if (currentValue != null) {
                        // 尝试将表达式结果评估为常量
                        Constant constResult = IR.Pass.ConstantExpressionEvaluator.evaluate(currentValue);
                        if (constResult != null) {
                            initValue = constResult;
                            
                            // 类型转换处理
                            if (baseType == IntegerType.I32 && initValue instanceof ConstantFloat) {
                                // 浮点数常量转整数
                                initValue = new ConstantInt((int)((ConstantFloat)initValue).getValue());
                            } else if (baseType == FloatType.F32 && initValue instanceof ConstantInt) {
                                // 整数常量转浮点数
                                initValue = new ConstantFloat((float)((ConstantInt)initValue).getValue());
                            }
                        } else if (currentValue instanceof Constant) {
                            // 如果是常量但不需要进一步评估
                            initValue = (Constant) currentValue;
                            
                            // 类型转换处理
                            if (baseType == IntegerType.I32 && initValue instanceof ConstantFloat) {
                                initValue = new ConstantInt((int)((ConstantFloat)initValue).getValue());
                            } else if (baseType == FloatType.F32 && initValue instanceof ConstantInt) {
                                initValue = new ConstantFloat((float)((ConstantInt)initValue).getValue());
                            }
                        } else {
                            throw new RuntimeException("全局变量初始化必须是常量表达式");
                        }
                    } else {
                        throw new RuntimeException("全局变量初始化失败");
                    }
                } else {
                    // 默认初始化为0
                    if (baseType == IntegerType.I32) {
                        initValue = new ConstantInt(0);
                    } else { // FloatType
                        initValue = new ConstantFloat(0.0f);
                    }
                }
                
                // 创建全局变量
                GlobalVariable globalVar;
                if (isConst) {
                    // 对于常量，直接创建一个常量全局变量
                    globalVar = new GlobalVariable("@" + name, baseType, true);
                    globalVar.setInitializer(initValue);
                    module.addGlobalVariable(globalVar);
                } else {
                    // 对于非常量，使用IRBuilder创建
                    globalVar = IRBuilder.createGlobalVariable("@" + name, baseType, module);
                    globalVar.setInitializer(initValue);
                }
                
                // 添加到符号表
                addVariable(name, globalVar);
            } else {
                // 全局数组变量
                // 计算数组总大小
                int totalSize = 1;
                for (int dim : dims) {
                    totalSize *= dim;
                }
                
                // 创建全局数组
                GlobalVariable arrayVar;
                if (isConst) {
                    // 对于常量数组，直接创建一个常量全局数组
                    arrayVar = new GlobalVariable("@" + name, baseType, totalSize, true);
                    module.addGlobalVariable(arrayVar);
                } else {
                    // 对于非常量数组，使用IRBuilder创建
                    arrayVar = IRBuilder.createGlobalArray("@" + name, baseType, totalSize, module);
                }
                
                // 处理数组初始化
                if (varDef.init != null) {
                    if (varDef.init instanceof SyntaxTree.ArrayInitExpr) {
                        // 处理数组初始化表达式
                        List<Value> initValues = processGlobalArrayInit((SyntaxTree.ArrayInitExpr) varDef.init, 
                                                                       dims, 
                                                                       baseType);
                        arrayVar.setArrayValues(initValues);
                    } else {
                        throw new RuntimeException("全局数组初始化必须使用数组初始化表达式");
                    }
                } else {
                    // 默认初始化为全0
                    arrayVar.setZeroInitialized(totalSize);
                }
                
                // 保存数组维度信息和变量
                addArrayDimensions(name, dims);
                addVariable(name, arrayVar);
            }
        }
    }
    
    /**
     * 处理全局数组初始化
     */
    private List<Value> processGlobalArrayInit(SyntaxTree.ArrayInitExpr initExpr, 
                                              List<Integer> dims, 
                                              Type elementType) {
        // 计算数组总大小
        int totalSize = 1;
        for (int dim : dims) {
            totalSize *= dim;
        }
        
        // 收集初始化值
        List<Value> initValues = new ArrayList<>();
        flattenGlobalArrayInit(initExpr, initValues, elementType);
        
        // 如果初始化值不足，用0填充
        while (initValues.size() < totalSize) {
            if (elementType == IntegerType.I32) {
                initValues.add(new ConstantInt(0));
            } else { // FloatType
                initValues.add(new ConstantFloat(0.0f));
            }
        }
        
        return initValues;
    }
    
    /**
     * 展平全局数组初始化表达式
     */
    private void flattenGlobalArrayInit(SyntaxTree.Expr expr, List<Value> result, Type elementType) {
        if (expr instanceof SyntaxTree.ArrayInitExpr) {
            SyntaxTree.ArrayInitExpr arrayInit = (SyntaxTree.ArrayInitExpr) expr;
            for (SyntaxTree.Expr element : arrayInit.elements) {
                flattenGlobalArrayInit(element, result, elementType);
            }
        } else {
            // 处理表达式元素
            currentValue = null;
            visitExpr(expr);
            
            if (currentValue != null) {
                // 尝试将表达式结果评估为常量
                Constant constResult = IR.Pass.ConstantExpressionEvaluator.evaluate(currentValue);
                Value valueToAdd = null;
                
                if (constResult != null) {
                    valueToAdd = constResult;
                } else if (currentValue instanceof Constant) {
                    valueToAdd = currentValue;
                } else {
                    throw new RuntimeException("全局数组初始化必须使用常量表达式");
                }
                
                // 类型转换处理
                if (valueToAdd instanceof ConstantInt && elementType == FloatType.F32) {
                    result.add(new ConstantFloat(((ConstantInt) valueToAdd).getValue()));
                } else if (valueToAdd instanceof ConstantFloat && elementType == IntegerType.I32) {
                    result.add(new ConstantInt((int) ((ConstantFloat)valueToAdd).getValue()));
                } else {
                    result.add((Constant) valueToAdd);
                }
            } else {
                throw new RuntimeException("全局数组初始化表达式计算失败");
            }
        }
    }
    
    /**
     * 访问函数定义
     */
    private void visitFuncDef(SyntaxTree.FuncDef funcDef) {
        String name = funcDef.name;
        String retTypeStr = funcDef.retType;
        
        // 确定返回类型
        Type retType;
        switch (retTypeStr) {
            case "int":
                retType = IntegerType.I32;
                break;
            case "float":
                retType = FloatType.F32;
                break;
            case "void":
                retType = VoidType.VOID;
                break;
            default:
                throw new RuntimeException("不支持的返回类型: " + retTypeStr);
        }
        
        // 创建函数
        Function function = IRBuilder.createFunction("@" + name, retType, module);
        currentFunction = function;
        
        // 重置基本块计数器，确保每个函数的基本块名称都是唯一的
        IRBuilder.resetBlockCounter();
        
        // 添加到符号表
        addVariable(name, function);
        
        // 创建入口基本块
        BasicBlock entryBlock = IRBuilder.createBasicBlock(function);
        currentBlock = entryBlock;
        
        // 进入函数作用域
        enterScope();
        
        // 处理参数
        for (SyntaxTree.Param param : funcDef.params) {
            processParam(param, function);
        }
        
        // 处理函数体
        visitBlock(funcDef.body);
        
        // 确保函数有返回语句
        if (currentBlock != null) {
            if (!currentBlock.getInstructions().isEmpty()) {
                Instruction lastInst = currentBlock.getInstructions().get(currentBlock.getInstructions().size() - 1);
                if (!(lastInst instanceof ReturnInstruction)) {
                    // 如果最后一条指令不是返回指令，添加一个默认的返回指令
                    if (retType == VoidType.VOID) {
                        IRBuilder.createReturn(currentBlock); // void返回
                    } else if (retType == IntegerType.I32) {
                        // 加载函数体中最后一个表达式的值作为返回值
                        Value returnValue = null;
                        
                        // 尝试找到最后一个语句，如果是表达式语句，使用其结果作为返回值
                        if (!funcDef.body.stmts.isEmpty()) {
                            SyntaxTree.Stmt lastStmt = funcDef.body.stmts.get(funcDef.body.stmts.size() - 1);
                            if (lastStmt instanceof SyntaxTree.ExprStmt) {
                                visitExpr(((SyntaxTree.ExprStmt) lastStmt).expr);
                                returnValue = currentValue;
                            }
                        }
                        
                        // 如果没有找到合适的返回值，返回0
                        if (returnValue == null) {
                            returnValue = new ConstantInt(0);
                        }
                        
                        IRBuilder.createReturn(returnValue, currentBlock);
                    } else if (retType == FloatType.F32) {
                        IRBuilder.createReturn(new ConstantFloat(0.0f), currentBlock); // 返回0.0
                    }
                }
            } else {
                // 如果当前块是空的，添加一个默认的返回指令
                if (retType == VoidType.VOID) {
                    IRBuilder.createReturn(currentBlock); // void返回
                } else if (retType == IntegerType.I32) {
                    IRBuilder.createReturn(new ConstantInt(0), currentBlock); // 返回0
                } else if (retType == FloatType.F32) {
                    IRBuilder.createReturn(new ConstantFloat(0.0f), currentBlock); // 返回0.0
                }
            }
        }
        
        // 离开函数作用域
        exitScope();
        
        // 清理不可达的基本块
        cleanupUnreachableBlocks(function);
        
        // 检查所有基本块，确保它们都有终结指令
        for (BasicBlock block : function.getBasicBlocks()) {
            if (block.getInstructions().isEmpty()) {
                // 空块添加跳转到下一个块或返回指令
                if (function.getBasicBlocks().indexOf(block) < function.getBasicBlocks().size() - 1) {
                    BasicBlock nextBlock = function.getBasicBlocks().get(function.getBasicBlocks().indexOf(block) + 1);
                    IRBuilder.createBr(nextBlock, block);
                } else {
                    // 最后一个块添加返回指令
                    if (retType == VoidType.VOID) {
                        IRBuilder.createReturn(block);
                    } else if (retType == IntegerType.I32) {
                        IRBuilder.createReturn(new ConstantInt(0), block);
                    } else if (retType == FloatType.F32) {
                        IRBuilder.createReturn(new ConstantFloat(0.0f), block);
                    }
                }
            } else {
                Instruction lastInst = block.getInstructions().get(block.getInstructions().size() - 1);
                if (!(lastInst instanceof TerminatorInstruction)) {
                    // 非空块但没有终结指令，添加跳转到下一个块或返回指令
                    if (function.getBasicBlocks().indexOf(block) < function.getBasicBlocks().size() - 1) {
                        BasicBlock nextBlock = function.getBasicBlocks().get(function.getBasicBlocks().indexOf(block) + 1);
                        IRBuilder.createBr(nextBlock, block);
                    } else {
                        // 最后一个块添加返回指令
                        if (retType == VoidType.VOID) {
                            IRBuilder.createReturn(block);
                        } else if (retType == IntegerType.I32) {
                            IRBuilder.createReturn(new ConstantInt(0), block);
                        } else if (retType == FloatType.F32) {
                            IRBuilder.createReturn(new ConstantFloat(0.0f), block);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 清理函数中不可达的基本块
     */
    private void cleanupUnreachableBlocks(Function function) {
        // 标记所有可达的基本块
        BasicBlock entryBlock = function.getBasicBlocks().get(0);
        List<BasicBlock> reachableBlocks = new ArrayList<>();
        markReachableBlocks(entryBlock, reachableBlocks);
        
        // 移除所有不可达的基本块
        List<BasicBlock> allBlocks = new ArrayList<>(function.getBasicBlocks());
        for (BasicBlock block : allBlocks) {
            if (!reachableBlocks.contains(block)) {
                function.removeBasicBlock(block);
            }
        }
    }
    
    /**
     * 递归标记所有可达的基本块
     */
    private void markReachableBlocks(BasicBlock block, List<BasicBlock> reachableBlocks) {
        if (reachableBlocks.contains(block)) {
            return; // 已经标记过
        }
        
        reachableBlocks.add(block);
        
        // 递归标记所有后继块
        for (BasicBlock successor : block.getSuccessors()) {
            markReachableBlocks(successor, reachableBlocks);
        }
    }
    
    /**
     * 处理函数参数
     */
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
                type = isArray ? new PointerType(FloatType.F32) : FloatType.F32;
                break;
            default:
                throw new RuntimeException("不支持的参数类型: " + typeStr);
        }
        
        // 创建参数
        Argument arg = IRBuilder.createArgument("%" + name, type, function, function.getArguments().size());
        
        if (isArray) {
            // 如果是数组参数，保存维度信息
            List<Integer> dimensions = new ArrayList<>();
            dimensions.add(0); // 第一维未知
            
            // 处理数组维度
            for (SyntaxTree.Expr dimExpr : param.dimensions) {
                if (dimExpr instanceof SyntaxTree.LiteralExpr) {
                    // 直接是字面量常量
                    Object dimValue = ((SyntaxTree.LiteralExpr) dimExpr).value;
                    if (dimValue instanceof Integer) {
                        dimensions.add((Integer) dimValue);
                    } else {
                        throw new RuntimeException("数组维度必须是整数常量");
                    }
                } else if (dimExpr instanceof SyntaxTree.VarExpr) {
                    // 变量表达式，尝试查找并获取常量值
                    String varName = ((SyntaxTree.VarExpr) dimExpr).name;
                    Value dimVar = findVariable(varName);
                    
                    // 处理全局常量情况
                    if (dimVar instanceof GlobalVariable && ((GlobalVariable) dimVar).isConstant()) {
                        GlobalVariable globalVar = (GlobalVariable) dimVar;
                        Value initializer = globalVar.getInitializer();
                        if (initializer instanceof ConstantInt) {
                            dimensions.add(((ConstantInt) initializer).getValue());
                            continue;
                        }
                    } 
                    // 处理直接常量情况
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
            // 非数组参数，需要在函数入口处分配栈空间并存储参数值
            Value allocaInst = IRBuilder.createAlloca(type, currentBlock);
            IRBuilder.createStore(arg, allocaInst, currentBlock);
            addVariable(name, allocaInst);
        }
    }
    
    /**
     * 访问代码块
     */
    private void visitBlock(SyntaxTree.Block block) {
        // 进入新作用域
        enterScope();
        
        // 访问块内的所有语句
        for (SyntaxTree.Stmt stmt : block.stmts) {
            // 如果当前块已终结，停止生成后续代码
            if (currentBlock == null) {
                break;
            }
            
            visitStmt(stmt);
        }
        
        // 离开作用域
        exitScope();
    }
    
    /**
     * 访问语句
     */
    private void visitStmt(SyntaxTree.Stmt stmt) {
        // 如果当前块已经终结，不再生成代码
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
    
    /**
     * 访问表达式语句
     */
    private void visitExprStmt(SyntaxTree.ExprStmt stmt) {
        visitExpr(stmt.expr);
        // 表达式语句不需要保存结果值
    }
    
    /**
     * 访问局部变量声明
     */
    private void visitLocalVarDecl(SyntaxTree.VarDecl varDecl) {
        String baseTypeStr = varDecl.baseType;
        boolean isConst = varDecl.isConst;
        
        // 确定基本类型
        Type baseType;
        switch (baseTypeStr) {
            case "int":
                baseType = IntegerType.I32;
                break;
            case "float":
                baseType = FloatType.F32;
                break;
            default:
                throw new RuntimeException("不支持的变量类型: " + baseTypeStr);
        }
        
        // 处理每个变量定义
        for (SyntaxTree.VarDef varDef : varDecl.variables) {
            String name = varDef.ident;
            List<Integer> dims = varDef.dims;
            
            if (dims.isEmpty()) {
                // 普通变量
                AllocaInstruction allocaInst = IRBuilder.createAlloca(baseType, currentBlock);
                
                // 如果有初始化表达式，计算并存储
                if (varDef.init != null) {
                    visitExpr(varDef.init);
                    Value initValue = currentValue;
                    
                    // IRBuilder.createStore 会自动处理类型转换
                    IRBuilder.createStore(initValue, allocaInst, currentBlock);
                } else {
                    // 默认初始化为0
                    if (baseType == IntegerType.I32) {
                        IRBuilder.createStore(new ConstantInt(0), allocaInst, currentBlock);
                    } else if (baseType == FloatType.F32) {
                        IRBuilder.createStore(new ConstantFloat(0.0f), allocaInst, currentBlock);
                    }
                }
                
                // 添加到符号表
                addVariable(name, allocaInst);
            } else {
                // 数组变量
                SyntaxTree.ArrayInitExpr initExpr = varDef.init instanceof SyntaxTree.ArrayInitExpr ? 
                                                  (SyntaxTree.ArrayInitExpr) varDef.init : null;
                
                // 使用processLocalArrayDecl处理数组声明和初始化
                processLocalArrayDecl(name, baseType, dims, initExpr);
            }
        }
    }
    
    /**
     * 获取类型
     */
    private Type getType(String typeName) {
        switch (typeName) {
            case "int":
                return IntegerType.I32;
            case "float":
                return FloatType.F32;
            default:
                throw new RuntimeException("不支持的类型: " + typeName);
        }
    }
    
    /**
     * 处理数组初始化
     */
    private void processArrayInit(SyntaxTree.ArrayInitExpr initExpr, Value arrayPtr, List<Integer> dims, Type elementType) {
        List<Value> flattenedValues = flattenArrayInit(initExpr, dims, elementType);
        
        // 特殊处理二维数组
        if (dims.size() == 2) {
            int rows = dims.get(0);
            int cols = dims.get(1);
            
            for (int i = 0; i < Math.min(flattenedValues.size(), rows * cols); i++) {
                // 计算正确的行列索引
                int row = i / cols;
                int col = i % cols;
                
                // 计算正确的一维偏移: row * cols + col
                int offset = row * cols + col;
                Value indexValue = new ConstantInt(offset);
                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                
                // 存储值
                IRBuilder.createStore(flattenedValues.get(i), elemPtr, currentBlock);
            }
        } else {
            // 其他维度的数组，保持原来的一维处理方式
        for (int i = 0; i < flattenedValues.size(); i++) {
            // 计算元素指针
            Value indexValue = new ConstantInt(i);
            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
            
            // 存储值
            IRBuilder.createStore(flattenedValues.get(i), elemPtr, currentBlock);
            }
        }
    }
    
    /**
     * 将嵌套的数组初始化表达式展平为一维值列表
     */
    private List<Value> flattenArrayInit(SyntaxTree.ArrayInitExpr initExpr, List<Integer> dims, Type elementType) {
        List<Value> result = new ArrayList<>();
        
        // 计算数组总大小
        int totalSize = 1;
        for (int dim : dims) {
            totalSize *= dim;
        }
        
        // 对于二维数组，需要特别处理嵌套的初始化表达式
        if (dims.size() == 2) {
            int rows = dims.get(0);
            int cols = dims.get(1);
            
            // 如果是带嵌套的二维数组初始化 {{...}, {...}, ...}
            if (initExpr.elements.size() > 0 && initExpr.elements.get(0) instanceof SyntaxTree.ArrayInitExpr) {
                // 处理每一行
                for (int i = 0; i < Math.min(rows, initExpr.elements.size()); i++) {
                    SyntaxTree.Expr rowExpr = initExpr.elements.get(i);
                    if (rowExpr instanceof SyntaxTree.ArrayInitExpr) {
                        SyntaxTree.ArrayInitExpr rowInit = (SyntaxTree.ArrayInitExpr) rowExpr;
                        // 处理这一行中的每个元素
                        for (int j = 0; j < Math.min(cols, rowInit.elements.size()); j++) {
                            visitExpr(rowInit.elements.get(j));
                            Value value = currentValue;
                            
                            // 类型转换（如果需要）
                            if (!value.getType().equals(elementType)) {
                                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (elementType == FloatType.F32 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            result.add(value);
                        }
                        
                        // 如果这一行的元素不足，用0填充
                        for (int j = rowInit.elements.size(); j < cols; j++) {
                            if (elementType == IntegerType.I32) {
                                result.add(new ConstantInt(0));
                            } else if (elementType == FloatType.F32) {
                                result.add(new ConstantFloat(0.0f));
                            }
                        }
                    } else {
                        // 这一行不是数组初始化表达式，按单个元素处理并填充剩余元素
                        visitExpr(rowExpr);
                        Value value = currentValue;
                        
                        // 类型转换（如果需要）
                        if (!value.getType().equals(elementType)) {
                            if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                value = IRBuilder.createFloatToInt(value, currentBlock);
                            } else if (elementType == FloatType.F32 && value.getType() instanceof IntegerType) {
                                value = IRBuilder.createIntToFloat(value, currentBlock);
                            }
                        }
                        result.add(value);
                        
                        // 填充这一行剩余的元素
                        for (int j = 1; j < cols; j++) {
                            if (elementType == IntegerType.I32) {
                                result.add(new ConstantInt(0));
                            } else if (elementType == FloatType.F32) {
                                result.add(new ConstantFloat(0.0f));
                            }
                        }
                    }
                }
                
                // 如果行数不足，用0填充剩余的行
                for (int i = initExpr.elements.size(); i < rows; i++) {
                    for (int j = 0; j < cols; j++) {
                        if (elementType == IntegerType.I32) {
                            result.add(new ConstantInt(0));
                        } else if (elementType == FloatType.F32) {
                            result.add(new ConstantFloat(0.0f));
                        }
                    }
                }
            } else {
                // 处理混合形式的初始化，如 {1, 2, {3}, {5}, 7, 8}
                int currentRow = 0;
                int currentCol = 0;
                
                // 将初始化元素映射到二维数组中
                for (SyntaxTree.Expr element : initExpr.elements) {
                    // 检查当前位置是否在数组范围内
                    if (currentRow >= rows) {
                        break;
                    }
                    
                    if (element instanceof SyntaxTree.ArrayInitExpr) {
                        // 处理嵌套数组初始化 {3} 或 {5}
                        SyntaxTree.ArrayInitExpr nestedInit = (SyntaxTree.ArrayInitExpr) element;
                        
                        // 获取嵌套数组的第一个元素
                        if (!nestedInit.elements.isEmpty()) {
                            visitExpr(nestedInit.elements.get(0));
                            Value value = currentValue;
                            
                            // 类型转换（如果需要）
                            if (!value.getType().equals(elementType)) {
                                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (elementType == FloatType.F32 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            // 计算正确的一维索引并存储值
                            int index = currentRow * cols + currentCol;
                            while (result.size() <= index) {
                                result.add(new ConstantInt(0)); // 填充中间可能的空隙
                            }
                            result.set(index, value);
                        }
                        
                        // 移动到下一位置
                        currentCol++;
                        if (currentCol >= cols) {
                            currentRow++;
                            currentCol = 0;
                        }
                        
                        // 嵌套数组的其他元素会被忽略，其位置填0
                        if (currentRow < rows && currentCol < cols) {
                            int index = currentRow * cols + currentCol;
                            while (result.size() <= index) {
                                result.add(new ConstantInt(0));
                            }
                            result.set(index, new ConstantInt(0));
                            
                            // 移动到下一位置
                            currentCol++;
                            if (currentCol >= cols) {
                                currentRow++;
                                currentCol = 0;
                            }
                        }
                    } else {
                        // 处理单个元素
                        visitExpr(element);
                        Value value = currentValue;
                        
                        // 类型转换（如果需要）
                        if (!value.getType().equals(elementType)) {
                            if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                value = IRBuilder.createFloatToInt(value, currentBlock);
                            } else if (elementType == FloatType.F32 && value.getType() instanceof IntegerType) {
                                value = IRBuilder.createIntToFloat(value, currentBlock);
                            }
                        }
                        
                        // 计算正确的一维索引并存储值
                        int index = currentRow * cols + currentCol;
                        while (result.size() <= index) {
                            result.add(new ConstantInt(0)); // 填充中间可能的空隙
                        }
                        result.set(index, value);
                        
                        // 移动到下一位置
                        currentCol++;
                        if (currentCol >= cols) {
                            currentRow++;
                            currentCol = 0;
                        }
                    }
                }
                
                // 确保结果大小正确
                while (result.size() < totalSize) {
                    result.add(new ConstantInt(0));
                }
            }
        } else {
            // 非二维数组，使用原有逻辑
        flattenArrayInitRecursive(initExpr, result, elementType);
        
        // 如果初始化值不足，用0填充
        while (result.size() < totalSize) {
            if (elementType == IntegerType.I32) {
                result.add(new ConstantInt(0));
            } else if (elementType == FloatType.F32) {
                result.add(new ConstantFloat(0.0f));
                }
            }
        }
        
        return result;
    }
    
    /**
     * 递归展平数组初始化表达式
     */
    private void flattenArrayInitRecursive(SyntaxTree.Expr expr, List<Value> result, Type elementType) {
        if (expr instanceof SyntaxTree.ArrayInitExpr) {
            SyntaxTree.ArrayInitExpr arrayInit = (SyntaxTree.ArrayInitExpr) expr;
            for (SyntaxTree.Expr element : arrayInit.elements) {
                flattenArrayInitRecursive(element, result, elementType);
            }
        } else {
            // 处理单个元素
            visitExpr(expr);
            Value value = currentValue;
            
            // 类型转换（如果需要）
            if (!value.getType().equals(elementType)) {
                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                    value = IRBuilder.createFloatToInt(value, currentBlock);
                } else if (elementType == FloatType.F32 && value.getType() instanceof IntegerType) {
                    value = IRBuilder.createIntToFloat(value, currentBlock);
                }
            }
            
            result.add(value);
        }
    }
    
    /**
     * 访问赋值语句
     */
    private void visitAssignStmt(SyntaxTree.AssignStmt stmt) {
        // 处理左值表达式
        if (!(stmt.target instanceof SyntaxTree.VarExpr || stmt.target instanceof SyntaxTree.ArrayAccessExpr)) {
            throw new RuntimeException("赋值语句左侧必须是变量或数组元素");
        }
        
        // 获取右值
        visitExpr(stmt.value);
        Value rightValue = currentValue;
        
        // 处理左值
        if (stmt.target instanceof SyntaxTree.VarExpr) {
            // 变量赋值
            String name = ((SyntaxTree.VarExpr) stmt.target).name;
            Value varPtr = findVariable(name);
            
            if (varPtr == null) {
                throw new RuntimeException("未定义的变量: " + name);
            }
            
            // 如果是指针类型（局部变量），直接存储；否则（全局变量）需要先加载
            if (varPtr.getType() instanceof PointerType) {
                // IRBuilder.createStore 会自动处理类型转换
                IRBuilder.createStore(rightValue, varPtr, currentBlock);
            } else {
                throw new RuntimeException("无法对非左值表达式赋值: " + name);
            }
        } else if (stmt.target instanceof SyntaxTree.ArrayAccessExpr) {
            // 数组元素赋值
            visitArrayAccessExpr((SyntaxTree.ArrayAccessExpr) stmt.target);
            Value elemPtr = currentValue;
            
            // 确保获取的是指针
            if (!(elemPtr.getType() instanceof PointerType)) {
                throw new RuntimeException("数组访问表达式必须返回指针");
            }
            
            // IRBuilder.createStore 会自动处理类型转换
            IRBuilder.createStore(rightValue, elemPtr, currentBlock);
        }
    }
    
    /**
     * 访问返回语句
     */
    private void visitReturnStmt(SyntaxTree.ReturnStmt stmt) {
        if (stmt.value != null) {
            // 有返回值
            visitExpr(stmt.value);
            Value retValue = currentValue;
            
            // 类型转换（如果需要）
            Type returnType = currentFunction.getReturnType();
            if (!retValue.getType().equals(returnType)) {
                if (returnType instanceof IntegerType && retValue.getType() instanceof FloatType) {
                    retValue = IRBuilder.createFloatToInt(retValue, currentBlock);
                } else if (returnType instanceof FloatType && retValue.getType() instanceof IntegerType) {
                    retValue = IRBuilder.createIntToFloat(retValue, currentBlock);
                } else if (returnType instanceof IntegerType && retValue.getType() instanceof IntegerType) {
                    // 处理整数类型之间的转换
                    IntegerType targetIntType = (IntegerType) returnType;
                    IntegerType retIntType = (IntegerType) retValue.getType();
                    
                    if (targetIntType.getBitWidth() > retIntType.getBitWidth()) {
                        // 扩展
                        retValue = IRBuilder.createZeroExtend(retValue, returnType, currentBlock);
                    } else if (targetIntType.getBitWidth() < retIntType.getBitWidth()) {
                        // 截断
                        retValue = IRBuilder.createTrunc(retValue, returnType, currentBlock);
                    }
                }
            }
            
            IRBuilder.createReturn(retValue, currentBlock);
        } else {
            // 无返回值
            IRBuilder.createReturn(currentBlock);
        }
        
        // 标记当前块为已终结，不再创建新的不可达块
        currentBlock = null;
    }
    
    /**
     * 访问if语句
     */
    private void visitIfStmt(SyntaxTree.IfStmt stmt) {
        // 如果当前块已经终结，不再生成代码
        if (currentBlock == null) {
            return;
        }
        
        // 创建必要的基本块
        BasicBlock thenBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock elseBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        // 条件求值
        visitExpr(stmt.cond);
        Value condValue = convertToBoolean(currentValue);
        
        // 条件分支
        IRBuilder.createCondBr(condValue, thenBlock, elseBlock, currentBlock);
        
        // 处理then分支
        currentBlock = thenBlock;
        
        // 如果在循环内，将mergeBlock替换为循环条件块
        BasicBlock realMergeBlock = mergeBlock;
        if (!mergeBlockStack.isEmpty() && !loopConditionBlocks.isEmpty()) {
            realMergeBlock = mergeBlockStack.peek();
        }
        
        visitStmt(stmt.thenBranch);
        
        // 如果当前块没有终结指令，添加跳转到合并块的指令
        if (currentBlock != null && !currentBlock.getInstructions().isEmpty() && 
            !(currentBlock.getInstructions().get(currentBlock.getInstructions().size() - 1) instanceof TerminatorInstruction)) {
            IRBuilder.createBr(realMergeBlock, currentBlock);
        } else if (currentBlock != null && currentBlock.getInstructions().isEmpty()) {
            // 如果是空块，也要跳转到合并块
            IRBuilder.createBr(realMergeBlock, currentBlock);
        }
        
        // 处理else分支
        currentBlock = elseBlock;
        if (stmt.elseBranch != null) {
            visitStmt(stmt.elseBranch);
            // 如果当前块没有终结指令，添加跳转到合并块的指令
            if (currentBlock != null && !currentBlock.getInstructions().isEmpty() && 
                !(currentBlock.getInstructions().get(currentBlock.getInstructions().size() - 1) instanceof TerminatorInstruction)) {
                IRBuilder.createBr(realMergeBlock, currentBlock);
            } else if (currentBlock != null && currentBlock.getInstructions().isEmpty()) {
                // 如果是空块，直接跳转到合并块
                IRBuilder.createBr(realMergeBlock, currentBlock);
            }
        } else {
            // 没有else分支，直接跳转到合并块
            IRBuilder.createBr(realMergeBlock, currentBlock);
        }
        
        // 只有当不在循环体内部时才使用常规的mergeBlock
        if (mergeBlockStack.isEmpty()) {
            // 继续在合并块生成代码
            currentBlock = mergeBlock;
        } else {
            // 如果在循环内部，跳过这个块
            currentBlock = null;
        }
    }
    
    /**
     * 访问while语句
     */
    private void visitWhileStmt(SyntaxTree.WhileStmt stmt) {
        // 如果当前块已经终结，不再生成代码
        if (currentBlock == null) {
            return;
        }
        
        // 创建基本块
        BasicBlock condBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock bodyBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock exitBlock = IRBuilder.createBasicBlock(currentFunction);
        
        // 跳转到条件块
        IRBuilder.createBr(condBlock, currentBlock);
        
        // 处理条件
        currentBlock = condBlock;
        visitExpr(stmt.cond);
        Value condValue = convertToBoolean(currentValue);
        IRBuilder.createCondBr(condValue, bodyBlock, exitBlock, currentBlock);
        
        // 记录循环信息，用于break和continue
        loopConditionBlocks.push(condBlock);
        loopExitBlocks.push(exitBlock);
        
        // 处理循环体
        currentBlock = bodyBlock;
        
        // 标记内部控制流的返回目标
        mergeBlockStack.push(condBlock);
        
        visitStmt(stmt.body);
        
        // 弹出标记
        mergeBlockStack.pop();
        
        // 如果循环体没有终结（比如通过break或return），添加跳回条件块的指令
        if (currentBlock != null && !currentBlock.getInstructions().isEmpty() && 
            !(currentBlock.getInstructions().get(currentBlock.getInstructions().size() - 1) instanceof TerminatorInstruction)) {
            IRBuilder.createBr(condBlock, currentBlock);
        }
        
        // 弹出循环信息
        loopConditionBlocks.pop();
        loopExitBlocks.pop();
        
        // 继续在退出块中生成代码
        currentBlock = exitBlock;
    }
    
    /**
     * 访问break语句
     */
    private void visitBreakStmt() {
        if (loopExitBlocks.isEmpty()) {
            throw new RuntimeException("break语句必须在循环内部");
        }
        
        // 跳转到当前循环的退出块
        IRBuilder.createBr(loopExitBlocks.peek(), currentBlock);
        
        // 标记当前块为已终结，不再创建新的不可达块
        currentBlock = null;
    }
    
    /**
     * 访问continue语句
     */
    private void visitContinueStmt() {
        if (loopConditionBlocks.isEmpty()) {
            throw new RuntimeException("continue语句必须在循环内部");
        }
        
        // 跳转到当前循环的条件块
        IRBuilder.createBr(loopConditionBlocks.peek(), currentBlock);
        
        // 标记当前块为已终结，不再创建新的不可达块
        currentBlock = null;
    }
    
    /**
     * 访问表达式
     */
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
            // 在表达式中访问数组元素时需要加载值
            visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
        } else if (expr instanceof SyntaxTree.ArrayInitExpr) {
            visitArrayInitExpr((SyntaxTree.ArrayInitExpr) expr);
        } else {
            throw new RuntimeException("不支持的表达式类型: " + expr.getClass().getName());
        }
    }
    
    /**
     * 访问字面量表达式
     */
    private void visitLiteralExpr(SyntaxTree.LiteralExpr expr) {
        Object value = expr.value;
        if (value instanceof Integer) {
            currentValue = new ConstantInt((Integer) value);
        } else if (value instanceof Float) {
            currentValue = new ConstantFloat((Float) value);
        } else {
            throw new RuntimeException("不支持的字面量类型: " + value.getClass().getName());
        }
    }
    
    /**
     * 访问变量表达式
     */
    private void visitVarExpr(SyntaxTree.VarExpr expr) {
        String name = expr.name;
        Value value = findVariable(name);
        
        if (value == null) {
            throw new RuntimeException("未定义的变量: " + name);
        }
        
        // 如果是指针类型，需要加载值
        if (value.getType() instanceof PointerType) {
            // 指针类型的变量，如局部变量、数组元素等，需要加载
            currentValue = IRBuilder.createLoad(value, currentBlock);
        } else {
            // 不是指针类型，直接使用
            currentValue = value;
        }
    }
    
    /**
     * 访问表达式，并确保加载指针类型的值
     */
    private void visitExprAndLoad(SyntaxTree.Expr expr) {
        visitExpr(expr);
        
        // 如果结果是指针类型，并且不是数组访问（因为数组访问可能是左值），需要加载值
        if (currentValue.getType() instanceof PointerType && !(expr instanceof SyntaxTree.ArrayAccessExpr)) {
            currentValue = IRBuilder.createLoad(currentValue, currentBlock);
        }
    }
    
    /**
     * 访问数组访问表达式并加载值
     * 这个方法在需要读取数组元素值时使用
     */
    private void visitArrayAccessExprAndLoad(SyntaxTree.ArrayAccessExpr expr) {
        // 获取数组变量
        String arrayName = expr.arrayName;
        Value arrayPtr = findVariable(arrayName);
        
        if (arrayPtr == null) {
            throw new RuntimeException("未定义的数组: " + arrayName);
        }
        
        // 获取数组维度信息
        List<Integer> dimensions = findArrayDimensions(arrayName);
        if (dimensions == null) {
            throw new RuntimeException("无法获取数组 " + arrayName + " 的维度信息");
        }
        
        // 处理索引表达式
        List<Value> indices = new ArrayList<>();
        for (SyntaxTree.Expr indexExpr : expr.indices) {
            visitExpr(indexExpr);
            indices.add(currentValue);
        }
        
        // 检查索引数量
        if (indices.size() > dimensions.size()) {
            throw new RuntimeException("数组 " + arrayName + " 的索引数量过多");
        }

        // 对二维数组特殊处理
        if (dimensions.size() == 2 && indices.size() == 2) {
            // 获取行列索引
            Value rowIndex = indices.get(0);
            Value colIndex = indices.get(1);
            int colSize = dimensions.get(1);
            
            // 计算一维偏移量: row * cols + col
            Value rowOffset = IRBuilder.createBinaryInst(OpCode.MUL, rowIndex, new ConstantInt(colSize), currentBlock);
            Value finalOffset = IRBuilder.createBinaryInst(OpCode.ADD, rowOffset, colIndex, currentBlock);
            
            // 获取元素指针
            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, finalOffset, currentBlock);
            
            // 加载值
            currentValue = IRBuilder.createLoad(elemPtr, currentBlock);
            return;
        }
        
        // 其他维度的数组处理
        Value offset = new ConstantInt(0);
        List<Integer> factors = new ArrayList<>();
        
        // 计算每个维度的因子
        for (int i = 0; i < dimensions.size(); i++) {
            int factor = 1;
            for (int j = i + 1; j < dimensions.size(); j++) {
                factor *= dimensions.get(j);
            }
            factors.add(factor);
        }
        
        // 计算总偏移量
        for (int i = 0; i < indices.size(); i++) {
            Value indexFactor = IRBuilder.createBinaryInst(
                OpCode.MUL,
                indices.get(i),
                new ConstantInt(factors.get(i)),
                currentBlock
            );
            offset = IRBuilder.createBinaryInst(OpCode.ADD, offset, indexFactor, currentBlock);
        }
        
        // 获取元素指针
        Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, offset, currentBlock);
        
        // 加载值
        currentValue = IRBuilder.createLoad(elemPtr, currentBlock);
    }
    
    /**
     * 访问一元表达式
     */
    private void visitUnaryExpr(SyntaxTree.UnaryExpr expr) {
        // 先处理操作数
        visitExpr(expr.expr);
        Value operand = currentValue;
        
        // 根据操作符类型处理
        switch (expr.op) {
            case "+":
                // 一元加法不需要任何操作
                break;
            case "-":
                // 如果在全局变量初始化上下文中，使用UnaryInstruction
                if (currentBlock == null) {
                    if (operand instanceof Constant) {
                        if (operand instanceof ConstantInt) {
                            currentValue = new ConstantInt(-((ConstantInt) operand).getValue());
                        } else if (operand instanceof ConstantFloat) {
                            currentValue = new ConstantFloat(-((ConstantFloat) operand).getValue());
                        }
                    } else {
                        // 创建一元操作指令，用于常量表达式评估
                        currentValue = new UnaryInstruction(OpCode.NEG, operand, "neg_" + operand.getName());
                    }
                } else {
                    // 在基本块中，使用二元指令实现一元负号
                    // 对于整数，创建0-operand
                    if (operand.getType() instanceof IntegerType) {
                        Value zero = new ConstantInt(0);
                        currentValue = IRBuilder.createBinaryInst(OpCode.SUB, zero, operand, currentBlock);
                    }
                    // 对于浮点数，创建0.0-operand
                    else if (operand.getType() instanceof FloatType) {
                        Value zero = new ConstantFloat(0.0f);
                        currentValue = IRBuilder.createBinaryInst(OpCode.FSUB, zero, operand, currentBlock);
                    }
                }
                break;
            case "!":
                // 逻辑非：将表达式与0比较，如果等于0则为1，否则为0
                if (operand.getType() instanceof IntegerType) {
                    Value zero = new ConstantInt(0);
                    currentValue = IRBuilder.createICmp(OpCode.EQ, operand, zero, currentBlock);
                } else if (operand.getType() instanceof FloatType) {
                    // 将浮点数转为整数再比较
                    Value intValue = IRBuilder.createFloatToInt(operand, currentBlock);
                    Value zero = new ConstantInt(0);
                    currentValue = IRBuilder.createICmp(OpCode.EQ, intValue, zero, currentBlock);
                }
                break;
            default:
                throw new RuntimeException("不支持的一元操作符: " + expr.op);
        }
    }
    
    /**
     * 访问二元表达式
     */
    private void visitBinaryExpr(SyntaxTree.BinaryExpr expr) {
        String op = expr.op;
        
        // 特殊处理逻辑运算符，实现短路评估
        if (op.equals("&&")) {
            visitLogicalAnd(expr.left, expr.right);
            return;
        } else if (op.equals("||")) {
            visitLogicalOr(expr.left, expr.right);
            return;
        }
        
        // 处理普通的二元运算
        visitExpr(expr.left);
        Value leftValue = currentValue;
        
        visitExpr(expr.right);
        Value rightValue = currentValue;
        
        // 处理比较操作
        if (isCmpOp(op)) {
            OpCode compareType;
            OpCode predicate;
            
            // 判断是整数还是浮点比较
            if (leftValue.getType() instanceof FloatType || rightValue.getType() instanceof FloatType) {
                compareType = OpCode.FCMP;
                predicate = getFloatPredicate(op);
                    } else {
                compareType = OpCode.ICMP;
                predicate = getIntPredicate(op);
            }
            
            currentValue = IRBuilder.createCompare(compareType, predicate, leftValue, rightValue, currentBlock);
                    } else {
            // 确定操作码
            OpCode opCode = getOpCodeForBinaryOp(op);
            
            // 普通运算
            currentValue = IRBuilder.createBinaryInst(opCode, leftValue, rightValue, currentBlock);
        }
    }
    
    /**
     * 处理逻辑与(&&)，实现短路评估
     */
    private void visitLogicalAnd(SyntaxTree.Expr left, SyntaxTree.Expr right) {
        // 如果当前块已经终结，不再生成代码
        if (currentBlock == null) {
            return;
        }
        
        // 创建基本块
        BasicBlock rightBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        // 记住左值计算的基本块
        BasicBlock leftBlock = currentBlock;
        
        // 求值左操作数
        visitExpr(left);
        Value leftValue = convertToBoolean(currentValue);
        
        // 如果左边为假，短路到合并块；否则继续计算右边
        IRBuilder.createCondBr(leftValue, rightBlock, mergeBlock, currentBlock);
        
        // 求值右操作数
        currentBlock = rightBlock;
        visitExpr(right);
        Value rightValue = convertToBoolean(currentValue);
        IRBuilder.createBr(mergeBlock, currentBlock);
        
        // 合并结果
        currentBlock = mergeBlock;
        
        // 创建phi节点
        PhiInstruction phi = IRBuilder.createPhi(IntegerType.I1, currentBlock);
        // 左边为假时，结果为假(0)
        phi.addIncoming(new ConstantInt(0), leftBlock);
        // 右边计算结果作为整体结果
        phi.addIncoming(rightValue, rightBlock);
        
        currentValue = phi;
    }
    
    /**
     * 处理逻辑或(||)，实现短路评估
     */
    private void visitLogicalOr(SyntaxTree.Expr left, SyntaxTree.Expr right) {
        // 如果当前块已经终结，不再生成代码
        if (currentBlock == null) {
            return;
        }
        
        // 创建基本块
        BasicBlock rightBlock = IRBuilder.createBasicBlock(currentFunction);
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        // 记住左值计算的基本块
        BasicBlock leftBlock = currentBlock;
        
        // 求值左操作数
        visitExpr(left);
        Value leftValue = convertToBoolean(currentValue);
        
        // 如果左边为真，短路到合并块；否则继续计算右边
        IRBuilder.createCondBr(leftValue, mergeBlock, rightBlock, currentBlock);
        
        // 求值右操作数
        currentBlock = rightBlock;
        visitExpr(right);
        Value rightValue = convertToBoolean(currentValue);
        IRBuilder.createBr(mergeBlock, currentBlock);
        
        // 合并结果
        currentBlock = mergeBlock;
        
        // 创建phi节点
        PhiInstruction phi = IRBuilder.createPhi(IntegerType.I1, currentBlock);
        // 左边为真时，结果为真(1)
        phi.addIncoming(new ConstantInt(1), leftBlock);
        // 右边计算结果作为整体结果
        phi.addIncoming(rightValue, rightBlock);
        
        currentValue = phi;
    }
    
    /**
     * 判断是否为比较操作符
     */
    private boolean isCmpOp(String op) {
        return op.equals("==") || op.equals("!=") || op.equals("<") || op.equals("<=") || 
               op.equals(">") || op.equals(">=");
    }
    
    /**
     * 获取二元操作对应的OpCode
     */
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
            // 添加比较操作符的支持
            case "==" -> OpCode.ICMP;
            case "!=" -> OpCode.ICMP;
            case "<" -> OpCode.ICMP;
            case "<=" -> OpCode.ICMP;
            case ">" -> OpCode.ICMP;
            case ">=" -> OpCode.ICMP;
            default -> throw new RuntimeException("不支持的二元运算符: " + op);
        };
    }
    
    /**
     * 获取整数比较对应的predicate
     */
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
    
    /**
     * 获取浮点比较对应的predicate
     */
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
    
    /**
     * 将值转换为布尔值（0或1）
     */
    private Value convertToBoolean(Value value) {
        if (value.getType() instanceof IntegerType && ((IntegerType) value.getType()).getBitWidth() == 1) {
            return value; // 已经是布尔值
        } else if (value.getType() instanceof IntegerType) {
            // 与0比较，不等于0为真
            Value zero = new ConstantInt(0);
            return IRBuilder.createICmp(OpCode.NE, value, zero, currentBlock);
        } else if (value.getType() instanceof FloatType) {
            // 浮点数转整数后与0比较
            Value intValue = IRBuilder.createFloatToInt(value, currentBlock);
            Value zero = new ConstantInt(0);
            return IRBuilder.createICmp(OpCode.NE, intValue, zero, currentBlock);
        } else if (value.getType() instanceof PointerType) {
            // 指针与null比较，不等于null为真
            // 在C语言中，任何非空指针都被视为true
            Value zero = new ConstantInt(0);
            return IRBuilder.createICmp(OpCode.NE, value, zero, currentBlock);
        }
        throw new RuntimeException("无法将类型 " + value.getType() + " 转换为布尔值");
    }
    
    /**
     * 访问函数调用表达式
     */
    private void visitCallExpr(SyntaxTree.CallExpr expr) {
        // 查找函数
        String funcName = expr.funcName;
        Value funcValue = findVariable(funcName);
        
        if (funcValue == null || !(funcValue instanceof Function)) {
            throw new RuntimeException("未定义的函数: " + funcName);
        }
        
        Function function = (Function) funcValue;
        List<Value> args = new ArrayList<>();
        
        // 处理参数
        for (SyntaxTree.Expr argExpr : expr.args) {
            visitExpr(argExpr);
            args.add(currentValue);
        }
        
        // 特殊处理：starttime和stoptime函数需要额外的参数
        if (funcName.equals("starttime") || funcName.equals("stoptime")) {
            args.add(new ConstantInt(0)); // 添加行号参数
        }
        
        // 检查参数类型并进行必要的类型转换
        List<Argument> funcArgs = function.getArguments();
        if (args.size() != funcArgs.size()) {
            throw new RuntimeException("函数 " + funcName + " 参数数量不匹配: 期望 " + 
                                      funcArgs.size() + ", 实际 " + args.size());
        }
        
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            Type paramType = funcArgs.get(i).getType();
            
            // 如果类型不匹配，尝试进行类型转换
            if (!arg.getType().equals(paramType)) {
                if (arg.getType() instanceof IntegerType && paramType instanceof FloatType) {
                    // 整数转浮点
                    args.set(i, IRBuilder.createIntToFloat(arg, currentBlock));
                } else if (arg.getType() instanceof FloatType && paramType instanceof IntegerType) {
                    // 浮点转整数
                    args.set(i, IRBuilder.createFloatToInt(arg, currentBlock));
                }
                // 其他类型不匹配的情况可能需要更复杂的处理
            }
        }
        
        // 创建函数调用指令
        currentValue = IRBuilder.createCall(function, args, currentBlock);
    }
    
    /**
     * 访问数组访问表达式
     */
    private void visitArrayAccessExpr(SyntaxTree.ArrayAccessExpr expr) {
        // 获取数组变量
        String arrayName = expr.arrayName;
        Value arrayPtr = findVariable(arrayName);
        
        if (arrayPtr == null) {
            throw new RuntimeException("未定义的数组: " + arrayName);
        }
        
        // 获取数组维度信息
        List<Integer> dimensions = findArrayDimensions(arrayName);
        if (dimensions == null) {
            throw new RuntimeException("无法获取数组 " + arrayName + " 的维度信息");
        }
        
        // 处理索引表达式
        List<Value> indices = new ArrayList<>();
        for (SyntaxTree.Expr indexExpr : expr.indices) {
            visitExpr(indexExpr);
            indices.add(currentValue);
        }
        
        // 检查索引数量
        if (indices.size() > dimensions.size()) {
            throw new RuntimeException("数组 " + arrayName + " 的索引数量过多");
        }
        
        // 针对二维数组优化
        if (dimensions.size() == 2 && indices.size() == 2) {
            Value firstIndex = indices.get(0); // 行索引
            Value secondIndex = indices.get(1); // 列索引
            int colSize = dimensions.get(1); // 列数
            
            // 计算 row * cols
            Value rowOffset = IRBuilder.createBinaryInst(
                        OpCode.MUL,
                firstIndex,
                new ConstantInt(colSize),
                        currentBlock
                    );
            
            // 计算 row * cols + col
            Value finalOffset = IRBuilder.createBinaryInst(
                OpCode.ADD,
                rowOffset,
                secondIndex,
                currentBlock
            );
            
            // 使用最终偏移量获取元素指针
            currentValue = IRBuilder.createGetElementPtr(arrayPtr, finalOffset, currentBlock);
            return;
        }

        // 对于其他维度的数组，正确计算偏移量
        Value offset = new ConstantInt(0);
                
                // 计算每个维度的因子
                List<Integer> factors = new ArrayList<>();
                for (int i = 0; i < dimensions.size(); i++) {
                    int factor = 1;
                    for (int j = i + 1; j < dimensions.size(); j++) {
                        factor *= dimensions.get(j);
                    }
                    factors.add(factor);
                }
                
                // 计算总偏移量
                for (int i = 0; i < indices.size(); i++) {
                    Value indexFactor = IRBuilder.createBinaryInst(
                        OpCode.MUL,
                        indices.get(i),
                        new ConstantInt(factors.get(i)),
                        currentBlock
                    );
                    offset = IRBuilder.createBinaryInst(OpCode.ADD, offset, indexFactor, currentBlock);
                }
                
                // 使用GEP指令获取元素指针
                currentValue = IRBuilder.createGetElementPtr(arrayPtr, offset, currentBlock);
    }
    
    /**
     * 访问数组初始化表达式
     */
    private void visitArrayInitExpr(SyntaxTree.ArrayInitExpr expr) {
        // 暂时不处理数组初始化表达式，这通常在变量声明中处理
        throw new RuntimeException("数组初始化表达式应在变量声明中处理");
    }
    
    /**
     * 处理局部数组声明
     */
    private void processLocalArrayDecl(String name, Type elementType, List<Integer> dimensions, SyntaxTree.ArrayInitExpr initExpr) {
        // 计算数组总大小
        int totalSize = 1;
        for (int dim : dimensions) {
            totalSize *= dim;
        }
        
        // 创建数组分配指令
        AllocaInstruction arrayPtr = IRBuilder.createArrayAlloca(elementType, totalSize, currentBlock);
        
        // 添加到符号表
        addVariable(name, arrayPtr);
        
        // 存储维度信息，这对于后续数组索引计算很关键
        addArrayDimensions(name, dimensions);
        
        // 初始化数组元素
        if (initExpr != null) {
            processArrayInit(initExpr, arrayPtr, dimensions, elementType);
        } else {
            // 如果没有初始化表达式，初始化为0
            for (int i = 0; i < totalSize; i++) {
                Value indexValue = new ConstantInt(i);
                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                
                if (elementType == IntegerType.I32) {
                    IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                } else if (elementType == FloatType.F32) {
                    IRBuilder.createStore(new ConstantFloat(0.0f), elemPtr, currentBlock);
                }
            }
        }
    }
    
    /**
     * 查找变量
     */
    private Value findVariable(String name) {
        // 从内向外查找符号表
        for (int i = symbolTables.size() - 1; i >= 0; i--) {
            HashMap<String, Value> table = symbolTables.get(i);
            if (table.containsKey(name)) {
                return table.get(name);
            }
        }
        return null;
    }
    
    /**
     * 添加变量到当前符号表
     */
    private void addVariable(String name, Value value) {
        symbolTables.get(symbolTables.size() - 1).put(name, value);
    }
    
    /**
     * 添加数组维度信息
     */
    private void addArrayDimensions(String name, List<Integer> dimensions) {
        arrayDimensions.get(arrayDimensions.size() - 1).put(name, dimensions);
    }
    
    /**
     * 查找数组维度信息
     */
    private List<Integer> findArrayDimensions(String name) {
        for (int i = arrayDimensions.size() - 1; i >= 0; i--) {
            HashMap<String, List<Integer>> table = arrayDimensions.get(i);
            if (table.containsKey(name)) {
                return table.get(name);
            }
        }
        return null;
    }
    
    /**
     * 进入新作用域
     */
    private void enterScope() {
        symbolTables.add(new HashMap<>());
        arrayDimensions.add(new HashMap<>());
    }
    
    /**
     * 离开当前作用域
     */
    private void exitScope() {
        symbolTables.remove(symbolTables.size() - 1);
        arrayDimensions.remove(arrayDimensions.size() - 1);
    }
} 
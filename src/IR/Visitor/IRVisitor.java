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
    private final Stack<BasicBlock> loopEndBlockStack = new Stack<>();
    
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
        
        // 最终验证：确保所有函数中的PHI节点都是正确的
        validateAllPhiNodesInModule();
        
        return module;
    }
    
    /**
     * 验证模块中所有函数的PHI节点
     */
    private void validateAllPhiNodesInModule() {
        if (module == null) return;
        
        // 验证所有普通函数
        for (Function function : module.functions()) {
            IRBuilder.validateAllPhiNodes(function);
        }
    }
    
    /**
     * 初始化标准库函数
     */
    private void initializeLibraryFunctions() {
        // 声明输入输出相关的库函数
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
        
        // 时间函数
        Function startTimeFunc = declareLibFunction("_sysy_starttime", VoidType.VOID, IntegerType.I32);
        Function stopTimeFunc = declareLibFunction("_sysy_stoptime", VoidType.VOID, IntegerType.I32);
        
        // 将C代码中使用的函数名也添加到符号表中，以便在C代码调用时能够找到这些函数
        symbolTables.get(0).put("starttime", startTimeFunc);
        symbolTables.get(0).put("stoptime", stopTimeFunc);
        
        // 声明memset函数
        declareLibFunction("memset", VoidType.VOID, new PointerType(IntegerType.I32), IntegerType.I32, IntegerType.I32);
    }
    
    /**
     * 声明标准库函数
     */
    private Function declareLibFunction(String name, Type returnType, Type... argTypes) {
        Function func = IRBuilder.createExternalFunction("@" + name, returnType, module);
        
        // 添加参数
        for (int i = 0; i < argTypes.length; i++) {
            IRBuilder.createArgument("%" + name + "_arg" + i, argTypes[i], func, i);
        }
        
        // 将函数添加到符号表
        symbolTables.get(0).put(name, func);
        
        return func;
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
                baseType = FloatType.F64;
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
                            } else if (baseType == FloatType.F64 && initValue instanceof ConstantInt) {
                                // 整数常量转浮点数
                                initValue = new ConstantFloat((double)((ConstantInt)initValue).getValue());
                            }
                        } else if (currentValue instanceof Constant) {
                            // 如果是常量但不需要进一步评估
                            initValue = (Constant) currentValue;
                            
                            // 类型转换处理
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
                    // 默认初始化为0
                    if (baseType == IntegerType.I32) {
                        initValue = new ConstantInt(0);
                    } else { // FloatType
                        initValue = new ConstantFloat(0.0);
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
                        // 检查是否是全零初始化数组
                        boolean isAllZero = isAllZeroInitializer((SyntaxTree.ArrayInitExpr) varDef.init);
                        
                        if (isAllZero) {
                            // 全零初始化，直接使用zeroinitializer
                            arrayVar.setZeroInitialized(totalSize);
                        } else {
                            // 处理数组初始化表达式
                            List<Value> initValues = processGlobalArrayInit((SyntaxTree.ArrayInitExpr) varDef.init, 
                                                                           dims, 
                                                                           baseType);
                            arrayVar.setArrayValues(initValues);
                        }
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
     * 检查数组初始化表达式是否全为零值
     * @param initExpr 数组初始化表达式
     * @return 如果全为零值返回true，否则返回false
     */
    private boolean isAllZeroInitializer(SyntaxTree.ArrayInitExpr initExpr) {
        // 检查是否是空初始化 {}
        if (initExpr.elements.isEmpty()) {
            return true;
        }
        
        for (SyntaxTree.Expr expr : initExpr.elements) {
            if (expr instanceof SyntaxTree.ArrayInitExpr) {
                // 递归检查嵌套数组
                if (!isAllZeroInitializer((SyntaxTree.ArrayInitExpr) expr)) {
                    return false;
                }
            } else if (expr instanceof SyntaxTree.LiteralExpr) {
                // 检查字面量是否为0
                Object value = ((SyntaxTree.LiteralExpr) expr).value;
                if (value instanceof Integer && (Integer) value != 0) {
                    return false;
                } else if (value instanceof Float && (Float) value != 0.0f) {
                    return false;
                } else if (value instanceof Double && (Double) value != 0.0) {
                    return false;
                }
            } else {
                // 非字面量表达式，保守估计可能非零
                return false;
            }
        }
        
        // 如果数组为空或所有元素都是0，则返回true
        return true;
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
        
        // 检查是否是全零初始化数组
        boolean isAllZero = isAllZeroInitializer(initExpr);
        
        if (isAllZero) {
            // 全零初始化，返回空列表表示使用zeroinitializer
            return new ArrayList<>();
        }
        
        // 初始化结果列表，先全部填充0
        List<Value> result = new ArrayList<>(totalSize);
        for (int i = 0; i < totalSize; i++) {
            if (elementType == IntegerType.I32) {
                result.add(new ConstantInt(0));
            } else { // FloatType
                result.add(new ConstantFloat(0.0));
            }
        }
        
        // 递归填充初始化值
        int[] indices = new int[dims.size()];
        fillArrayInitValues(initExpr, result, elementType, dims, indices, 0);
        
        return result;
    }

    /**
     * 递归填充多维数组的初始化值
     * @param expr 初始化表达式
     * @param result 结果列表
     * @param elementType 元素类型
     * @param dims 每个维度的大小
     * @param indices 当前索引数组
     * @param dimLevel 当前维度级别
     */
    private void fillArrayInitValues(SyntaxTree.Expr expr, List<Value> result,
                                   Type elementType, List<Integer> dims, 
                                   int[] indices, int dimLevel) {
        if (expr instanceof SyntaxTree.ArrayInitExpr) {
            SyntaxTree.ArrayInitExpr arrayInit = (SyntaxTree.ArrayInitExpr) expr;
            
            // 处理当前维度的每个元素
            for (int i = 0; i < Math.min(arrayInit.elements.size(), dims.get(dimLevel)); i++) {
                indices[dimLevel] = i;
                
                if (dimLevel < dims.size() - 1) {
                    // 非最后维度，递归处理子数组
                    if (i < arrayInit.elements.size() && arrayInit.elements.get(i) instanceof SyntaxTree.ArrayInitExpr) {
                        // 如果子元素是数组初始化表达式，递归处理下一维度
                        fillArrayInitValues(arrayInit.elements.get(i), result, elementType, dims, indices, dimLevel + 1);
                    } else if (i < arrayInit.elements.size()) {
                        // 如果子元素不是数组初始化表达式但当前不是最后维度，
                        // 将该元素放在下一维度的首位置，其余位置填0
                        indices[dimLevel + 1] = 0;
                        fillSingleElement(arrayInit.elements.get(i), result, elementType, dims, indices);
                        
                        // 重置下一维度的索引，保持当前维度索引不变
                        for (int j = dimLevel + 1; j < indices.length; j++) {
                            indices[j] = 0;
                        }
                    }
                } else {
                    // 最后一维，处理单个元素
                    if (i < arrayInit.elements.size()) {
                        fillSingleElement(arrayInit.elements.get(i), result, elementType, dims, indices);
                    }
                }
            }
        } else {
            // 单个元素直接填充
            fillSingleElement(expr, result, elementType, dims, indices);
        }
    }

    /**
     * 填充单个数组元素
     * @param expr 表达式
     * @param result 结果列表
     * @param elementType 元素类型
     * @param dims 每个维度的大小
     * @param indices 当前索引数组
     */
    private void fillSingleElement(SyntaxTree.Expr expr, List<Value> result,
                                 Type elementType, List<Integer> dims, int[] indices) {
        // 计算线性索引
        int linearIndex = calculateLinearIndex(dims, indices);
        
        // 处理表达式
        currentValue = null;
        visitExpr(expr);
        
        if (currentValue != null) {
            Constant constResult = IR.Pass.ConstantExpressionEvaluator.evaluate(currentValue);
            Value valueToAdd = null;
            
            if (constResult != null) {
                valueToAdd = constResult;
            } else if (currentValue instanceof Constant) {
                valueToAdd = currentValue;
            } else {
                throw new RuntimeException("全局数组初始化必须使用常量表达式");
            }
            
            // 类型转换
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

    /**
     * 计算多维数组的线性索引
     * @param dims 每个维度的大小
     * @param indices 当前索引数组
     * @return 线性索引
     */
    private int calculateLinearIndex(List<Integer> dims, int[] indices) {
        int linearIndex = 0;
        int factor = 1;
        
        // 从最后一个维度开始计算
        for (int i = dims.size() - 1; i >= 0; i--) {
            linearIndex += indices[i] * factor;
            if (i > 0) {
                factor *= dims.get(i);
            }
        }
        
        return linearIndex;
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
                retType = FloatType.F64;
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
                    } else if (retType.isFloatType()) {
                        IRBuilder.createReturn(new ConstantFloat(0.0), currentBlock); // 返回0.0
                    }
                }
            } else {
                // 如果当前块是空的，添加一个默认的返回指令
                if (retType == VoidType.VOID) {
                    IRBuilder.createReturn(currentBlock); // void返回
                } else if (retType == IntegerType.I32) {
                    IRBuilder.createReturn(new ConstantInt(0), currentBlock); // 返回0
                } else if (retType.isFloatType()) {
                    IRBuilder.createReturn(new ConstantFloat(0.0), currentBlock); // 返回0.0
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
                    } else if (retType.isFloatType()) {
                        IRBuilder.createReturn(new ConstantFloat(0.0), block);
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
                        } else if (retType.isFloatType()) {
                            IRBuilder.createReturn(new ConstantFloat(0.0), block);
                        }
                    }
                }
            }
        }

        // 验证所有PHI节点的前驱关系
        IRBuilder.validateAllPhiNodes(function);
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
                type = isArray ? new PointerType(FloatType.F64) : FloatType.F64;
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
        if (stmt.expr != null) {
            visitExpr(stmt.expr);
            // 表达式语句不需要保存结果值
        }
        // 如果表达式为null（空语句），不做任何操作
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
                baseType = FloatType.F64;
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
                    } else if (baseType == FloatType.F64) {
                        IRBuilder.createStore(new ConstantFloat(0.0), allocaInst, currentBlock);
                    }
                }
                
                // 添加到符号表
                addVariable(name, allocaInst);
            } else {
                // 数组变量
                // 检查是否是特殊情况，包含数组访问表达式的初始化
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
                
                // 计算数组总大小
                int totalSize = 1;
                for (Integer dim : dims) {
                    totalSize *= dim;
                }
                
                // 创建数组分配指令
                AllocaInstruction arrayPtr = IRBuilder.createArrayAlloca(baseType, totalSize, currentBlock);
                
                // 添加到符号表
                addVariable(name, arrayPtr);
                
                // 存储维度信息
                addArrayDimensions(name, dims);
                
                if (hasArrayAccess && varDef.init instanceof SyntaxTree.ArrayInitExpr) {
                    // 先初始化为0
                    for (int i = 0; i < totalSize; i++) {
                        Value indexValue = new ConstantInt(i);
                        Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                        if (baseType == IntegerType.I32) {
                            IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                        } else { // FloatType
                            IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
                        }
                    }
                    
                    // 计算索引因子
                    List<Integer> factors = new ArrayList<>();
                    for (int i = 0; i < dims.size(); i++) {
                        int factor = 1;
                        for (int j = i + 1; j < dims.size(); j++) {
                            factor *= dims.get(j);
                        }
                        factors.add(factor);
                    }
                    
                    // 直接处理一维平铺的数组初始化
                    List<SyntaxTree.Expr> elements = ((SyntaxTree.ArrayInitExpr) varDef.init).elements;
                    int currentPos = 0;
                    
                    for (SyntaxTree.Expr expr : elements) {
                        // 如果超出数组范围，停止初始化
                        if (currentPos >= totalSize) break;
                        
                        // 如果是数组访问表达式，需要特殊处理
                        if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                            // 访问数组表达式获取值
                            visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
                            Value value = currentValue;
                            
                            // 类型转换
                            if (!value.getType().equals(baseType)) {
                                if (baseType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (baseType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            // 存储值
                            Value indexValue = new ConstantInt(currentPos);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        } 
                        // 如果是数组初始化表达式，处理嵌套初始化
                        else if (expr instanceof SyntaxTree.ArrayInitExpr) {
                            SyntaxTree.ArrayInitExpr subInit = (SyntaxTree.ArrayInitExpr) expr;
                            
                            // 处理嵌套初始化的元素
                            for (int i = 0; i < Math.min(subInit.elements.size(), dims.get(dims.size() - 1)); i++) {
                                if (currentPos >= totalSize) break;
                                
                                SyntaxTree.Expr subExpr = subInit.elements.get(i);
                                
                                // 访问表达式获取值
                                if (subExpr instanceof SyntaxTree.ArrayAccessExpr) {
                                    visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) subExpr);
                                } else {
                                    visitExpr(subExpr);
                                }
                                Value value = currentValue;
                                
                                // 类型转换
                                if (!value.getType().equals(baseType)) {
                                    if (baseType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                        value = IRBuilder.createFloatToInt(value, currentBlock);
                                    } else if (baseType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                        value = IRBuilder.createIntToFloat(value, currentBlock);
                                    }
                                }
                                
                                // 存储值
                                Value indexValue = new ConstantInt(currentPos);
                                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                                IRBuilder.createStore(value, elemPtr, currentBlock);
                                
                                currentPos++;
                            }
                        } 
                        // 处理普通表达式
                        else {
                            visitExpr(expr);
                            Value value = currentValue;
                            
                            // 类型转换
                            if (!value.getType().equals(baseType)) {
                                if (baseType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (baseType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            // 存储值
                            Value indexValue = new ConstantInt(currentPos);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        }
                        
                        currentPos++;
                    }
                } else if (varDef.init != null) {
                    // 标准数组初始化
                    SyntaxTree.ArrayInitExpr initExpr = varDef.init instanceof SyntaxTree.ArrayInitExpr ? 
                                                      (SyntaxTree.ArrayInitExpr) varDef.init : null;
                    processLocalArrayDecl(name, baseType, dims, initExpr);
                }
                // 否则保持默认值
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
                return FloatType.F64;
            default:
                throw new RuntimeException("不支持的类型: " + typeName);
        }
    }
    
    /**
     * 处理数组初始化
     */
    private void processArrayInit(SyntaxTree.ArrayInitExpr initExpr, Value arrayPtr, List<Integer> dims, Type elementType) {
        List<Integer> factors = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            int factor = 1;
            for (int j = i + 1; j < dims.size(); j++) {
                factor *= dims.get(j);
            }
            factors.add(factor);
        }
        
        // 计算数组总大小
        int totalSize = 1;
        for (Integer dim : dims) {
            totalSize *= dim;
        }
        
        // 检查是否可以使用memset优化（仅用于整型数组）
        boolean useMemset = (elementType == IntegerType.I32 || elementType == IntegerType.I1 || elementType == IntegerType.I8);
        int elementSizeInBytes = elementType.getSize();
        int totalSizeInBytes = totalSize * elementSizeInBytes;
        
        // 如果是整型数组且全为0，直接使用memset优化
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
        
        // 初始化所有元素为0（防止部分初始化）
        if (useMemset) {
            // 对于整型数组，使用memset
            Function memsetFunc = module.getLibFunction("memset");
            if (memsetFunc != null) {
                List<Value> args = new ArrayList<>();
                args.add(arrayPtr);
                args.add(new ConstantInt(0));
                args.add(new ConstantInt(totalSizeInBytes));
                IRBuilder.createCall(memsetFunc, args, currentBlock);
            } else {
                // 如果memset不可用，使用传统方法初始化为0
                for (int i = 0; i < totalSize; i++) {
                    Value indexValue = new ConstantInt(i);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                }
            }
        } else {
            // 对于浮点数组，使用循环逐个初始化
            for (int i = 0; i < totalSize; i++) {
                Value indexValue = new ConstantInt(i);
                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
            }
        }
        
        // 如果数组全为0，上面已经完成了初始化，可以直接返回
        if (isLocalArrayAllZero(initExpr)) {
            return;
        }
        
        // 使用嵌套数组初始化表达式的结构进行初始化
        // 创建索引数组跟踪当前位置
        int[] indices = new int[dims.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = 0; // 初始化所有索引为0
        }
        
        // 递归处理数组初始化，保持与数组访问相同的线性偏移计算逻辑
        initializeArray(initExpr, arrayPtr, dims, factors, indices, 0, elementType);
    }
    
    /**
     * 递归初始化数组元素
     * @param expr 初始化表达式
     * @param arrayPtr 数组指针
     * @param dims 数组维度
     * @param factors 线性索引计算因子
     * @param indices 当前索引数组
     * @param dimLevel 当前维度级别
     * @param elementType 元素类型
     */
    private void initializeArray(SyntaxTree.Expr expr, Value arrayPtr, List<Integer> dims, 
                                List<Integer> factors, int[] indices, int dimLevel, Type elementType) {
        // 处理多级嵌套的数组初始化
        if (expr instanceof SyntaxTree.ArrayInitExpr) {
            SyntaxTree.ArrayInitExpr arrayInit = (SyntaxTree.ArrayInitExpr) expr;
            int elementCount = arrayInit.elements.size();
            
            // 如果当前维度已经是最后一维，则在这一维中按顺序初始化元素
            if (dimLevel == dims.size() - 1) {
                for (int i = 0; i < Math.min(elementCount, dims.get(dimLevel)); i++) {
                    // 更新当前维度的索引
                    indices[dimLevel] = i;
                    
                    // 获取当前元素的表达式
                    SyntaxTree.Expr element = arrayInit.elements.get(i);
                    
                    // 计算线性索引
                    int linearIndex = 0;
                    for (int j = 0; j < dims.size(); j++) {
                        linearIndex += indices[j] * factors.get(j);
                    }
                    
                    // 处理表达式
                    if (element instanceof SyntaxTree.ArrayAccessExpr) {
                        // 数组访问需要特殊处理，确保我们获取的是正确的值
                        visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) element);
                    } else {
                        // 访问表达式获取值
                        visitExpr(element);
                    }
                    Value value = currentValue;
                    
                    // 类型转换（如果需要）
                    if (!value.getType().equals(elementType)) {
                        if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                            value = IRBuilder.createFloatToInt(value, currentBlock);
                        } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                            value = IRBuilder.createIntToFloat(value, currentBlock);
                        }
                    }
                    
                    // 创建指针并存储值
                    Value indexValue = new ConstantInt(linearIndex);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(value, elemPtr, currentBlock);
                }
            }
            // 对于非最后一维，我们需要区分处理方式
            else {
                // 检查是否是花括号混合形式的初始化
                boolean hasNestedArray = false;
                for (SyntaxTree.Expr element : arrayInit.elements) {
                    if (element instanceof SyntaxTree.ArrayInitExpr) {
                        hasNestedArray = true;
                        break;
                    }
                }
                
                if (hasNestedArray) {
                    // 有嵌套数组，按照正常的多维数组处理
                    int curIdx = 0; // 当前处理的元素在当前维度的索引
                    
                    for (int i = 0; i < elementCount; i++) {
                        SyntaxTree.Expr element = arrayInit.elements.get(i);
                        
                        if (element instanceof SyntaxTree.ArrayInitExpr) {
                            // 如果是嵌套数组，则作为下一个维度整体处理
                            if (curIdx < dims.get(dimLevel)) {
                                indices[dimLevel] = curIdx++;
                                initializeArray(element, arrayPtr, dims, factors, indices, dimLevel + 1, elementType);
                            }
                        } else {
                            // 单个元素，放在当前维度的对应位置，下一维度的第一个位置
                            if (curIdx < dims.get(dimLevel)) {
                                indices[dimLevel] = curIdx++;
                                
                                // 将下一维度的索引设为0
                                for (int j = dimLevel + 1; j < dims.size(); j++) {
                                    indices[j] = 0;
                                }
                                
                                // 计算线性索引
                                int linearIndex = 0;
                                for (int j = 0; j < dims.size(); j++) {
                                    linearIndex += indices[j] * factors.get(j);
                                }
                                
                                // 处理表达式
                                if (element instanceof SyntaxTree.ArrayAccessExpr) {
                                    // 数组访问需要特殊处理，确保我们获取的是正确的值
                                    visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) element);
                                } else {
                                    // 访问表达式获取值
                                    visitExpr(element);
                                }
                                Value value = currentValue;
                                
                                // 类型转换
                                if (!value.getType().equals(elementType)) {
                                    if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                        value = IRBuilder.createFloatToInt(value, currentBlock);
                                    } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                        value = IRBuilder.createIntToFloat(value, currentBlock);
                                    }
                                }
                                
                                // 存储值
                                Value indexValue = new ConstantInt(linearIndex);
                                Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                                IRBuilder.createStore(value, elemPtr, currentBlock);
                            }
                        }
                    }
                } else {
                    // 没有嵌套数组，按照一维数组平铺处理
                    int currentPos = 0;
                    for (int i = 0; i < dims.get(dimLevel) && currentPos < elementCount; i++) {
                        indices[dimLevel] = i;
                        
                        // 对于当前维度的每个位置，填充下一维度的元素
                        for (int j = 0; j < dims.get(dimLevel + 1) && currentPos < elementCount; j++) {
                            indices[dimLevel + 1] = j;
                            
                            // 将剩余维度的索引设为0
                            for (int k = dimLevel + 2; k < dims.size(); k++) {
                                indices[k] = 0;
                            }
                            
                            // 计算线性索引
                            int linearIndex = 0;
                            for (int k = 0; k < dims.size(); k++) {
                                linearIndex += indices[k] * factors.get(k);
                            }
                            
                            // 获取当前元素
                            SyntaxTree.Expr element = arrayInit.elements.get(currentPos++);
                            
                            // 处理表达式
                            if (element instanceof SyntaxTree.ArrayAccessExpr) {
                                // 数组访问需要特殊处理，确保我们获取的是正确的值
                                visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) element);
                            } else {
                                // 访问表达式获取值
                                visitExpr(element);
                            }
                            Value value = currentValue;
                            
                            // 类型转换
                            if (!value.getType().equals(elementType)) {
                                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            // 存储值
                            Value indexValue = new ConstantInt(linearIndex);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        }
                    }
                }
            }
        } else {
            // 处理单个值作为数组元素
            // 计算线性索引
            int linearIndex = 0;
            for (int i = 0; i < dims.size(); i++) {
                linearIndex += indices[i] * factors.get(i);
            }
            
            // 处理表达式
            if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                // 数组访问需要特殊处理，确保我们获取的是正确的值
                visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
            } else {
                // 访问表达式获取值
                visitExpr(expr);
            }
            Value value = currentValue;
            
            // 类型转换（如果需要）
            if (!value.getType().equals(elementType)) {
                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                    value = IRBuilder.createFloatToInt(value, currentBlock);
                } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                    value = IRBuilder.createIntToFloat(value, currentBlock);
                }
            }
            
            // 创建指针并存储值
            Value indexValue = new ConstantInt(linearIndex);
            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
            IRBuilder.createStore(value, elemPtr, currentBlock);
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
        
        // 处理常量条件的特殊情况
        if (condValue instanceof ConstantInt) {
            int condConstValue = ((ConstantInt) condValue).getValue();
            if (condConstValue != 0) {
                // 条件为真，只生成then分支
                IRBuilder.createBr(thenBlock, currentBlock);
                
                // 处理then分支
                currentBlock = thenBlock;
                visitStmt(stmt.thenBranch);
                
                // 如果当前块没有终结指令，添加跳转到合并块的指令
                if (currentBlock != null && !currentBlock.hasTerminator()) {
                    IRBuilder.createBr(mergeBlock, currentBlock);
                }
                
                // 添加一个从else块到合并块的直接跳转，确保控制流完整
                currentBlock = elseBlock;
                IRBuilder.createBr(mergeBlock, currentBlock);
                
                // 继续在合并块生成代码
                currentBlock = mergeBlock;
                return;
            } else {
                // 条件为假，只生成else分支
                IRBuilder.createBr(elseBlock, currentBlock);
                
                // 添加一个从then块到合并块的直接跳转，确保控制流完整
                currentBlock = thenBlock;
                IRBuilder.createBr(mergeBlock, currentBlock);
                
                // 处理else分支
                currentBlock = elseBlock;
                if (stmt.elseBranch != null) {
                    visitStmt(stmt.elseBranch);
                }
                
                // 如果当前块没有终结指令，添加跳转到合并块的指令
                if (currentBlock != null && !currentBlock.hasTerminator()) {
                    IRBuilder.createBr(mergeBlock, currentBlock);
                }
                
                // 继续在合并块生成代码
                currentBlock = mergeBlock;
                return;
            }
        }
        
        // 非常量条件的正常处理
        IRBuilder.createCondBr(condValue, thenBlock, elseBlock, currentBlock);
        
        // 处理then分支
        currentBlock = thenBlock;
        visitStmt(stmt.thenBranch);
        
        // 如果当前块没有终结指令，添加跳转到合并块的指令
        if (currentBlock != null && !currentBlock.hasTerminator()) {
            IRBuilder.createBr(mergeBlock, currentBlock);
        }
        
        // 处理else分支
        currentBlock = elseBlock;
        if (stmt.elseBranch != null) {
            visitStmt(stmt.elseBranch);
        }
        
        // 如果当前块没有终结指令，添加跳转到合并块的指令
        if (currentBlock != null && !currentBlock.hasTerminator()) {
            IRBuilder.createBr(mergeBlock, currentBlock);
        }
        
        // 继续在合并块生成代码
        currentBlock = mergeBlock;
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
        visitStmt(stmt.body);
        
        // 如果循环体没有终结，添加跳回条件块的指令
        if (currentBlock != null && !currentBlock.hasTerminator()) {
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
            currentValue = new ConstantFloat(((Float) value).doubleValue());
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
        
        // 全局初始化上下文中（即currentBlock为null），尝试获取常量值
        if (currentBlock == null && value instanceof GlobalVariable) {
            GlobalVariable globalVar = (GlobalVariable) value;
            if (globalVar.isConstant() && globalVar.hasInitializer()) {
                // 如果是常量并且已初始化，返回它的初始值而不是变量引用
                currentValue = globalVar.getInitializer();
                return;
            }
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
        // 数组访问表达式特殊处理
        if (expr instanceof SyntaxTree.ArrayAccessExpr) {
            // 特殊处理数组访问表达式，确保我们获取元素的值
            visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
            return;
        }
        
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

        // 通用计算索引逻辑（对所有维度通用）
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
                    // 对于布尔值(i1)，先扩展为i32
                    if (operand.getType() instanceof IntegerType) {
                        IntegerType intType = (IntegerType) operand.getType();
                        if (intType.getBitWidth() == 1) {
                            // 布尔值先扩展为i32
                            operand = IRBuilder.createZeroExtend(operand, IntegerType.I32, currentBlock);
                        }
                        Value zero = new ConstantInt(0);
                        currentValue = IRBuilder.createBinaryInst(OpCode.SUB, zero, operand, currentBlock);
                    }
                    // 对于浮点数，创建0.0-operand
                    else if (operand.getType() instanceof FloatType) {
                        Value zero = new ConstantFloat(0.0);
                        currentValue = IRBuilder.createBinaryInst(OpCode.FSUB, zero, operand, currentBlock);
                    }
                }
                break;
            case "!":
                // 逻辑非：将表达式与0比较，如果等于0则为1，否则为0
                if (operand.getType() instanceof IntegerType) {
                    Value zero = new ConstantInt(0);
                    // 先生成布尔值结果
                    Value boolResult = IRBuilder.createICmp(OpCode.EQ, operand, zero, currentBlock);
                    
                    // 在SysY语言中，逻辑运算的结果通常在比较中使用，需要扩展为整数
                    // 将i1类型扩展为i32类型
                    currentValue = IRBuilder.createZeroExtend(boolResult, IntegerType.I32, currentBlock);
                } else if (operand.getType() instanceof FloatType) {
                    // 将浮点数转为整数再比较
                    Value intValue = IRBuilder.createFloatToInt(operand, currentBlock);
                    Value zero = new ConstantInt(0);
                    Value boolResult = IRBuilder.createICmp(OpCode.EQ, intValue, zero, currentBlock);
                    
                    // 将i1类型扩展为i32类型
                    currentValue = IRBuilder.createZeroExtend(boolResult, IntegerType.I32, currentBlock);
                }
                break;
            default:
                throw new RuntimeException("不支持的一元操作符: " + expr.op);
        }
    }
    
    /**
     * 访问二元表达式（非递归迭代实现）
     */
    private void visitBinaryExpr(SyntaxTree.BinaryExpr expr) {
        String op = expr.op;
        
        // 特殊处理逻辑运算符，实现短路评估
        if (op.equals("&&")) {
            handleLogicalAndChain(expr);
            return;
        } else if (op.equals("||")) {
            handleLogicalOrChain(expr);
            return;
        }
        
        // 使用迭代算法处理二元表达式树
        // 定义表达式处理的状态: 0=未处理，1=已处理左操作数
        class ExprState {
            SyntaxTree.Expr expr;
            int state;
            Value leftValue; // 存储左操作数的值
            
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
                
                // 如果是逻辑运算符，应该已经在外部处理过了
                if (currentOp.equals("&&") || currentOp.equals("||")) {
                    throw new RuntimeException("逻辑运算符应该已经被单独处理");
                }
                
                if (current.state == 0) {
                    // 先处理左子表达式
                    current.state = 1;
                    stack.push(new ExprState(binaryExpr.left, 0));
                } else {
                    // 已处理左子表达式，现在处理右子表达式
                    stack.pop(); // 弹出当前表达式
                    
                    Value leftValue = current.leftValue != null ? current.leftValue : currentValue;
                    
                    // 处理右子表达式
                    visitExpr(binaryExpr.right);
                    Value rightValue = currentValue;
                    
                    // 根据操作符处理结果
                    processBinaryOperation(currentOp, leftValue, rightValue);
                    
                    // 如果栈不为空，将结果保存到上层表达式的leftValue中
                    if (!stack.isEmpty()) {
                        stack.peek().leftValue = currentValue;
                    }
                }
            } else {
                // 处理非二元表达式
                stack.pop();
                visitExpr(current.expr);
                
                // 如果栈不为空，将结果保存到上层表达式的leftValue中
                if (!stack.isEmpty()) {
                    stack.peek().leftValue = currentValue;
                }
            }
        }
    }
    
    /**
     * 处理二元运算操作
     */
    private void processBinaryOperation(String op, Value leftValue, Value rightValue) {
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
            
            // 创建比较指令，生成布尔值结果(i1类型)
            Value compResult = IRBuilder.createCompare(compareType, predicate, leftValue, rightValue, currentBlock);
            
            // 在SysY语言中，比较结果通常需要在后续表达式中使用，需要将i1类型提升为i32类型
            currentValue = IRBuilder.createZeroExtend(compResult, IntegerType.I32, currentBlock);
        } else {
            // 判断左右值是否有布尔值和整数混合的情况
            if (op.equals("==") || op.equals("!=")) {
                // 对于相等和不等比较，可能是布尔值和整数的比较
                boolean leftIsBool = leftValue.getType() instanceof IntegerType && 
                                    ((IntegerType) leftValue.getType()).getBitWidth() == 1;
                boolean rightIsBool = rightValue.getType() instanceof IntegerType && 
                                     ((IntegerType) rightValue.getType()).getBitWidth() == 1;
                
                // 如果一个是布尔值，另一个是整数，将整数转为布尔值
                if (leftIsBool && !rightIsBool && rightValue.getType() instanceof IntegerType) {
                    // 将右侧整数转为布尔值 (非零为真)
                    Value zero = new ConstantInt(0);
                    rightValue = IRBuilder.createICmp(OpCode.NE, rightValue, zero, currentBlock);
                } else if (rightIsBool && !leftIsBool && leftValue.getType() instanceof IntegerType) {
                    // 将左侧整数转为布尔值 (非零为真)
                    Value zero = new ConstantInt(0);
                    leftValue = IRBuilder.createICmp(OpCode.NE, leftValue, zero, currentBlock);
                }
            }
            
            // 确定操作码
            OpCode opCode = getOpCodeForBinaryOp(op);
            
            // 普通运算
            currentValue = IRBuilder.createBinaryInst(opCode, leftValue, rightValue, currentBlock);
        }
    }
    
    /**
     * 处理逻辑与表达式链，支持a&&b&&c这样的嵌套逻辑表达式
     */
    private void handleLogicalAndChain(SyntaxTree.BinaryExpr expr) {
        // 收集逻辑链中的所有表达式
        List<SyntaxTree.Expr> operands = collectLogicalChain(expr, "&&");
        
        // 检查是否所有操作数都是常量
        boolean allConstants = true;
        for (SyntaxTree.Expr operand : operands) {
            if (!isConstantExpr(operand)) {
                allConstants = false;
                break;
            }
        }
        
        // 如果全是常量，直接计算结果并创建控制流
        if (allConstants) {
            boolean result = true;
            for (SyntaxTree.Expr operand : operands) {
                if (!evaluateConstantBool(operand)) {
                    result = false;
                    break;
                }
            }
            
            // 创建常量结果
            currentValue = new ConstantInt(result ? 1 : 0, IntegerType.I1);
            
            // 对于常量表达式，我们仍然需要创建正确的控制流
            if (!result) {
                // 如果结果为假，我们需要确保控制流能够正确跳过if语句体
                // 这里不创建短路跳转，让后续代码使用这个常量值来决定控制流
            }
            
            return;
        }
        
        // 保存开始基本块
        BasicBlock startBlock = currentBlock;
        
        // 创建结束块，所有表达式结束后会跳转到这里
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        // 为每个操作数创建一个基本块（除了第一个操作数，它使用当前块）
        List<BasicBlock> evalBlocks = new ArrayList<>();
        for (int i = 1; i < operands.size(); i++) {
            evalBlocks.add(IRBuilder.createBasicBlock(currentFunction));
        }
        
        // 处理第一个操作数
        visitExpr(operands.get(0));
        Value firstResult = convertToBoolean(currentValue);
        
        // 从第一个操作数开始短路逻辑
        for (int i = 0; i < operands.size() - 1; i++) {
            Value currentResult = (i == 0) ? firstResult : currentValue;
            BasicBlock nextBlock = (i < evalBlocks.size()) ? evalBlocks.get(i) : mergeBlock;
            
            // 如果当前结果为假，短路到合并块，结果为假
            // 否则，继续求值下一个表达式
            IRBuilder.createCondBr(currentResult, nextBlock, mergeBlock, currentBlock);
            
            // 移动到下一个求值块
            if (i < evalBlocks.size()) {
                currentBlock = nextBlock;
                
                // 求值下一个操作数
                visitExpr(operands.get(i+1));
                Value boolValue = convertToBoolean(currentValue);
                // 保存转换后的布尔值，以便PHI节点使用
                currentValue = boolValue;
            }
        }
        
        // 最后一个表达式的结果是链的结果，添加跳转到合并块
        IRBuilder.createBr(mergeBlock, currentBlock);
        
        // 创建phi节点用于合并结果
        currentBlock = mergeBlock;
        PhiInstruction phi = IRBuilder.createPhi(IntegerType.I1, mergeBlock);
        
        // 处理短路情况（当任一条件为假时）
        for (int i = 0; i < operands.size() - 1; i++) {
            BasicBlock predBlock = (i == 0) ? startBlock : evalBlocks.get(i-1);
            phi.addIncoming(new ConstantInt(0, IntegerType.I1), predBlock);
        }
        
        // 最后一个表达式的结果
        if (!evalBlocks.isEmpty()) {
            BasicBlock lastEvalBlock = evalBlocks.get(evalBlocks.size() - 1);
            // 确保我们使用的是布尔值结果，而不是函数调用结果
            if (!(currentValue.getType() instanceof IntegerType) || 
                ((IntegerType)currentValue.getType()).getBitWidth() != 1) {
                // 如果当前值不是布尔类型，需要转换为布尔值
                Value zero = new ConstantInt(0);
                currentValue = IRBuilder.createICmp(OpCode.NE, currentValue, zero, currentBlock);
            }
            phi.addIncoming(currentValue, lastEvalBlock);
        }
        
        currentValue = phi;
        
        // 强制修复PHI节点的前驱关系
        forceFixPhiNodes(phi);
    }
    
    /**
     * 处理逻辑或表达式链，支持a||b||c这样的嵌套逻辑表达式
     */
    private void handleLogicalOrChain(SyntaxTree.BinaryExpr expr) {
        // 收集逻辑链中的所有表达式
        List<SyntaxTree.Expr> operands = collectLogicalChain(expr, "||");
        
        // 检查是否所有操作数都是常量
        boolean allConstants = true;
        for (SyntaxTree.Expr operand : operands) {
            if (!isConstantExpr(operand)) {
                allConstants = false;
                break;
            }
        }
        
        // 如果全是常量，直接计算结果并创建控制流
        if (allConstants) {
            boolean result = false;
            for (SyntaxTree.Expr operand : operands) {
                if (evaluateConstantBool(operand)) {
                    result = true;
                    break;
                }
            }
            
            // 创建常量结果
            currentValue = new ConstantInt(result ? 1 : 0, IntegerType.I1);
            
            // 对于常量表达式，我们仍然需要创建正确的控制流
            if (result) {
                // 如果结果为真，我们需要确保控制流能够正确进入if语句体
                // 这里不创建短路跳转，让后续代码使用这个常量值来决定控制流
            }
            
            return;
        }
        
        // 创建结束块，所有表达式结束后会跳转到这里
        BasicBlock mergeBlock = IRBuilder.createBasicBlock(currentFunction);
        
        // 为每个操作数（除了第一个）创建一个基本块
        List<BasicBlock> evalBlocks = new ArrayList<>();
        for (int i = 1; i < operands.size(); i++) {
            evalBlocks.add(IRBuilder.createBasicBlock(currentFunction));
        }
        
        // 保存起始块引用
        BasicBlock startBlock = currentBlock;
        
        // 处理第一个操作数
        visitExpr(operands.get(0));
        Value firstValue = convertToBoolean(currentValue);
        
        // 创建短路逻辑
        if (operands.size() > 1) {
            // 如果第一个操作数为真，短路到合并块，否则继续
            IRBuilder.createCondBr(firstValue, mergeBlock, evalBlocks.get(0), currentBlock);
            
            // 处理其余操作数
            for (int i = 1; i < operands.size(); i++) {
                currentBlock = evalBlocks.get(i-1);
                
                // 处理当前操作数
                visitExpr(operands.get(i));
                Value currValue = convertToBoolean(currentValue);
                
                // 保存转换后的布尔值，以便PHI节点使用
                currentValue = currValue;
                
                // 最后一个操作数直接跳转到合并块
                if (i == operands.size() - 1) {
                    IRBuilder.createBr(mergeBlock, currentBlock);
                } else {
                    // 否则检查短路
                    IRBuilder.createCondBr(currValue, mergeBlock, evalBlocks.get(i), currentBlock);
                }
            }
        } else {
            // 只有一个操作数，直接跳转到合并块
            IRBuilder.createBr(mergeBlock, currentBlock);
        }
        
        // 设置合并块
        currentBlock = mergeBlock;
        
        // 创建PHI节点合并结果
        PhiInstruction resultPhi = IRBuilder.createPhi(IntegerType.I1, mergeBlock);
        
        // 第一个操作数短路（为真）
        resultPhi.addIncoming(new ConstantInt(1, IntegerType.I1), startBlock);
        
        // 中间操作数短路（为真）
        for (int i = 0; i < operands.size() - 1 && i < evalBlocks.size(); i++) {
            resultPhi.addIncoming(new ConstantInt(1, IntegerType.I1), evalBlocks.get(i));
        }
        
        // 最后一个操作数的结果
        if (!evalBlocks.isEmpty()) {
            BasicBlock lastEvalBlock = evalBlocks.get(evalBlocks.size() - 1);
            resultPhi.addIncoming(currentValue, lastEvalBlock);
        }
        
        currentValue = resultPhi;
        
        // 强制修复PHI节点的前驱关系
        forceFixPhiNodes(resultPhi);
    }
    
    /**
     * 强制修复PHI节点的前驱关系
     */
    private void forceFixPhiNodes(PhiInstruction phi) {
        BasicBlock block = phi.getParent();
        if (block == null) return;
        
        // 确保PHI节点的前驱与基本块的前驱一致
        List<BasicBlock> blockPreds = block.getPredecessors();
        List<BasicBlock> phiPreds = new ArrayList<>(phi.getIncomingBlocks());
        
        // 添加缺失的前驱
        for (BasicBlock pred : blockPreds) {
            if (!phiPreds.contains(pred)) {
                // 添加默认值
                Value defaultValue = phi.getType().toString().equals("i1") ? 
                    new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                phi.addIncoming(defaultValue, pred);
            }
        }
        
        // 移除多余的前驱
        for (BasicBlock pred : new ArrayList<>(phiPreds)) {
            if (!blockPreds.contains(pred)) {
                phi.removeIncoming(pred);
            }
        }
    }
    
    /**
     * 判断表达式是否为常量
     */
    private boolean isConstantExpr(SyntaxTree.Expr expr) {
        if (expr instanceof SyntaxTree.LiteralExpr) {
            return true;
        }
        return false;
    }
    
    /**
     * 计算常量表达式的布尔值
     */
    private boolean evaluateConstantBool(SyntaxTree.Expr expr) {
        if (expr instanceof SyntaxTree.LiteralExpr) {
            Object value = ((SyntaxTree.LiteralExpr) expr).value;
            if (value instanceof Integer) {
                return ((Integer) value) != 0;
            } else if (value instanceof Float) {
                return ((Float) value) != 0.0f;
            }
        }
        
        // 默认为假
        return false;
    }
    
    /**
     * 收集嵌套的逻辑表达式链
     * 例如: a || b || c -> [a, b, c]
     */
    private List<SyntaxTree.Expr> collectLogicalChain(SyntaxTree.BinaryExpr expr, String operator) {
        List<SyntaxTree.Expr> operands = new ArrayList<>();
        collectOperandsHelper(expr, operator, operands);
        return operands;
    }
    
    /**
     * 递归收集逻辑表达式链的辅助方法
     */
    private void collectOperandsHelper(SyntaxTree.Expr expr, String operator, List<SyntaxTree.Expr> operands) {
        if (expr instanceof SyntaxTree.BinaryExpr && ((SyntaxTree.BinaryExpr) expr).op.equals(operator)) {
            SyntaxTree.BinaryExpr binExpr = (SyntaxTree.BinaryExpr) expr;
            collectOperandsHelper(binExpr.left, operator, operands);
            collectOperandsHelper(binExpr.right, operator, operands);
        } else {
            operands.add(expr);
        }
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
        
        // 特殊处理：starttime和stoptime函数需要添加行号参数
        // 如果调用的是C函数名，实际上是调用_sysy_前缀的函数
        if (funcName.equals("starttime") || funcName.equals("stoptime")) {
            // 不需要额外检查参数数量，直接添加行号参数
            // 默认使用行号0
            args.add(new ConstantInt(0));
            
            // 如果调用的是starttime，创建对_sysy_starttime的调用
            if (funcName.equals("starttime")) {
                // 创建函数调用指令
                currentValue = IRBuilder.createCall((Function)findVariable("_sysy_starttime"), args, currentBlock);
                return;
            } 
            // 如果调用的是stoptime，创建对_sysy_stoptime的调用
            else { 
                // 创建函数调用指令
                currentValue = IRBuilder.createCall((Function)findVariable("_sysy_stoptime"), args, currentBlock);
                return;
            }
        }
        
        // 获取函数参数类型列表
        List<Argument> funcArgs = function.getArguments();
        if (expr.args.size() != funcArgs.size()) {
            throw new RuntimeException("函数 " + funcName + " 参数数量不匹配: 期望 " + 
                                      funcArgs.size() + ", 实际 " + expr.args.size());
        }
        
        // 处理参数
        for (int i = 0; i < expr.args.size(); i++) {
            SyntaxTree.Expr argExpr = expr.args.get(i);
            Type paramType = funcArgs.get(i).getType();
            
            // 特别处理数组参数 - 如果参数是数组且函数期望指针类型
            if (paramType instanceof PointerType) {
                // 对于变量引用，检查是否是数组
                if (argExpr instanceof SyntaxTree.VarExpr) {
                    String arrayName = ((SyntaxTree.VarExpr) argExpr).name;
                    Value arrayVar = findVariable(arrayName);
                    
                    // 检查是否是数组
                    if (findArrayDimensions(arrayName) != null) {
                        // 直接使用数组指针，不加载值
                        args.add(arrayVar);
                        continue;
                    }
                }
                // 对于数组元素访问，需要传递指针而非值
                else if (argExpr instanceof SyntaxTree.ArrayAccessExpr) {
                    // 获取数组元素指针而非加载值
                    visitArrayAccessExpr((SyntaxTree.ArrayAccessExpr) argExpr);
                    Value elemPtr = currentValue;
                    
                    // 确保获取的是指针
                    if (elemPtr.getType() instanceof PointerType) {
                        args.add(elemPtr);
                        continue;
                    }
                }
            }
            
            // 常规参数处理（非指针类型或非数组访问）
            visitExpr(argExpr);
            Value arg = currentValue;
            
            // 如果类型不匹配，尝试进行类型转换
            if (!arg.getType().equals(paramType)) {
                if (arg.getType() instanceof IntegerType && paramType instanceof FloatType) {
                    // 整数转浮点
                    arg = IRBuilder.createIntToFloat(arg, currentBlock);
                } else if (arg.getType() instanceof FloatType && paramType instanceof IntegerType) {
                    // 浮点转整数
                    arg = IRBuilder.createFloatToInt(arg, currentBlock);
                }
            }
            
            args.add(arg);
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
        
        // 计算每个维度的因子（通用逻辑，适用于任何维度）
        List<Integer> factors = new ArrayList<>();
        for (int i = 0; i < dimensions.size(); i++) {
            int factor = 1;
            for (int j = i + 1; j < dimensions.size(); j++) {
                factor *= dimensions.get(j);
            }
            factors.add(factor);
        }
        
        // 计算线性偏移量（通用逻辑，适用于任何维度）
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
        
        // 检查是否可以使用memset优化（仅用于整型数组）
        boolean useMemset = (elementType == IntegerType.I32 || elementType == IntegerType.I1 || elementType == IntegerType.I8);
        int elementSizeInBytes = elementType.getSize();
        int totalSizeInBytes = totalSize * elementSizeInBytes;
        
        // 初始化数组元素
        if (initExpr != null) {
            // 检查是否全部为0，如果是则使用memset优化（仅用于整型数组）
            if (useMemset && isLocalArrayAllZero(initExpr)) {
                // 使用memset将数组初始化为0
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
            
            // 如果数组初始化中包含特殊元素如a[3][0]，我们需要先处理这些表达式
            // 先简单检查是否存在数组访问表达式
            boolean hasArrayAccess = false;
            for (SyntaxTree.Expr expr : initExpr.elements) {
                if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                    hasArrayAccess = true;
                    break;
                }
            }
            
            // 如果包含数组访问表达式，需要特殊处理
            if (hasArrayAccess) {
                // 先初始化整个数组为0
                if (useMemset) {
                    // 对于整型数组，使用memset
                    Function memsetFunc = module.getLibFunction("memset");
                    if (memsetFunc != null) {
                        List<Value> args = new ArrayList<>();
                        args.add(arrayPtr);
                        args.add(new ConstantInt(0));
                        args.add(new ConstantInt(totalSizeInBytes));
                        IRBuilder.createCall(memsetFunc, args, currentBlock);
                    } else {
                        // 如果找不到memset函数，手动将数组初始化为0
                        for (int i = 0; i < totalSize; i++) {
                            Value indexValue = new ConstantInt(i);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                            IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                        }
                    }
                } else {
                    // 对于浮点数组，使用循环逐个初始化
                    for (int i = 0; i < totalSize; i++) {
                        Value indexValue = new ConstantInt(i);
                        Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                        IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
                    }
                }
                
                // 直接处理一维平铺的数组初始化
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
                    
                    // 计算当前元素在数组中的位置
                    int[] indices = new int[dimensions.size()];
                    int remaining = currentPos;
                    for (int i = 0; i < dimensions.size(); i++) {
                        indices[i] = remaining / factors.get(i);
                        remaining %= factors.get(i);
                    }
                    
                    // 计算线性索引
                    int linearIndex = 0;
                    for (int i = 0; i < dimensions.size(); i++) {
                        linearIndex += indices[i] * factors.get(i);
                    }
                    
                    // 访问表达式获取值
                    if (expr instanceof SyntaxTree.ArrayAccessExpr) {
                        visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) expr);
                    } else if (expr instanceof SyntaxTree.ArrayInitExpr) {
                        // 对于嵌套数组初始化，跳过当前级别，继续处理下一级别
                        SyntaxTree.ArrayInitExpr subInit = (SyntaxTree.ArrayInitExpr) expr;
                        
                        // 计算当前维度的大小
                        int currentDimSize = dimensions.get(0);
                        for (int i = 0; i < Math.min(subInit.elements.size(), dimensions.get(1)); i++) {
                            if (currentPos + i >= totalSize) break;
                            
                            SyntaxTree.Expr subExpr = subInit.elements.get(i);
                            
                            // 访问表达式获取值
                            if (subExpr instanceof SyntaxTree.ArrayAccessExpr) {
                                visitArrayAccessExprAndLoad((SyntaxTree.ArrayAccessExpr) subExpr);
                            } else {
                                visitExpr(subExpr);
                            }
                            Value value = currentValue;
                            
                            // 类型转换
                            if (!value.getType().equals(elementType)) {
                                if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                                    value = IRBuilder.createFloatToInt(value, currentBlock);
                                } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                                    value = IRBuilder.createIntToFloat(value, currentBlock);
                                }
                            }
                            
                            // 存储到数组元素位置
                            Value storeIndex = new ConstantInt(linearIndex + i);
                            Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, storeIndex, currentBlock);
                            IRBuilder.createStore(value, elemPtr, currentBlock);
                        }
                        
                        currentPos += dimensions.get(1); // 跳过一整个子数组
                        continue;
                    } else {
                        visitExpr(expr);
                    }
                    Value value = currentValue;
                    
                    // 类型转换
                    if (!value.getType().equals(elementType)) {
                        if (elementType == IntegerType.I32 && value.getType() instanceof FloatType) {
                            value = IRBuilder.createFloatToInt(value, currentBlock);
                        } else if (elementType == FloatType.F64 && value.getType() instanceof IntegerType) {
                            value = IRBuilder.createIntToFloat(value, currentBlock);
                        }
                    }
                    
                    // 存储到数组元素位置
                    Value indexValue = new ConstantInt(linearIndex);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(value, elemPtr, currentBlock);
                    
                    currentPos++;
                }
                
                // 检查是否还有剩余的元素需要初始化为0
                if (currentPos < totalSize) {
                    // 计算剩余部分的大小和字节数
                    int remainingElements = totalSize - currentPos;
                    int remainingBytes = remainingElements * elementSizeInBytes;
                    
                    // 计算剩余部分在数组中的位置
                    Value ptrOffset = IRBuilder.createGetElementPtr(arrayPtr, new ConstantInt(currentPos), currentBlock);
                    
                    // 对于整型数组且剩余元素较多时使用memset初始化剩余的元素为0
                    if (useMemset && remainingElements > 32) {
                        Function remainingMemsetFunc = module.getLibFunction("memset");
                        if (remainingMemsetFunc != null) {
                            List<Value> args = new ArrayList<>();
                            args.add(ptrOffset);
                            args.add(new ConstantInt(0));
                            args.add(new ConstantInt(remainingBytes));
                            IRBuilder.createCall(remainingMemsetFunc, args, currentBlock);
                        } else {
                            // 如果找不到memset，手动初始化剩余元素
                            for (int i = 0; i < remainingElements; i++) {
                                Value indexValue = new ConstantInt(i);
                                Value elemPtr = IRBuilder.createGetElementPtr(ptrOffset, indexValue, currentBlock);
                                IRBuilder.createStore(new ConstantInt(0), elemPtr, currentBlock);
                            }
                        }
                    } else {
                        // 对于浮点数组或剩余元素较少，使用循环逐个初始化
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
                // 正常处理数组初始化
                processArrayInit(initExpr, arrayPtr, dimensions, elementType);
            }
        } else {
            // 如果没有初始化表达式，使用适当的方法初始化为0
            if (useMemset) {
                // 对于整型数组，使用memset
                Function noInitMemsetFunc = module.getLibFunction("memset");
                if (noInitMemsetFunc != null) {
                    List<Value> args = new ArrayList<>();
                    args.add(arrayPtr);
                    args.add(new ConstantInt(0));
                    args.add(new ConstantInt(totalSizeInBytes));
                    IRBuilder.createCall(noInitMemsetFunc, args, currentBlock);
                }
                // 如果找不到memset，则不进行任何初始化，与C语言行为一致
            } else {
                // 对于浮点数组，使用循环逐个初始化
                for (int i = 0; i < totalSize; i++) {
                    Value indexValue = new ConstantInt(i);
                    Value elemPtr = IRBuilder.createGetElementPtr(arrayPtr, indexValue, currentBlock);
                    IRBuilder.createStore(new ConstantFloat(0.0), elemPtr, currentBlock);
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
    
    /**
     * 检查局部数组初始化是否全为0
     * @param initExpr 数组初始化表达式
     * @return 如果数组初始化全为0，返回true，否则返回false
     */
    private boolean isLocalArrayAllZero(SyntaxTree.ArrayInitExpr initExpr) {
        if (initExpr == null) {
            return true; // 没有初始化表达式，默认全为0
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
                return false; // 非字面量或数组初始化表达式，无法在编译时确定是否为0
            }
        }
        
        return true;
    }
    
    /**
     * 判断数组初始化表达式从指定位置开始是否全为0
     * @param initExpr 数组初始化表达式
     * @param startPos 开始位置
     * @return 如果从指定位置开始全为0，返回true，否则返回false
     */
    private boolean isRemainingArrayZero(SyntaxTree.ArrayInitExpr initExpr, int startPos) {
        if (initExpr == null || startPos >= initExpr.elements.size()) {
            return true;
        }
        
        for (int i = startPos; i < initExpr.elements.size(); i++) {
            SyntaxTree.Expr expr = initExpr.elements.get(i);
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

} 
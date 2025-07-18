package IR;

import IR.Module;
import IR.OpCode;
import IR.Type.*;
import IR.Value.*;
import IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * IR构建工厂类，提供创建各种IR元素的静态方法
 */
public class IRBuilder {
    private static int tmpCounter = 0;  // 用于生成临时变量名
    private static int blockCounter = 0; // 用于生成基本块名
    
    /**
     * 创建一个新的IR模块
     */
    public static Module createModule(String name) {
        return new Module(name);
    }
    
    /**
     * 创建一个函数
     */
    public static Function createFunction(String name, Type returnType, Module module) {
        Function function = new Function(name, returnType);
        module.addFunction(function);
        return function;
    }
    
    /**
     * 创建一个外部函数（库函数）
     */
    public static Function createExternalFunction(String name, Type returnType, Module module) {
        Function function = new Function(name, returnType);
        function.setExternal(true);
        module.addLibFunction(function);
        return function;
    }
    
    /**
     * 创建一个函数参数
     */
    public static Argument createArgument(String name, Type type, Function function, int index) {
        Argument arg = new Argument(name, type, function, index);
        function.addArgument(arg);
        return arg;
    }
    
    /**
     * 创建一个全局变量
     */
    public static GlobalVariable createGlobalVariable(String name, Type type, Module module) {
        GlobalVariable var = new GlobalVariable(name, type, false);
        module.addGlobalVariable(var);
        return var;
    }
    
    /**
     * 创建一个全局常量
     */
    public static GlobalVariable createGlobalConstant(String name, Type type, Value initializer, Module module) {
        GlobalVariable var = new GlobalVariable(name, type, true);
        var.setInitializer(initializer);
        module.addGlobalVariable(var);
        return var;
    }
    
    /**
     * 创建一个全局数组
     */
    public static GlobalVariable createGlobalArray(String name, Type elementType, int size, Module module) {
        GlobalVariable var = new GlobalVariable(name, elementType, size, false);
        module.addGlobalVariable(var);
        return var;
    }
    
    /**
     * 创建一个基本块
     */
    public static BasicBlock createBasicBlock(Function function) {
        return new BasicBlock("block" + blockCounter++, function);
    }
    
    /**
     * 创建一个带名称的基本块
     */
    public static BasicBlock createBasicBlock(String name, Function function) {
        return new BasicBlock(name, function);
    }
    
    /**
     * 创建一个整数常量
     */
    public static ConstantInt createConstantInt(int value) {
        return new ConstantInt(value);
    }
    
    /**
     * 创建一个浮点常量
     */
    public static ConstantFloat createConstantFloat(float value) {
        return new ConstantFloat(value);
    }
    
    /**
     * 创建一个加载指令
     */
    public static LoadInstruction createLoad(Value pointer, BasicBlock block) {
        // 检查指针类型
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("加载指令必须从指针类型加载");
        }
        
        // 获取指针元素类型
        Type pointedType = ((PointerType) pointer.getType()).getElementType();
        String name = "load_" + tmpCounter++;
        
        // 创建加载指令
        LoadInstruction inst = new LoadInstruction(pointer, pointedType, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个分配指令
     */
    public static AllocaInstruction createAlloca(Type type, BasicBlock block) {
        // 为常用类型创建缓存的指针类型
        PointerType pointerType = null;
        if (type.equals(IntegerType.I32)) {
            pointerType = new PointerType(IntegerType.I32);
        } else if (type.equals(FloatType.F32)) {
            pointerType = new PointerType(FloatType.F32);
        }
        
        String name = "alloca_" + tmpCounter++;
        AllocaInstruction inst = new AllocaInstruction(type, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个数组分配指令
     */
    public static AllocaInstruction createArrayAlloca(Type elementType, int size, BasicBlock block) {
        String name = "array_alloca_" + tmpCounter++;
        AllocaInstruction inst = new AllocaInstruction(elementType, size, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个存储指令
     */
    public static StoreInstruction createStore(Value value, Value pointer, BasicBlock block) {
        // 确保指针是指针类型
        if (pointer.getType() instanceof PointerType) {
            Type elementType = ((PointerType) pointer.getType()).getElementType();
            Type valueType = value.getType();
            
            // 只处理常见的整数和浮点类型转换
            if (elementType instanceof FloatType && valueType instanceof IntegerType) {
                // 将整数值转换为浮点值
                if (value instanceof ConstantInt constInt) {
                    // 常量整数转常量浮点
                    value = createConstantFloat((float) constInt.getValue());
                } else {
                    // 非常量整数转浮点
                    value = createIntToFloat(value, block);
                }
            }
            else if (elementType instanceof IntegerType && valueType instanceof FloatType) {
                // 将浮点值转换为整数值
                if (value instanceof ConstantFloat constFloat) {
                    // 常量浮点转常量整数
                    value = createConstantInt((int) constFloat.getValue());
                } else {
                    // 非常量浮点转整数
                    value = createFloatToInt(value, block);
                }
            }
        }
        
        // 创建存储指令
        StoreInstruction inst = new StoreInstruction(value, pointer);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个二元运算指令，但不添加到基本块
     */
    public static BinaryInstruction createBinaryInstOnly(OpCode opCode, Value left, Value right) {
        String name = opCode.getName().toLowerCase() + "_result_" + tmpCounter++;
        
        // 确保操作码与操作数类型匹配
        boolean isFloatOp = opCode == OpCode.FADD || opCode == OpCode.FSUB || 
                          opCode == OpCode.FMUL || opCode == OpCode.FDIV || opCode == OpCode.FREM;
        boolean isIntOp = opCode == OpCode.ADD || opCode == OpCode.SUB || 
                        opCode == OpCode.MUL || opCode == OpCode.DIV || opCode == OpCode.REM;
        
        // 如果操作数是整数但使用了浮点操作码，修正操作码
        if (isFloatOp && left.getType() instanceof IntegerType && right.getType() instanceof IntegerType) {
            switch (opCode) {
                case FADD: opCode = OpCode.ADD; break;
                case FSUB: opCode = OpCode.SUB; break;
                case FMUL: opCode = OpCode.MUL; break;
                case FDIV: opCode = OpCode.DIV; break;
                case FREM: opCode = OpCode.REM; break;
            }
        }
        
        // 如果操作数是浮点数但使用了整数操作码，修正操作码
        if (isIntOp && left.getType() instanceof FloatType && right.getType() instanceof FloatType) {
            switch (opCode) {
                case ADD: opCode = OpCode.FADD; break;
                case SUB: opCode = OpCode.FSUB; break;
                case MUL: opCode = OpCode.FMUL; break;
                case DIV: opCode = OpCode.FDIV; break;
                case REM: opCode = OpCode.FREM; break;
            }
        }
        
        return new BinaryInstruction(opCode, left, right, left.getType());
    }
    
    /**
     * 创建一个二元运算指令
     */
    public static BinaryInstruction createBinaryInst(OpCode opCode, Value left, Value right, BasicBlock block) {
        Type type;
        Type leftType = left.getType();
        Type rightType = right.getType();

        // 如果操作数类型不同，进行类型转换
        if (!leftType.equals(rightType)) {
            // 默认使用整数类型
            type = IntegerType.I32;

            // 处理浮点数和整数的混合运算
            if (leftType instanceof FloatType && rightType instanceof IntegerType) {
                // 将整数转为浮点数
                right = createIntToFloat(right, block);
                type = FloatType.F32;
            } 
            else if (rightType instanceof FloatType && leftType instanceof IntegerType) {
                // 将整数转为浮点数
                left = createIntToFloat(left, block);
                type = FloatType.F32;
            }
        } else {
            type = leftType;
        }

        // 确保类型不为null
        if (type == null) {
            type = IntegerType.I32;
        }

        // 根据类型调整操作码
        if (type instanceof FloatType) {
            // 将整数操作转换为浮点操作
            switch (opCode) {
                case ADD: opCode = OpCode.FADD; break;
                case SUB: opCode = OpCode.FSUB; break;
                case MUL: opCode = OpCode.FMUL; break;
                case DIV: opCode = OpCode.FDIV; break;
                case REM: opCode = OpCode.FREM; break;
            }
        }

        // 比较操作的结果总是布尔值（i1类型）
        if (opCode.isCompare()) {
            type = IntegerType.I1;
        }

        BinaryInstruction inst = new BinaryInstruction(opCode, left, right, type);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个比较指令
     */
    public static CompareInstruction createCompare(OpCode compareType, OpCode predicate, Value left, Value right, BasicBlock block) {
        // 处理类型不匹配的情况
        Type leftType = left.getType();
        Type rightType = right.getType();
        
        // 如果类型不匹配，进行必要的转换
        if (!leftType.equals(rightType)) {
            // 处理浮点数和整数的混合比较
            if (leftType instanceof FloatType && rightType instanceof IntegerType) {
                // 将整数转为浮点数
                right = createIntToFloat(right, block);
                // 确保使用浮点比较
                compareType = OpCode.FCMP;
            } 
            else if (rightType instanceof FloatType && leftType instanceof IntegerType) {
                // 将整数转为浮点数
                left = createIntToFloat(left, block);
                // 确保使用浮点比较
                compareType = OpCode.FCMP;
            }
        } else {
            // 确保根据操作数类型选择正确的比较类型
            if (leftType instanceof FloatType) {
                compareType = OpCode.FCMP;
            } else {
                compareType = OpCode.ICMP;
            }
        }

        CompareInstruction inst = new CompareInstruction(compareType, predicate, left, right);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个整数比较指令
     */
    public static CompareInstruction createICmp(OpCode predicate, Value left, Value right, BasicBlock block) {
        return createCompare(OpCode.ICMP, predicate, left, right, block);
    }
    
    /**
     * 创建一个浮点比较指令
     */
    public static CompareInstruction createFCmp(OpCode predicate, Value left, Value right, BasicBlock block) {
        return createCompare(OpCode.FCMP, predicate, left, right, block);
    }
    
    /**
     * 创建一个返回指令
     */
    public static ReturnInstruction createReturn(BasicBlock block) {
        ReturnInstruction inst = new ReturnInstruction();
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个带返回值的返回指令
     */
    public static ReturnInstruction createReturn(Value value, BasicBlock block) {
        ReturnInstruction inst = new ReturnInstruction(value);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个无条件分支指令
     */
    public static BranchInstruction createBr(BasicBlock target, BasicBlock block) {
        BranchInstruction inst = new BranchInstruction(target);
        block.addInstruction(inst);
        
        // 更新基本块的后继关系
        block.addSuccessor(target);
        
        // 更新目标块的前驱关系
        target.addPredecessor(block);
        
        return inst;
    }
    
    /**
     * 创建一个条件分支指令
     */
    public static BranchInstruction createCondBr(Value condition, BasicBlock trueBlock, BasicBlock falseBlock, BasicBlock block) {
        // 确保条件是布尔类型(i1)
        if (condition.getType() != IntegerType.I1) {
            // 如果条件不是布尔类型，创建比较指令转换为布尔类型
            // 将非零值视为true，零值视为false
            condition = createICmp(OpCode.NE, condition, createConstantInt(0), block);
        }
        
        BranchInstruction inst = new BranchInstruction(condition, trueBlock, falseBlock);
        block.addInstruction(inst);
        
        // 更新基本块的后继关系
        block.addSuccessor(trueBlock);
        block.addSuccessor(falseBlock);
        
        // 更新基本块的前驱关系
        trueBlock.addPredecessor(block);
        falseBlock.addPredecessor(block);
        
        return inst;
    }
    
    /**
     * 创建一个函数调用指令
     */
    public static CallInstruction createCall(Function callee, List<Value> arguments, BasicBlock block) {
        CallInstruction inst;
        
        // 检查函数返回类型是否为void
        if (callee.getReturnType() instanceof VoidType) {
            // void返回类型的函数调用不需要名称
            inst = new CallInstruction(callee, arguments, null);
        } else {
            // 非void返回类型的函数调用需要名称
            String name = "call_" + tmpCounter++;
            inst = new CallInstruction(callee, arguments, name);
        }
        
        if (block != null) {
            block.addInstruction(inst);
            
            // 建立调用关系
            if (!callee.isExternal()) {
                Function caller = block.getParentFunction();
                // 避免重复添加
                if (!caller.getCallees().contains(callee)) {
                    caller.getCallees().add(callee);
                }
                if (!callee.getCallers().contains(caller)) {
                    callee.getCallers().add(caller);
                }
            }
        }
        
        return inst;
    }
    
    /**
     * 创建一个Phi指令
     */
    public static PhiInstruction createPhi(Type type, BasicBlock block) {
        String name = "phi_" + tmpCounter++;
        PhiInstruction inst = new PhiInstruction(type, name);
        if (block != null) {
            block.addInstructionFirst(inst);
            
            // 确保PHI节点与基本块前驱匹配
            List<BasicBlock> predecessors = block.getPredecessors();
            if (!predecessors.isEmpty()) {
                // 更新PHI节点前驱信息
                updatePhiNodePredecessors(inst, block);
            }
        }
        return inst;
    }
    
    /**
     * 验证函数中所有PHI节点的前驱关系
     * 确保它们与实际基本块的前驱匹配
     */
    public static void validateAllPhiNodes(Function function) {
        if (function == null) return;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : new ArrayList<>(block.getInstructions())) {
                if (inst instanceof PhiInstruction phi) {
                    phi.validatePredecessors();
                }
            }
        }
        
        // 在验证后，强制修复所有仍然有问题的PHI节点
        forceFixPhiNodes(function);
    }
    
    /**
     * 强制修复PHI节点，删除所有不对应实际前驱的输入项
     */
    public static void forceFixPhiNodes(Function function) {
        if (function == null) return;
        
        // 分析所有的跳转指令，构建准确的前驱图
        Map<BasicBlock, List<BasicBlock>> actualPredecessors = new HashMap<>();
        
        // 初始化前驱图
        for (BasicBlock block : function.getBasicBlocks()) {
            actualPredecessors.put(block, new ArrayList<>());
        }
        
        // 从终结指令分析实际的控制流
        for (BasicBlock block : function.getBasicBlocks()) {
            Instruction terminator = block.getTerminator();
            if (terminator instanceof TerminatorInstruction) {
                BasicBlock[] successors = ((TerminatorInstruction) terminator).getSuccessors();
                for (BasicBlock successor : successors) {
                    if (successor != null && !actualPredecessors.get(successor).contains(block)) {
                        actualPredecessors.get(successor).add(block);
                    }
                }
            }
        }
        
        // 修复所有PHI节点
        for (BasicBlock block : function.getBasicBlocks()) {
            List<BasicBlock> realPreds = actualPredecessors.get(block);
            
            for (Instruction inst : new ArrayList<>(block.getInstructions())) {
                if (inst instanceof PhiInstruction phi) {
                    // 创建只包含实际前驱的新输入映射
                    Map<BasicBlock, Value> validIncomings = new HashMap<>();
                    
                    // 保留所有有效的前驱输入
                    for (BasicBlock pred : realPreds) {
                        if (phi.getIncomingValues().containsKey(pred)) {
                            validIncomings.put(pred, phi.getIncomingValues().get(pred));
                        } else {
                            // 为缺失的前驱添加默认值
                            Value defaultValue = phi.getType().toString().equals("i1") ?
                                new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                            validIncomings.put(pred, defaultValue);
                        }
                    }
                    
                    // 重建PHI节点的输入
                    phi.removeAllOperands();
                    phi.getIncomingValues().clear();
                    for (Map.Entry<BasicBlock, Value> entry : validIncomings.entrySet()) {
                        phi.addIncoming(entry.getValue(), entry.getKey());
                    }
                }
            }
        }
    }
    
    /**
     * 更新PHI节点的前驱信息
     * 确保PHI节点的输入与基本块的前驱匹配
     */
    public static void updatePhiNodePredecessors(PhiInstruction phi, BasicBlock block) {
        List<BasicBlock> predecessors = block.getPredecessors();
        
        // 只有当PHI节点为空（新创建）时才添加默认值
        if (phi.getIncomingBlocks().isEmpty() && !predecessors.isEmpty()) {
            // 为每个前驱块添加一个默认值（常量0）
            for (BasicBlock pred : predecessors) {
                Value defaultValue = phi.getType() == IntegerType.I1 ? 
                    new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                phi.addIncoming(defaultValue, pred);
            }
        }
    }
    
    /**
     * 更新基本块内所有PHI节点的前驱关系
     */
    public static void updateAllPhiNodes(BasicBlock block) {
        if (block == null) return;
        
        for (Instruction inst : block.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                updatePhiNodePredecessors(phi, block);
            }
        }
    }
    
    /**
     * 创建一个简单的GetElementPtr指令（类似示例的PtrInst）
     */
    public static GetElementPtrInstruction createGetElementPtr(Value pointer, Value index, BasicBlock block) {
        // 检查指针类型
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("GetElementPtr的基地址必须是指针类型");
        }
        
        String name = "gep_" + tmpCounter++;
        GetElementPtrInstruction inst = new GetElementPtrInstruction(pointer, index, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个多维索引的GetElementPtr指令
     */
    public static GetElementPtrInstruction createGetElementPtr(Value pointer, List<Value> indices, BasicBlock block) {
        // 检查指针类型
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("GetElementPtr的基地址必须是指针类型");
        }
        
        String name = "gep_" + tmpCounter++;
        GetElementPtrInstruction inst = new GetElementPtrInstruction(pointer, indices, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个类型转换指令
     */
    public static ConversionInstruction createConversion(Value value, Type targetType, OpCode conversionType, BasicBlock block) {
        String name = conversionType.getName().toLowerCase() + "_" + tmpCounter++;
        ConversionInstruction inst = new ConversionInstruction(value, targetType, conversionType, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    /**
     * 创建一个整数到浮点数的转换指令
     */
    public static ConversionInstruction createIntToFloat(Value value, BasicBlock block) {
        return createConversion(value, FloatType.F32, OpCode.SITOFP, block);
    }
    
    /**
     * 创建一个浮点数到整数的转换指令
     */
    public static ConversionInstruction createFloatToInt(Value value, BasicBlock block) {
        return createConversion(value, IntegerType.I32, OpCode.FPTOSI, block);
    }
    
    /**
     * 创建一个零扩展指令（从较小整数类型扩展到较大整数类型）
     */
    public static ConversionInstruction createZeroExtend(Value value, Type targetType, BasicBlock block) {
        return createConversion(value, targetType, OpCode.ZEXT, block);
    }
    
    /**
     * 创建一个位截断指令（从较大整数类型截断到较小整数类型）
     */
    public static ConversionInstruction createTrunc(Value value, Type targetType, BasicBlock block) {
        return createConversion(value, targetType, OpCode.TRUNC, block);
    }
    
    /**
     * 创建一个位类型转换指令（用于指针类型之间的转换）
     */
    public static ConversionInstruction createBitCast(Value value, Type targetType, BasicBlock block) {
        return createConversion(value, targetType, OpCode.BITCAST, block);
    }
    
    /**
     * 创建一个操作数列表
     */
    public static List<Value> createOperandList(Value... operands) {
        List<Value> list = new ArrayList<>();
        for (Value op : operands) {
            list.add(op);
        }
        return list;
    }
    
    /**
     * 计算常量表达式的结果
     */
    public static Value calculateConstantExpr(Value left, Value right, OpCode op) {
        // 整数常量计算
        if (left instanceof ConstantInt leftInt && right instanceof ConstantInt rightInt) {
            int leftVal = leftInt.getValue();
            int rightVal = rightInt.getValue();
            
            return switch (op) {
                case ADD -> createConstantInt(leftVal + rightVal);
                case SUB -> createConstantInt(leftVal - rightVal);
                case MUL -> createConstantInt(leftVal * rightVal);
                case DIV -> createConstantInt(leftVal / rightVal);
                case REM -> createConstantInt(leftVal % rightVal);
                case SHL -> createConstantInt(leftVal << rightVal);
                case LSHR -> createConstantInt(leftVal >>> rightVal);
                case ASHR -> createConstantInt(leftVal >> rightVal);
                case AND -> createConstantInt(leftVal & rightVal);
                case OR -> createConstantInt(leftVal | rightVal);
                case XOR -> createConstantInt(leftVal ^ rightVal);
                default -> null;
            };
        }
        
        // 浮点常量计算
        if (left instanceof ConstantFloat leftFloat && right instanceof ConstantFloat rightFloat) {
            float leftVal = leftFloat.getValue();
            float rightVal = rightFloat.getValue();
            
            return switch (op) {
                case FADD -> createConstantFloat(leftVal + rightVal);
                case FSUB -> createConstantFloat(leftVal - rightVal);
                case FMUL -> createConstantFloat(leftVal * rightVal);
                case FDIV -> createConstantFloat(leftVal / rightVal);
                case FREM -> createConstantFloat(leftVal % rightVal);
                default -> null;
            };
        }
        
        // 混合类型计算（整数和浮点）
        if (left instanceof ConstantInt leftInt && right instanceof ConstantFloat rightFloat) {
            float leftVal = leftInt.getValue();
            float rightVal = rightFloat.getValue();
            
            return switch (op) {
                case FADD -> createConstantFloat(leftVal + rightVal);
                case FSUB -> createConstantFloat(leftVal - rightVal);
                case FMUL -> createConstantFloat(leftVal * rightVal);
                case FDIV -> createConstantFloat(leftVal / rightVal);
                case FREM -> createConstantFloat(leftVal % rightVal);
                default -> null;
            };
        }
        
        if (left instanceof ConstantFloat leftFloat && right instanceof ConstantInt rightInt) {
            float leftVal = leftFloat.getValue();
            float rightVal = rightInt.getValue();
            
            return switch (op) {
                case FADD -> createConstantFloat(leftVal + rightVal);
                case FSUB -> createConstantFloat(leftVal - rightVal);
                case FMUL -> createConstantFloat(leftVal * rightVal);
                case FDIV -> createConstantFloat(leftVal / rightVal);
                case FREM -> createConstantFloat(leftVal % rightVal);
                default -> null;
            };
        }
        
        return null; // 无法计算
    }

    /**
     * 重置基本块计数器
     */
    public static void resetBlockCounter() {
        blockCounter = 0;
    }
} 
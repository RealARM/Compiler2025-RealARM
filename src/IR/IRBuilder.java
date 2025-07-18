package IR;

import IR.Type.*;
import IR.Value.*;
import IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;

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
        module.addExternalFunction(function);
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
     * 创建一个分配指令
     */
    public static AllocaInstruction createAlloca(Type type, BasicBlock block) {
        String name = "alloca_" + tmpCounter++;
        AllocaInstruction inst = new AllocaInstruction(type, name);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个数组分配指令
     */
    public static AllocaInstruction createArrayAlloca(Type elementType, int size, BasicBlock block) {
        String name = "array_alloca_" + tmpCounter++;
        AllocaInstruction inst = new AllocaInstruction(elementType, size, name);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个加载指令
     */
    public static LoadInstruction createLoad(Value pointer, BasicBlock block) {
        String name = "load_" + tmpCounter++;
        LoadInstruction inst = new LoadInstruction(pointer, name);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个存储指令
     */
    public static StoreInstruction createStore(Value value, Value pointer, BasicBlock block) {
        StoreInstruction inst = new StoreInstruction(value, pointer);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个二元运算指令
     */
    public static BinaryInstruction createBinaryInst(OpCode opCode, Value left, Value right, BasicBlock block) {
        String name = opCode.getName() + "_" + tmpCounter++;
        BinaryInstruction inst = new BinaryInstruction(opCode, left, right, left.getType());
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个比较指令
     */
    public static CompareInstruction createCompare(OpCode compareType, OpCode predicate, Value left, Value right, BasicBlock block) {
        String name = compareType.getName() + "_" + predicate.getName() + "_" + tmpCounter++;
        CompareInstruction inst = new CompareInstruction(compareType, predicate, left, right);
        block.addInstruction(inst);
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
        block.addSuccessor(target);
        return inst;
    }
    
    /**
     * 创建一个条件分支指令
     */
    public static BranchInstruction createCondBr(Value condition, BasicBlock trueBlock, BasicBlock falseBlock, BasicBlock block) {
        BranchInstruction inst = new BranchInstruction(condition, trueBlock, falseBlock);
        block.addInstruction(inst);
        block.addSuccessor(trueBlock);
        block.addSuccessor(falseBlock);
        return inst;
    }
    
    /**
     * 创建一个函数调用指令
     */
    public static CallInstruction createCall(Function callee, List<Value> arguments, BasicBlock block) {
        String name = "call_" + tmpCounter++;
        CallInstruction inst = new CallInstruction(callee, arguments, name);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个Phi指令
     */
    public static PhiInstruction createPhi(Type type, BasicBlock block) {
        String name = "phi_" + tmpCounter++;
        PhiInstruction inst = new PhiInstruction(type, name);
        block.addInstructionFirst(inst);
        return inst;
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
} 
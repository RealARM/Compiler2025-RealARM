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
        // 自动类型转换，确保值类型与指针元素类型匹配
        if (pointer.getType() instanceof PointerType) {
            Type elementType = ((PointerType) pointer.getType()).getElementType();
            
            // 处理常量整数到常量浮点数的转换
            if (elementType instanceof FloatType && value instanceof ConstantInt) {
                ConstantInt constInt = (ConstantInt) value;
                value = new ConstantFloat((float) constInt.getValue());
            }
            // 处理常量浮点数到常量整数的转换
            else if (elementType instanceof IntegerType && value instanceof ConstantFloat) {
                ConstantFloat constFloat = (ConstantFloat) value;
                value = new ConstantInt((int) constFloat.getValue());
            }
            // 非常量值的类型转换
            else if (!value.getType().equals(elementType)) {
                // 整数和浮点数之间的转换
                if (elementType instanceof IntegerType && value.getType() instanceof FloatType) {
                    value = createFloatToInt(value, block);
                } else if (elementType instanceof FloatType && value.getType() instanceof IntegerType) {
                    value = createIntToFloat(value, block);
                } 
                // 整数类型之间的转换
                else if (elementType instanceof IntegerType && value.getType() instanceof IntegerType) {
                    IntegerType targetIntType = (IntegerType) elementType;
                    IntegerType valueIntType = (IntegerType) value.getType();
                    
                    if (targetIntType.getBitWidth() > valueIntType.getBitWidth()) {
                        // 扩展
                        value = createZeroExtend(value, elementType, block);
                    } else if (targetIntType.getBitWidth() < valueIntType.getBitWidth()) {
                        // 截断
                        value = createTrunc(value, elementType, block);
                    }
                }
            }
        }
        
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
     * 创建一个简单的GetElementPtr指令（类似示例的PtrInst）
     */
    public static GetElementPtrInstruction createGetElementPtr(Value pointer, Value index, BasicBlock block) {
        String name = "gep_" + tmpCounter++;
        GetElementPtrInstruction inst = new GetElementPtrInstruction(pointer, index, name);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个多维索引的GetElementPtr指令
     */
    public static GetElementPtrInstruction createGetElementPtr(Value pointer, List<Value> indices, BasicBlock block) {
        String name = "gep_" + tmpCounter++;
        GetElementPtrInstruction inst = new GetElementPtrInstruction(pointer, indices, name);
        block.addInstruction(inst);
        return inst;
    }
    
    /**
     * 创建一个类型转换指令
     */
    public static ConversionInstruction createConversion(Value value, Type targetType, OpCode conversionType, BasicBlock block) {
        String name = conversionType.getName() + "_" + tmpCounter++;
        ConversionInstruction inst = new ConversionInstruction(value, targetType, conversionType, name);
        block.addInstruction(inst);
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
} 
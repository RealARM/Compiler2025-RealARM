package Backend.Armv8;

import Backend.Armv8.Instruction.*;
import Backend.Armv8.Operand.*;
import Backend.Armv8.Structure.*;
import Backend.Armv8.tools.Armv8Tools;
import IR.Module;
import IR.Type.IntegerType;
import IR.Type.FloatType;
import IR.Type.PointerType;
import IR.Type.Type;
import IR.Value.*;
import IR.Value.Instructions.*;
import IR.OpCode;
import java.util.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import Backend.Armv8.Operand.Armv8Label;
import Backend.Armv8.Instruction.Armv8Move;
import Backend.Armv8.Instruction.Armv8Branch;
import Backend.Armv8.Instruction.Armv8Jump;

public class Armv8CodeGen {
    public Module irModule;
    public Armv8Module armv8Module = new Armv8Module();
    // 使用多态性，这样可以存储Armv8Label及其子类
    private final LinkedHashMap<Value, Armv8Label> value2Label = new LinkedHashMap<>();
    private final LinkedHashMap<Value, Armv8Reg> value2Reg = new LinkedHashMap<>();
    private final LinkedHashMap<Value, Long> ptr2Offset = new LinkedHashMap<>();
    private Armv8Block curArmv8Block = null;
    private Armv8Function curArmv8Function = null;
    private final LinkedHashMap<Instruction, ArrayList<Armv8Instruction>> predefines = new LinkedHashMap<>();

    public Armv8CodeGen(Module irModule) {
        this.irModule = irModule;
    }

    public String removeLeadingAt(String name) {
        if (name.startsWith("@")) {
            return name.substring(1);
        }
        return name;
    }

    public void run() {
        // 生成全局变量
        for (GlobalVariable globalVariable : irModule.globalVars()) {
            generateGlobalVariable(globalVariable);
        }

        // 生成函数
        for (Function function : irModule.functions()) {
            generateFunction(function);
        }
    }

    private void generateGlobalVariable(GlobalVariable globalVariable) {
        // 生成全局变量的实现
        String varName = removeLeadingAt(globalVariable.getName());
        ArrayList<Byte> initialValues = new ArrayList<>();
        int byteSize;
        
        // 获取类型的元素类型
        Type elementType = globalVariable.getElementType();
        
        if (globalVariable.isArray()) {
            // 数组类型全局变量
            byteSize = globalVariable.getArraySize() * elementType.getSize();
            
            // 处理数组初始化
            if (globalVariable.isZeroInitialized()) {
                // 零初始化的数组
                initialValues = null; // Armv8GlobalVariable will handle zero initialization
            } else if (globalVariable.hasInitializer() && globalVariable.getArrayValues() != null) {
                // 带有初始化列表的数组
                List<Value> arrayValues = globalVariable.getArrayValues();
                for (Value val : arrayValues) {
                    if (val instanceof ConstantInt) {
                        long intValue = ((ConstantInt) val).getValue();
                        // 将整数值转换为字节数组
                        int valueSize = elementType.getSize();
                        for (int i = 0; i < valueSize; i++) {
                            initialValues.add((byte) ((intValue >> (i * 8)) & 0xFF));
                        }
                    }
                }
            }
        } else {
            // 非数组类型全局变量
            byteSize = elementType.getSize();
            
            // 处理单个值的初始化
            if (globalVariable.hasInitializer()) {
                Value initializer = globalVariable.getInitializer();
                if (initializer instanceof ConstantInt) {
                    long intValue = ((ConstantInt) initializer).getValue();
                    // 将整数值转换为字节数组
                    for (int i = 0; i < byteSize; i++) {
                        initialValues.add((byte) ((intValue >> (i * 8)) & 0xFF));
                    }
                }
            } else {
                // 未初始化的变量
                initialValues = null; // Armv8GlobalVariable will handle zero initialization
            }
        }
        
        // 创建ARM全局变量并添加到模块
        Armv8GlobalVariable armv8GlobalVar = new Armv8GlobalVariable(varName, initialValues, byteSize);
        
        // 根据是否有初始值决定放在data段还是bss段
        if (initialValues == null || initialValues.isEmpty()) {
            armv8Module.addBssVar(armv8GlobalVar); // 未初始化的变量放在bss段
        } else {
            armv8Module.addDataVar(armv8GlobalVar); // 初始化的变量放在data段
        }
        
        value2Label.put(globalVariable, armv8GlobalVar);
    }

    private void generateFunction(Function function) {
        if (function.isExternal()) {
            return; // 跳过外部函数
        }

        String functionName = removeLeadingAt(function.getName());
        curArmv8Function = new Armv8Function(functionName);
        armv8Module.addFunction(functionName, curArmv8Function);

        // 将基本块映射到ARM块
        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            String blockName = removeLeadingAt(functionName) + "_" + basicBlock.getName();
            Armv8Block armv8Block = new Armv8Block(blockName);
            value2Label.put(basicBlock, armv8Block);
            curArmv8Function.addBlock(armv8Block);
        }

        // 为每个基本块生成代码
        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            curArmv8Block = (Armv8Block) value2Label.get(basicBlock);
            generateBasicBlock(basicBlock);
        }

        // 计算栈大小并完成函数
        int stackSize = calculateStackSize(function);
        curArmv8Function.setStackSize(stackSize);
    }

    // 计算函数需要的栈空间大小
    private int calculateStackSize(Function function) {
        int size = 0;
        
        // 遍历所有基本块的所有指令，检查需要的局部变量空间
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction instruction : block.getInstructions()) {
                if (instruction instanceof AllocaInstruction) {
                    AllocaInstruction alloca = (AllocaInstruction) instruction;
                    // 获取分配的类型大小，并进行必要的对齐
                    Type allocatedType = alloca.getAllocatedType();
                    int typeSize;
                    
                    if (alloca.isArrayAllocation()) {
                        // 如果是数组类型，计算数组总大小
                        typeSize = allocatedType.getSize() * alloca.getArraySize();
                    } else {
                        // 单个元素大小
                        typeSize = allocatedType.getSize();
                    }
                    
                    // 按8字节对齐
                    size += (typeSize + 7) / 8 * 8;
                }
            }
        }
        
        // 检查函数调用，确保有足够的空间保存寄存器和参数
        int maxCalleeArgs = 0;
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction instruction : block.getInstructions()) {
                if (instruction instanceof CallInstruction) {
                    CallInstruction call = (CallInstruction) instruction;
                    int argsCount = call.getArguments().size();
                    // 保存参数需要的栈空间（每个参数8字节，考虑到ARMv8的调用约定）
                    maxCalleeArgs = Math.max(maxCalleeArgs, argsCount);
                }
            }
        }
        
        // 为调用其他函数的参数分配栈空间（超过8个参数的部分）
        if (maxCalleeArgs > 8) {
            size += (maxCalleeArgs - 8) * 8; // 8字节对齐
        }
        
        // 考虑保存被调用者寄存器的空间
        // ARMv8中通常需要保存x19-x29这些被调用者保存的寄存器
        // 这部分已经在Armv8Function的generatePrologue和generateEpilogue中处理了
        
        // 确保栈大小是16字节对齐的（ARMv8 ABI要求）
        size = (size + 15) & ~15;
        
        return size;
    }

    private void generateBasicBlock(BasicBlock basicBlock) {
        for (Instruction instruction : basicBlock.getInstructions()) {
            parseInstruction(instruction, false);
        }
    }

    public void parseInstruction(Instruction ins, boolean predefine) {
        if (ins instanceof AllocaInstruction) {
            parseAlloc((AllocaInstruction) ins, predefine);
        } else if (ins instanceof BinaryInstruction) {
            parseBinaryInst((BinaryInstruction) ins, predefine);
        } else if (ins instanceof BranchInstruction) {
            parseBrInst((BranchInstruction) ins, predefine);
        } else if (ins instanceof CallInstruction) {
            parseCallInst((CallInstruction) ins, predefine);
        } else if (ins instanceof ConversionInstruction) {
            parseConversionInst((ConversionInstruction) ins, predefine);
        } else if (ins instanceof LoadInstruction) {
            parseLoad((LoadInstruction) ins, predefine);
        } else if (ins instanceof GetElementPtrInstruction) {
            parsePtrInst((GetElementPtrInstruction) ins, predefine);
        } else if (ins instanceof ReturnInstruction) {
            parseRetInst((ReturnInstruction) ins, predefine);
        } else if (ins instanceof StoreInstruction) {
            parseStore((StoreInstruction) ins, predefine);
        } else if (ins instanceof CompareInstruction) {
            parseCompareInst((CompareInstruction) ins, predefine);
        } else if (ins instanceof PhiInstruction) {
            parsePhiInst((PhiInstruction) ins, predefine);
        } else if (ins instanceof UnaryInstruction) {
            parseUnaryInst((UnaryInstruction) ins, predefine);
        } else {
            System.err.println("错误: 不支持的指令: " + ins.getClass().getName());
        }
    }

    // 指令解析方法将在这里实现
    private void parseAlloc(AllocaInstruction ins, boolean predefine) {
        // 计算分配的类型的大小
        Type allocatedType = ins.getAllocatedType();
        int size;
        
        // 判断是数组分配还是单一变量分配
        if (ins.isArrayAllocation()) {
            // 如果是数组，计算总大小
            size = allocatedType.getSize() * ins.getArraySize();
        } else {
            // 单一变量
            size = allocatedType.getSize();
        }
        
        // 按8字节对齐
        size = (size + 7) / 8 * 8;
        
        // 计算在栈中的偏移量
        // 注：此处假设堆栈增长为负方向(从高地址向低地址)，偏移量为正值
        long offset = 0;
        
        // 检查是否已有其他分配，计算下一个可用偏移量
        for (Value value : ptr2Offset.keySet()) {
            if (value instanceof AllocaInstruction) {
                AllocaInstruction allocaIns = (AllocaInstruction) value;
                Type type = allocaIns.getAllocatedType();
                int allocSize;
                
                if (allocaIns.isArrayAllocation()) {
                    allocSize = type.getSize() * allocaIns.getArraySize();
                } else {
                    allocSize = type.getSize();
                }
                
                // 按8字节对齐
                allocSize = (allocSize + 7) / 8 * 8;
                
                // 偏移量增加
                offset += allocSize;
            }
        }
        
        // 将此分配与计算出的偏移量关联起来
        ptr2Offset.put(ins, offset);
        
        // 注意：ARM中的栈分配不需要生成实际指令
        // 函数序言已经为局部变量分配了栈空间
        // 在后续的加载/存储指令中，将使用保存的偏移量来访问此分配的内存
    }

    private void parseBinaryInst(BinaryInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取IR指令的操作码和操作数
        OpCode opCode = ins.getOpCode();
        Value leftOperand = ins.getLeft();
        Value rightOperand = ins.getRight();
        
        // 默认使用32位操作（对应w寄存器）
        boolean is32Bit = true;
        
        // 获取或分配寄存器
        Armv8Reg destReg = Armv8Reg.allocateReg();
        value2Reg.put(ins, destReg);
        
        Armv8Operand leftOp, rightOp;
        
        // 处理左操作数
        if (leftOperand instanceof ConstantInt) {
            // 对于常量，创建立即数操作数
            long value = ((ConstantInt) leftOperand).getValue();
            leftOp = new Armv8Imm((int) value);
        } else {
            // 对于变量，使用寄存器
            if (!value2Reg.containsKey(leftOperand)) {
                Armv8Reg leftReg = Armv8Reg.allocateReg();
                value2Reg.put(leftOperand, leftReg);
                leftOp = leftReg;
            } else {
                leftOp = value2Reg.get(leftOperand);
            }
        }
        
        // 处理右操作数
        if (rightOperand instanceof ConstantInt) {
            // 对于常量，创建立即数操作数
            long value = ((ConstantInt) rightOperand).getValue();
            rightOp = new Armv8Imm((int) value);
        } else {
            // 对于变量，使用寄存器
            if (!value2Reg.containsKey(rightOperand)) {
                Armv8Reg rightReg = Armv8Reg.allocateReg();
                value2Reg.put(rightOperand, rightReg);
                rightOp = rightReg;
            } else {
                rightOp = value2Reg.get(rightOperand);
            }
        }
        
        // 创建操作数列表
        ArrayList<Armv8Operand> operands = new ArrayList<>();
        operands.add(leftOp);
        operands.add(rightOp);
        
        // 根据操作码生成对应的ARM指令
        Armv8Binary.Armv8BinaryType binaryType = null;
        switch (opCode) {
            case ADD:
                binaryType = Armv8Binary.Armv8BinaryType.add;
                break;
            case SUB:
                binaryType = Armv8Binary.Armv8BinaryType.sub;
                break;
            case MUL:
                binaryType = Armv8Binary.Armv8BinaryType.mul;
                break;
            case DIV:
                binaryType = Armv8Binary.Armv8BinaryType.sdiv;
                break;
            case REM:
                // 余数操作在ARM中需要特殊处理
                // 1. 先做除法 dest = a / b
                // 2. 然后计算余数 dest = a - dest * b
                
                // 创建除法操作
                Armv8Binary divInst = new Armv8Binary(operands, destReg, Armv8Binary.Armv8BinaryType.sdiv, is32Bit);
                addInstr(divInst, insList, predefine);
                
                // 创建临时寄存器存储乘法结果
                Armv8Reg tempReg = Armv8Reg.allocateReg();
                
                // 创建乘法操作 temp = dest * b
                ArrayList<Armv8Operand> mulOperands = new ArrayList<>();
                mulOperands.add(destReg);
                mulOperands.add(rightOp);
                Armv8Binary mulInst = new Armv8Binary(mulOperands, tempReg, Armv8Binary.Armv8BinaryType.mul, is32Bit);
                addInstr(mulInst, insList, predefine);
                
                // 创建减法操作 dest = a - temp
                ArrayList<Armv8Operand> subOperands = new ArrayList<>();
                subOperands.add(leftOp);
                subOperands.add(tempReg);
                Armv8Binary subInst = new Armv8Binary(subOperands, destReg, Armv8Binary.Armv8BinaryType.sub, is32Bit);
                addInstr(subInst, insList, predefine);
                
                return; // 余数操作已完成，直接返回
                
            case AND:
                binaryType = Armv8Binary.Armv8BinaryType.and;
                break;
            case OR:
                binaryType = Armv8Binary.Armv8BinaryType.orr;
                break;
            case XOR:
                binaryType = Armv8Binary.Armv8BinaryType.eor;
                break;
            case SHL:
                binaryType = Armv8Binary.Armv8BinaryType.lsl;
                break;
            case LSHR:
                binaryType = Armv8Binary.Armv8BinaryType.lsr;
                break;
            case ASHR:
                binaryType = Armv8Binary.Armv8BinaryType.asr;
                break;
            default:
                System.err.println("不支持的二元操作: " + opCode);
                return;
        }
        
        // 创建二元指令
        Armv8Binary binaryInst = new Armv8Binary(operands, destReg, binaryType, is32Bit);
        addInstr(binaryInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseBrInst(BranchInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        if (ins.isUnconditional()) {
            // 处理无条件跳转
            BasicBlock targetBlock = ins.getTrueBlock();
            Armv8Block armTarget = (Armv8Block) value2Label.get(targetBlock);
            
            // 创建无条件跳转指令
            Armv8Jump jumpInst = new Armv8Jump(armTarget, curArmv8Block);
            addInstr(jumpInst, insList, predefine);
        } else {
            // 处理条件跳转
            Value condition = ins.getCondition();
            BasicBlock trueBlock = ins.getTrueBlock();
            BasicBlock falseBlock = ins.getFalseBlock();
            
            Armv8Block armTrueBlock = (Armv8Block) value2Label.get(trueBlock);
            Armv8Block armFalseBlock = (Armv8Block) value2Label.get(falseBlock);
            
            // 条件指令需要首先处理条件
            if (condition instanceof IR.Value.Instructions.CompareInstruction) {
                // 如果是比较指令，生成比较指令
                CompareInstruction cmpIns = (CompareInstruction) condition;
                OpCode cmpOp = cmpIns.getCompareType();
                Value left = cmpIns.getLeft();
                Value right = cmpIns.getRight();
                OpCode predicate = cmpIns.getPredicate();
                
                // 获取左右操作数
                Armv8Operand leftOp, rightOp;
                
                // 处理左操作数
                if (left instanceof ConstantInt) {
                    long value = ((ConstantInt) left).getValue();
                    leftOp = new Armv8Imm((int) value);
                } else {
                    if (!value2Reg.containsKey(left)) {
                        Armv8Reg leftReg = Armv8Reg.allocateReg();
                        value2Reg.put(left, leftReg);
                        leftOp = leftReg;
                    } else {
                        leftOp = value2Reg.get(left);
                    }
                }
                
                // 处理右操作数
                if (right instanceof ConstantInt) {
                    long value = ((ConstantInt) right).getValue();
                    rightOp = new Armv8Imm((int) value);
                } else {
                    if (!value2Reg.containsKey(right)) {
                        Armv8Reg rightReg = Armv8Reg.allocateReg();
                        value2Reg.put(right, rightReg);
                        rightOp = rightReg;
                    } else {
                        rightOp = value2Reg.get(right);
                    }
                }
                
                // 创建比较指令
                boolean is32Bit = true;  // 默认使用32位比较
                Armv8Compare.CmpType cmpType = Armv8Compare.CmpType.cmp;
                Armv8Compare compareInst = new Armv8Compare(leftOp, rightOp, cmpType, is32Bit);
                addInstr(compareInst, insList, predefine);
                
                // 根据比较谓词确定条件分支类型
                Armv8Tools.CondType condType;
                switch (predicate) {
                    case EQ:
                        condType = Armv8Tools.CondType.eq;
                        break;
                    case NE:
                        condType = Armv8Tools.CondType.ne;
                        break;
                    case SGT:
                        condType = Armv8Tools.CondType.gt;
                        break;
                    case SGE:
                        condType = Armv8Tools.CondType.ge;
                        break;
                    case SLT:
                        condType = Armv8Tools.CondType.lt;
                        break;
                    case SLE:
                        condType = Armv8Tools.CondType.le;
                        break;
                    default:
                        System.err.println("不支持的比较谓词: " + predicate);
                        condType = Armv8Tools.CondType.eq;  // 默认为等于
                        break;
                }
                
                // 创建条件跳转到true块
                Armv8Branch branchTrueInst = new Armv8Branch(armTrueBlock, condType);
                branchTrueInst.setPredSucc(curArmv8Block);
                addInstr(branchTrueInst, insList, predefine);
                
                // 无条件跳转到false块作为默认情况
                Armv8Jump jumpFalseInst = new Armv8Jump(armFalseBlock, curArmv8Block);
                addInstr(jumpFalseInst, insList, predefine);
                
            } else {
                // 如果不是直接的比较指令，而是一个布尔值
                Armv8Operand condOp;
                
                if (condition instanceof ConstantInt) {
                    // 对于常量条件，直接生成对应的跳转
                    long value = ((ConstantInt) condition).getValue();
                    if (value != 0) {
                        // 条件为真，直接跳转到true块
                        Armv8Jump jumpTrueInst = new Armv8Jump(armTrueBlock, curArmv8Block);
                        addInstr(jumpTrueInst, insList, predefine);
                    } else {
                        // 条件为假，直接跳转到false块
                        Armv8Jump jumpFalseInst = new Armv8Jump(armFalseBlock, curArmv8Block);
                        addInstr(jumpFalseInst, insList, predefine);
                    }
                    return;
                } else {
                    // 对于变量条件，使用寄存器
                    if (!value2Reg.containsKey(condition)) {
                        Armv8Reg condReg = Armv8Reg.allocateReg();
                        value2Reg.put(condition, condReg);
                        condOp = condReg;
                    } else {
                        condOp = value2Reg.get(condition);
                    }
                    
                    // 使用CBZ指令测试条件是否为0
                    boolean is32Bit = true;  // 默认使用32位比较
                    
                    // 如果为0则跳转到false块
                    Armv8Cbz cbzInst = new Armv8Cbz((Armv8Reg) condOp, armFalseBlock, is32Bit);
                    cbzInst.setPredSucc(curArmv8Block);
                    addInstr(cbzInst, insList, predefine);
                    
                    // 否则跳转到true块
                    Armv8Jump jumpTrueInst = new Armv8Jump(armTrueBlock, curArmv8Block);
                    addInstr(jumpTrueInst, insList, predefine);
                }
            }
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseCallInst(CallInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取被调用的函数
        Function callee = ins.getCallee();
        String functionName = removeLeadingAt(callee.getName());
        
        // 获取函数参数列表
        List<Value> arguments = ins.getArguments();
        int argCount = arguments.size();
        
        // ARMv8调用约定：前8个参数通过寄存器传递，剩余参数通过栈传递
        for (int i = 0; i < argCount; i++) {
            Value arg = arguments.get(i);
            
            if (i < 8) {
                // 前8个参数通过寄存器x0-x7传递
                Armv8CPUReg argReg = Armv8CPUReg.getArmv8ArgReg(i);
                
                // 处理参数值
                if (arg instanceof ConstantInt) {
                    // 对于常量整数，直接加载到对应的参数寄存器
                    long value = ((ConstantInt) arg).getValue();
                    Armv8Imm imm = new Armv8Imm((int) value);
                    
                    // 生成加载立即数到寄存器指令
                    Armv8Move moveInst = new Armv8Move(argReg, imm, true);
                    addInstr(moveInst, insList, predefine);
                } else {
                    // 对于变量，先确保它有对应的寄存器
                    if (!value2Reg.containsKey(arg)) {
                        // 如果变量没有被分配寄存器，为它分配一个
                        Armv8Reg argValueReg = Armv8Reg.allocateReg();
                        value2Reg.put(arg, argValueReg);
                    }
                    
                    // 将变量寄存器的值移动到参数寄存器
                    Armv8Reg argValueReg = value2Reg.get(arg);
                    Armv8Move moveInst = new Armv8Move(argReg, argValueReg, false);
                    addInstr(moveInst, insList, predefine);
                }
            } else {
                // 超过8个参数的部分通过栈传递
                // 计算栈上的偏移量：(i-8)*8
                int stackOffset = (i - 8) * 8;
                
                // 处理参数值
                if (arg instanceof ConstantInt) {
                    // 对于常量，先加载到临时寄存器再存入栈
                    long value = ((ConstantInt) arg).getValue();
                    Armv8Reg tempReg = Armv8Reg.allocateReg();
                    Armv8Imm imm = new Armv8Imm((int) value);
                    
                    // 生成加载立即数到临时寄存器指令
                    Armv8Move moveInst = new Armv8Move(tempReg, imm, true);
                    addInstr(moveInst, insList, predefine);
                    
                    // 将临时寄存器值存储到栈上
                    Armv8CPUReg spReg = Armv8CPUReg.getArmv8SpReg();
                    Armv8Store storeInst = new Armv8Store(tempReg, spReg, new Armv8Imm((int)stackOffset), true);
                    addInstr(storeInst, insList, predefine);
                } else {
                    // 对于变量，确保它有对应的寄存器
                    if (!value2Reg.containsKey(arg)) {
                        Armv8Reg argValueReg = Armv8Reg.allocateReg();
                        value2Reg.put(arg, argValueReg);
                    }
                    
                    // 将变量寄存器的值存储到栈上
                    Armv8Reg argValueReg = value2Reg.get(arg);
                    Armv8CPUReg spReg = Armv8CPUReg.getArmv8SpReg();
                    Armv8Store storeInst = new Armv8Store(argValueReg, spReg, new Armv8Imm((int)stackOffset), true);
                    addInstr(storeInst, insList, predefine);
                }
            }
        }
        
        // 创建调用标签
        Armv8Label functionLabel = new Armv8Label(functionName);
        
        // 生成调用指令
        Armv8Call callInst = new Armv8Call(functionLabel);
        
        // 记录使用的寄存器（用于调用者保存寄存器的保存和恢复）
        for (Value value : value2Reg.keySet()) {
            Armv8Reg reg = value2Reg.get(value);
            if (reg instanceof Armv8CPUReg && ((Armv8CPUReg) reg).canBeReorder()) {
                callInst.addUsedReg(reg);
            }
        }
        
        addInstr(callInst, insList, predefine);
        
        // 处理返回值
        if (!ins.isVoidCall()) {
            // 非void函数的返回值在x0寄存器
            Armv8CPUReg returnReg = Armv8CPUReg.getArmv8CPURetValueReg();
            
            // 为调用指令分配一个寄存器存储返回值
            Armv8Reg resultReg = Armv8Reg.allocateReg();
            value2Reg.put(ins, resultReg);
            
            // 将返回值从x0移动到结果寄存器
            Armv8Move moveReturnInst = new Armv8Move(resultReg, returnReg, false);
            addInstr(moveReturnInst, insList, predefine);
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseConversionInst(ConversionInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取源值和目标类型
        Value source = ins.getSource();
        Type targetType = ins.getType();
        OpCode conversionType = ins.getConversionType();
        
        // 获取或分配源寄存器
        Armv8Reg srcReg;
        if (source instanceof ConstantInt) {
            // 对于常量，需要先加载到寄存器中
            long value = ((ConstantInt) source).getValue();
            Armv8Imm imm = new Armv8Imm((int) value);
            srcReg = Armv8Reg.allocateReg();
            
            // 创建一个移动指令将常量加载到寄存器
            Armv8Move moveInst = new Armv8Move(srcReg, imm, false);
            addInstr(moveInst, insList, predefine);
        } else if (value2Reg.containsKey(source)) {
            srcReg = value2Reg.get(source);
        } else {
            srcReg = Armv8Reg.allocateReg();
            value2Reg.put(source, srcReg);
        }
        
        // 分配目标寄存器
        Armv8Reg destReg;
        if (targetType.isFloatType()) {
            // 目标是浮点类型，使用浮点寄存器
            destReg = Armv8FPUReg.getArmv8FloatReg(0); // 简化处理，直接使用v0
            for (int i = 1; i < 32; i++) {
                // 尝试找一个未使用的FPU寄存器
                if (!value2Reg.containsValue(Armv8FPUReg.getArmv8FloatReg(i))) {
                    destReg = Armv8FPUReg.getArmv8FloatReg(i);
                    break;
                }
            }
        } else {
            // 目标是整数类型，使用通用寄存器
            destReg = Armv8Reg.allocateReg();
        }
        value2Reg.put(ins, destReg);
        
        // 根据转换类型创建相应的转换指令
        Armv8Cvt.CvtType armCvtType;
        
        if (ins.isIntToFloat()) {
            // 整数到浮点转换
            if (conversionType == OpCode.SITOFP) {
                armCvtType = Armv8Cvt.CvtType.SCVTF;  // 有符号整数转浮点
            } else {
                armCvtType = Armv8Cvt.CvtType.UCVTF;  // 无符号整数转浮点
            }
        } else if (ins.isFloatToInt()) {
            // 浮点到整数转换
            if (conversionType == OpCode.FPTOSI) {
                armCvtType = Armv8Cvt.CvtType.FCVTZS; // 浮点转有符号整数，向零舍入
            } else {
                armCvtType = Armv8Cvt.CvtType.FCVTZU; // 浮点转无符号整数，向零舍入
            }
        } else if (ins.isTruncation()) {
            // 位截断操作
            // ARMv8没有直接的位截断指令，可能需要使用AND指令掩码来实现
            ArrayList<Armv8Operand> andOps = new ArrayList<>();
            andOps.add(srcReg);
            
            // 创建掩码，根据目标类型决定
            long mask;
            if (targetType instanceof IntegerType) {
                int bits = ((IntegerType) targetType).getBitWidth();
                mask = (1L << bits) - 1;
            } else {
                mask = 0xFFFFFFFFL; // 默认32位掩码
            }
            
            Armv8Imm maskImm = new Armv8Imm((int) mask);
            andOps.add(maskImm);
            
            Armv8Binary andInst = new Armv8Binary(andOps, destReg, Armv8Binary.Armv8BinaryType.and, true);
            addInstr(andInst, insList, predefine);
            
            // 记录指令列表并返回
            if (predefine) {
                predefines.put(ins, insList);
            }
            return;
        } else if (ins.isExtension()) {
            // 扩展操作
            if (conversionType == OpCode.SEXT) {
                // 符号扩展
                // ARMv8可以通过SBFX或者直接移位实现，这里简单处理
                Armv8Move sextInst = new Armv8Move(destReg, srcReg, false);
                addInstr(sextInst, insList, predefine);
            } else {
                // 零扩展
                // ARMv8可以通过UBFX或者位掩码实现，这里简单处理
                Armv8Move zextInst = new Armv8Move(destReg, srcReg, true); // 使用32位模式
                addInstr(zextInst, insList, predefine);
            }
            
            // 记录指令列表并返回
            if (predefine) {
                predefines.put(ins, insList);
            }
            return;
        } else {
            // 其他转换类型（如位转换）
            // 简单地移动数据
            Armv8Move moveInst = new Armv8Move(destReg, srcReg, false);
            addInstr(moveInst, insList, predefine);
            
            // 记录指令列表并返回
            if (predefine) {
                predefines.put(ins, insList);
            }
            return;
        }
        
        // 创建转换指令
        Armv8Cvt cvtInst = new Armv8Cvt(srcReg, armCvtType, destReg);
        addInstr(cvtInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseLoad(LoadInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取要加载的指针
        Value pointer = ins.getPointer();
        // 获取加载的类型
        Type loadedType = ins.getLoadedType();
        // 判断是否是32位加载（整型加载情况）
        boolean is32Bit = true;
        
        // 为结果分配目标寄存器
        Armv8Reg destReg = Armv8Reg.allocateReg();
        value2Reg.put(ins, destReg);
        
        // 处理指针操作数
        Armv8Reg baseReg;
        Armv8Operand offsetOp = new Armv8Imm(0); // 默认偏移量为0
        
        // 处理各种类型的指针
        if (pointer instanceof GetElementPtrInstruction) {
            // 如果是GEP指令，检查是否已经有关联的寄存器
            if (!value2Reg.containsKey(pointer)) {
                // 如果没有，解析GEP指令
                parsePtrInst((GetElementPtrInstruction) pointer, true);
            }
            
            if (value2Reg.containsKey(pointer)) {
                // 使用GEP结果作为基地址
                baseReg = value2Reg.get(pointer);
            } else if (ptr2Offset.containsKey(pointer)) {
                // 使用栈指针加上偏移量
                baseReg = Armv8CPUReg.getArmv8SpReg();
                offsetOp = new Armv8Imm(ptr2Offset.get(pointer).intValue());
            } else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            // 对于全局变量，使用标签
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            Armv8Label label = new Armv8Label(globalName);
            
            // 为全局变量地址分配寄存器
            baseReg = Armv8Reg.allocateReg();
            
            // 创建加载地址指令
            Armv8Adr adrInst = new Armv8Adr(baseReg, label);
            addInstr(adrInst, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果是局部变量分配指令
            if (!ptr2Offset.containsKey(pointer)) {
                parseAlloc((AllocaInstruction) pointer, true);
            }
            
            // 使用栈指针加上偏移量
            baseReg = Armv8CPUReg.getArmv8SpReg();
            offsetOp = new Armv8Imm(ptr2Offset.get(pointer).intValue());
        } else {
            // 其他类型的指针，尝试获取关联的寄存器
            if (!value2Reg.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = value2Reg.get(pointer);
        }
        
        // 创建加载指令
        Armv8Load loadInst = new Armv8Load(baseReg, offsetOp, destReg, is32Bit);
        addInstr(loadInst, insList, predefine);
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parsePtrInst(GetElementPtrInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取基地址指针和索引
        Value pointer = ins.getPointer();
        List<Value> indices = ins.getIndices();
        
        // 为结果分配目标寄存器
        Armv8Reg destReg = Armv8Reg.allocateReg();
        value2Reg.put(ins, destReg);
        
        // 处理基地址指针
        Armv8Reg baseReg;
        
        if (pointer instanceof GlobalVariable) {
            // 如果基地址是全局变量
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            Armv8Label label = new Armv8Label(globalName);
            
            // 加载全局变量地址
            baseReg = Armv8Reg.allocateReg();
            Armv8Adr adrInst = new Armv8Adr(baseReg, label);
            addInstr(adrInst, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果基地址是局部变量
            if (!ptr2Offset.containsKey(pointer)) {
                parseAlloc((AllocaInstruction) pointer, true);
            }
            
            // 计算局部变量地址：SP + 偏移
            baseReg = Armv8Reg.allocateReg();
            
            // 使用Armv8Binary创建加法指令
            ArrayList<Armv8Operand> operands = new ArrayList<>();
            operands.add(Armv8CPUReg.getArmv8SpReg());
            operands.add(new Armv8Imm(ptr2Offset.get(pointer).intValue()));
            
            Armv8Binary addInst = new Armv8Binary(operands, baseReg, Armv8Binary.Armv8BinaryType.add, true);
            addInstr(addInst, insList, predefine);
        } else {
            // 其他类型的指针（如函数参数或其他指令的结果）
            if (!value2Reg.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = value2Reg.get(pointer);
        }
        
        // 先将基地址复制到目标寄存器
        if (baseReg != destReg) {
            Armv8Move moveInst = new Armv8Move(destReg, baseReg, false);
            addInstr(moveInst, insList, predefine);
        }
        
        // 处理索引，计算偏移
        if (!indices.isEmpty()) {
            // 获取第一个索引
            Value indexValue = indices.get(0);
            Armv8Reg indexReg;
            
            // 计算元素大小
            Type elementType = ins.getElementType();
            int elementSize = elementType.getSize();
            
            // 处理索引操作数
            if (indexValue instanceof ConstantInt) {
                // 如果索引是常量，直接计算偏移
                long indexVal = ((ConstantInt) indexValue).getValue();
                long offset = indexVal * elementSize;
                
                // 使用ADD指令将偏移添加到基地址
                if (offset != 0) {
                    ArrayList<Armv8Operand> operands = new ArrayList<>();
                    operands.add(destReg);
                    operands.add(new Armv8Imm((int) offset));
                    
                    Armv8Binary addInst = new Armv8Binary(operands, destReg, Armv8Binary.Armv8BinaryType.add, true);
                    addInstr(addInst, insList, predefine);
                }
            } else {
                // 如果索引是变量，需要先加载索引值
                if (!value2Reg.containsKey(indexValue)) {
                    if (indexValue instanceof Instruction) {
                        parseInstruction((Instruction) indexValue, true);
                    } else {
                        throw new RuntimeException("未处理的索引类型: " + indexValue);
                    }
                }
                indexReg = value2Reg.get(indexValue);
                
                // 计算偏移：index * elementSize
                Armv8Reg tempReg = Armv8Reg.allocateReg();
                if (elementSize != 1) {
                    // 先将索引值乘以元素大小
                    Armv8Move moveInst = new Armv8Move(tempReg, new Armv8Imm((int)elementSize), true);
                    addInstr(moveInst, insList, predefine);
                    
                    // 使用Armv8Binary创建乘法指令
                    ArrayList<Armv8Operand> mulOperands = new ArrayList<>();
                    mulOperands.add(indexReg);
                    mulOperands.add(tempReg);
                    
                    Armv8Binary mulInst = new Armv8Binary(mulOperands, tempReg, Armv8Binary.Armv8BinaryType.mul, false);
                    addInstr(mulInst, insList, predefine);
                    
                    // 将计算得到的偏移添加到基地址
                    ArrayList<Armv8Operand> addOperands = new ArrayList<>();
                    addOperands.add(destReg);
                    addOperands.add(tempReg);
                    
                    Armv8Binary addInst = new Armv8Binary(addOperands, destReg, Armv8Binary.Armv8BinaryType.add, false);
                    addInstr(addInst, insList, predefine);
                } else {
                    // 元素大小为1时，直接加索引
                    ArrayList<Armv8Operand> addOperands = new ArrayList<>();
                    addOperands.add(destReg);
                    addOperands.add(indexReg);
                    
                    Armv8Binary addInst = new Armv8Binary(addOperands, destReg, Armv8Binary.Armv8BinaryType.add, false);
                    addInstr(addInst, insList, predefine);
                }
            }
        }
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseRetInst(ReturnInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 检查是否有返回值
        if (!ins.isVoidReturn()) {
            // 获取返回值
            Value returnValue = ins.getReturnValue();
            
            // 根据返回值类型确定返回寄存器
            Armv8Reg returnReg;
            
            // 判断返回值类型，使用适当的返回寄存器
            if (returnValue.getType() instanceof FloatType) {
                // 浮点返回值使用v0
                returnReg = Armv8FPUReg.getArmv8FPURetValueReg();
            } else {
                // 整数返回值使用x0
                returnReg = Armv8CPUReg.getArmv8CPURetValueReg();
            }
            
            // 如果返回值是常量
            if (returnValue instanceof ConstantInt) {
                // 加载立即数到返回寄存器
                Armv8Move moveInst = new Armv8Move(returnReg, new Armv8Imm((int)((ConstantInt)returnValue).getValue()), true);
                addInstr(moveInst, insList, predefine);
            }
            else if (returnValue instanceof ConstantFloat) {
                // 处理浮点常量的存储
                // 注意：可能需要特殊处理浮点常量的加载
                // 这部分可能需要根据具体实现调整
                System.err.println("警告: 浮点常量返回值尚未完全实现");
            }
            else {
                // 如果是变量，需要确保已经有关联的寄存器
                if (!value2Reg.containsKey(returnValue)) {
                    if (returnValue instanceof Instruction) {
                        parseInstruction((Instruction)returnValue, true);
                    } else {
                        throw new RuntimeException("未处理的返回值类型: " + returnValue);
                    }
                }
                
                // 将值移动到返回寄存器
                Armv8Reg sourceReg = value2Reg.get(returnValue);
                Armv8Move moveInst = new Armv8Move(returnReg, sourceReg, returnValue.getType() instanceof IntegerType);
                addInstr(moveInst, insList, predefine);
            }
        }
        
        // 添加返回指令
        Armv8Ret retInst = new Armv8Ret();
        addInstr(retInst, insList, predefine);
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseStore(StoreInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取要存储的值和目标指针
        Value valueToStore = ins.getValue();
        Value pointer = ins.getPointer();
        
        // 判断是否是32位存储（整型存储情况）
        boolean is32Bit = !(valueToStore.getType() instanceof FloatType);
        
        // 处理待存储值，获取或创建包含值的寄存器
        Armv8Reg valueReg;
        
        if (valueToStore instanceof ConstantInt) {
            // 对于整数常量，创建一个临时寄存器并加载值
            valueReg = Armv8Reg.allocateReg();
            Armv8Move moveInst = new Armv8Move(valueReg, new Armv8Imm((int)((ConstantInt)valueToStore).getValue()), true);
            addInstr(moveInst, insList, predefine);
        } 
        else if (valueToStore instanceof ConstantFloat) {
            // 处理浮点常量的存储
            // 注意：可能需要特殊处理浮点常量的加载
            valueReg = Armv8Reg.allocateReg();
            System.err.println("警告: 浮点常量存储尚未完全实现");
        }
        else {
            // 对于变量，确保已经有关联的寄存器
            if (!value2Reg.containsKey(valueToStore)) {
                if (valueToStore instanceof Instruction) {
                    parseInstruction((Instruction)valueToStore, true);
                } else {
                    throw new RuntimeException("未处理的存储值类型: " + valueToStore);
                }
            }
            valueReg = value2Reg.get(valueToStore);
        }
        
        // 处理指针操作数
        Armv8Reg baseReg;
        Armv8Operand offsetOp = new Armv8Imm(0); // 默认偏移量为0
        
        // 处理各种类型的指针
        if (pointer instanceof GetElementPtrInstruction) {
            // 如果是GEP指令，检查是否已经有关联的寄存器
            if (!value2Reg.containsKey(pointer)) {
                // 如果没有，解析GEP指令
                parsePtrInst((GetElementPtrInstruction)pointer, true);
            }
            
            if (value2Reg.containsKey(pointer)) {
                // 使用GEP结果作为基地址
                baseReg = value2Reg.get(pointer);
            } else if (ptr2Offset.containsKey(pointer)) {
                // 使用栈指针加上偏移量
                baseReg = Armv8CPUReg.getArmv8SpReg();
                offsetOp = new Armv8Imm(ptr2Offset.get(pointer).intValue());
            } else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            // 对于全局变量，使用标签
            String globalName = removeLeadingAt(((GlobalVariable)pointer).getName());
            Armv8Label label = new Armv8Label(globalName);
            
            // 为全局变量地址分配寄存器
            baseReg = Armv8Reg.allocateReg();
            
            // 创建加载地址指令
            Armv8Adr adrInst = new Armv8Adr(baseReg, label);
            addInstr(adrInst, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果是局部变量分配指令
            if (!ptr2Offset.containsKey(pointer)) {
                parseAlloc((AllocaInstruction)pointer, true);
            }
            
            // 使用栈指针加上偏移量
            baseReg = Armv8CPUReg.getArmv8SpReg();
            offsetOp = new Armv8Imm(ptr2Offset.get(pointer).intValue());
        } else {
            // 其他类型的指针，尝试获取关联的寄存器
            if (!value2Reg.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction)pointer, true);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = value2Reg.get(pointer);
        }
        
        // 创建存储指令
        Armv8Store storeInst = new Armv8Store(valueReg, baseReg, offsetOp, is32Bit);
        addInstr(storeInst, insList, predefine);
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseCompareInst(CompareInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取比较指令的操作数和比较类型
        Value left = ins.getLeft();
        Value right = ins.getRight();
        OpCode predicate = ins.getPredicate();
        boolean isFloat = ins.isFloatCompare();
        
        // 为结果分配目标寄存器
        Armv8Reg destReg = Armv8Reg.allocateReg();
        value2Reg.put(ins, destReg);
        
        // 处理左操作数
        Armv8Operand leftOp;
        if (left instanceof ConstantInt) {
            // 对于常量，创建立即数操作数
            long value = ((ConstantInt) left).getValue();
            leftOp = new Armv8Imm((int) value);
        } else {
            // 对于变量，使用寄存器
            if (!value2Reg.containsKey(left)) {
                Armv8Reg leftReg = Armv8Reg.allocateReg();
                value2Reg.put(left, leftReg);
                leftOp = leftReg;
            } else {
                leftOp = value2Reg.get(left);
            }
        }
        
        // 处理右操作数
        Armv8Operand rightOp;
        if (right instanceof ConstantInt) {
            // 对于常量，创建立即数操作数
            long value = ((ConstantInt) right).getValue();
            rightOp = new Armv8Imm((int) value);
        } else {
            // 对于变量，使用寄存器
            if (!value2Reg.containsKey(right)) {
                Armv8Reg rightReg = Armv8Reg.allocateReg();
                value2Reg.put(right, rightReg);
                rightOp = rightReg;
            } else {
                rightOp = value2Reg.get(right);
            }
        }
        
        // 创建比较指令
        boolean is32Bit = true; // 默认使用32位比较
        Armv8Compare.CmpType cmpType = isFloat ? Armv8Compare.CmpType.fcmp : Armv8Compare.CmpType.cmp;
        Armv8Compare compareInst = new Armv8Compare(leftOp, rightOp, cmpType, is32Bit);
        addInstr(compareInst, insList, predefine);
        
        // 根据比较谓词设置条件寄存器
        Armv8Tools.CondType condType;
        switch (predicate) {
            case EQ:
                condType = Armv8Tools.CondType.eq;
                break;
            case NE:
                condType = Armv8Tools.CondType.ne;
                break;
            case SGT:
                condType = Armv8Tools.CondType.gt;
                break;
            case SGE:
                condType = Armv8Tools.CondType.ge;
                break;
            case SLT:
                condType = Armv8Tools.CondType.lt;
                break;
            case SLE:
                condType = Armv8Tools.CondType.le;
                break;
            case UGT:
                condType = Armv8Tools.CondType.hi; // 无符号大于
                break;
            case UGE:
                condType = Armv8Tools.CondType.cs; // 无符号大于等于
                break;
            case ULT:
                condType = Armv8Tools.CondType.cc; // 无符号小于
                break;
            case ULE:
                condType = Armv8Tools.CondType.ls; // 无符号小于等于
                break;
            default:
                System.err.println("不支持的比较谓词: " + predicate);
                condType = Armv8Tools.CondType.eq; // 默认为等于
                break;
        }
        
        // 创建条件设置指令（CSET）将比较结果存储到目标寄存器
        Armv8Cset csetInst = new Armv8Cset(destReg, condType, is32Bit);
        addInstr(csetInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parsePhiInst(PhiInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 为Phi结果分配目标寄存器
        Armv8Reg destReg = Armv8Reg.allocateReg();
        value2Reg.put(ins, destReg);
        
        // 获取Phi指令的所有输入块和对应值
        Map<BasicBlock, Value> incomingValues = ins.getIncomingValues();
        
        // 处理每个输入值
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            BasicBlock incomingBlock = entry.getKey();
            Value incomingValue = entry.getValue();
            
            // 获取对应的ARM块
            Armv8Block armv8IncomingBlock = (Armv8Block) value2Label.get(incomingBlock);
            if (armv8IncomingBlock == null) {
                System.err.println("警告: 找不到Phi指令输入块的对应ARM块: " + incomingBlock.getName());
                continue;
            }
            
            // 为输入值创建源操作数
            Armv8Operand srcOp;
            if (incomingValue instanceof ConstantInt) {
                // 常量值，创建立即数
                long value = ((ConstantInt) incomingValue).getValue();
                srcOp = new Armv8Imm((int) value);
            } else {
                // 非常量值，使用寄存器
                if (!value2Reg.containsKey(incomingValue)) {
                    if (incomingValue instanceof Instruction) {
                        // 递归处理未解析的指令
                        parseInstruction((Instruction) incomingValue, true);
                    } else {
                        // 为未分配寄存器的值分配一个新寄存器
                        Armv8Reg valueReg = Armv8Reg.allocateReg();
                        value2Reg.put(incomingValue, valueReg);
                    }
                }
                
                if (value2Reg.containsKey(incomingValue)) {
                    srcOp = value2Reg.get(incomingValue);
                } else {
                    System.err.println("警告: 无法为Phi指令的输入值创建操作数: " + incomingValue);
                    continue;
                }
            }
            
            // 创建移动指令，将输入值移动到目标寄存器
            // 注意：这些指令将被添加到前驱块的末尾，在跳转指令之前
            boolean is32Bit = ins.getType() instanceof IntegerType;
            
            // 创建移动指令
            Armv8Move moveInst;
            if (srcOp instanceof Armv8Imm) {
                moveInst = new Armv8Move(destReg, srcOp, is32Bit);
            } else {
                moveInst = new Armv8Move(destReg, (Armv8Reg) srcOp, is32Bit);
            }
            
            // 为前驱块添加phi解析指令
            if (!predefine) {
                // 如果不是预定义阶段，将指令直接添加到前驱块的末尾(在跳转指令之前)
                Armv8Instruction lastInst = armv8IncomingBlock.getLastInstruction();
                if (lastInst != null && (lastInst instanceof Armv8Branch || lastInst instanceof Armv8Jump)) {
                    // 在跳转指令前插入移动指令
                    armv8IncomingBlock.insertBeforeInst(lastInst, moveInst);
                } else {
                    // 没有跳转指令，直接添加到块末尾
                    armv8IncomingBlock.addArmv8Instruction(moveInst);
                }
            } else {
                // 预定义阶段，只将指令添加到列表中
                insList.add(moveInst);
            }
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseUnaryInst(UnaryInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取操作码和操作数
        OpCode opCode = ins.getOpCode();
        Value operand = ins.getOperand();
        
        // 为结果分配目标寄存器
        Armv8Reg destReg = Armv8Reg.allocateReg();
        value2Reg.put(ins, destReg);
        
        // 处理操作数
        Armv8Operand srcOp;
        if (operand instanceof ConstantInt) {
            // 对于常量，创建立即数操作数
            long value = ((ConstantInt) operand).getValue();
            srcOp = new Armv8Imm((int) value);  // 显式转换为int
        } else {
            // 对于变量，使用寄存器
            if (!value2Reg.containsKey(operand)) {
                if (operand instanceof Instruction) {
                    parseInstruction((Instruction) operand, true);
                } else {
                    Armv8Reg srcReg = Armv8Reg.allocateReg();
                    value2Reg.put(operand, srcReg);
                }
            }
            srcOp = value2Reg.get(operand);
        }
        
        // 根据操作码生成对应的ARM指令
        boolean is32Bit = true; // 默认使用32位操作
        
        switch (opCode) {
            case NEG: // 取负操作
                if (srcOp instanceof Armv8Imm) {
                    // 如果是立即数，可以直接计算负值
                    int value = ((Armv8Imm) srcOp).getValue();
                    Armv8Move moveInst = new Armv8Move(destReg, new Armv8Imm(-value), is32Bit);
                    addInstr(moveInst, insList, predefine);
                } else {
                    // 如果是寄存器，使用NEG指令
                    Armv8Reg srcReg = (Armv8Reg) srcOp;
                    Armv8Unary negInst = new Armv8Unary(srcReg, destReg, Armv8Unary.Armv8UnaryType.neg, is32Bit);
                    addInstr(negInst, insList, predefine);
                }
                break;
                
            default:
                System.err.println("不支持的一元操作: " + opCode);
                break;
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void addInstr(Armv8Instruction ins, ArrayList<Armv8Instruction> insList, boolean predefine) {
        if (predefine) {
            insList.add(ins);
        } else {
            curArmv8Block.addArmv8Instruction(ins);
        }
    }

    public void dump() {
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter("armv8_backend.s"));
            out.write(armv8Module.toString());
            out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Armv8Module getArmv8Module() {
        return armv8Module;
    }
} 
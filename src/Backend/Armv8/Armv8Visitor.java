package Backend.Armv8;

import Backend.Armv8.Instruction.*;
import Backend.Armv8.Operand.*;
import Backend.Armv8.Structure.*;
import Backend.Armv8.tools.*;
import IR.Module;
import IR.OpCode;
import IR.Type.*;
import IR.Value.*;
import IR.Value.Instructions.*;
import IR.Visitor.IRVisitor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class Armv8Visitor {
    public Module irModule;
    public Armv8Module armv8Module = new Armv8Module();
    // 使用多态性，这样可以存储Armv8Label及其子类
    private static final LinkedHashMap<Value, Armv8Label> LabelList = new LinkedHashMap<>();
    private static final LinkedHashMap<Value, Armv8Reg> RegList = new LinkedHashMap<>();
    private static final LinkedHashMap<Value, Long> ptrList = new LinkedHashMap<>();
    private Long stackPos = 0L;
    private Armv8Block curArmv8Block = null;
    private Armv8Function curArmv8Function = null;
    private final LinkedHashMap<Instruction, ArrayList<Armv8Instruction>> predefines = new LinkedHashMap<>();

    public Armv8Visitor(Module irModule) {
        this.irModule = irModule;
    }

    public String removeLeadingAt(String name) {
        if (name.startsWith("@")) {
            return name.substring(1);
        }
        return name;
    }

    public static LinkedHashMap<Value, Armv8Reg> getRegList() {
        return RegList;
    }
    
    public static LinkedHashMap<Value, Long> getPtrList() {
        return ptrList;
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
        ArrayList<Number> initialValues = new ArrayList<>();
        
        // 获取类型的元素类型
        Type elementType = globalVariable.getElementType();
        int byteSize = elementType.getSize();
        boolean isFloat = elementType instanceof FloatType;
        
        if (globalVariable.isArray()) {
            byteSize = globalVariable.getArraySize() * elementType.getSize();
            // 处理数组初始化
            if (globalVariable.isZeroInitialized()) {
                // 零初始化的数组
                initialValues = null;
            } else if (globalVariable.hasInitializer() && globalVariable.getArrayValues() != null) {
                // 带有初始化列表的数组
                List<Value> arrayValues = globalVariable.getArrayValues();
                for (Value val : arrayValues) {
                    if (val instanceof ConstantInt) {
                        // 处理整型值
                        initialValues.add(((ConstantInt) val).getValue());
                    } else if (val instanceof ConstantFloat) {
                        // 处理浮点值
                        initialValues.add(((ConstantFloat) val).getValue());
                    }
                }
            }
        } else {
            // 处理单个值的初始化
            if (globalVariable.hasInitializer()) {
                Value initializer = globalVariable.getInitializer();
                if (initializer instanceof ConstantInt) {
                    initialValues.add(((ConstantInt) initializer).getValue());
                } else if (initializer instanceof ConstantFloat) {
                    initialValues.add(((ConstantFloat) initializer).getValue());
                }
            } else {
                // 未初始化的变量
                initialValues = null;
            }
        }
        
        // 创建ARM全局变量并添加到模块
        Armv8GlobalVariable armv8GlobalVar = new Armv8GlobalVariable(varName, initialValues, byteSize, isFloat);
        
        // 根据是否有初始值决定放在data段还是bss段
        if (initialValues == null || initialValues.isEmpty()) {
            armv8Module.addBssVar(armv8GlobalVar); // 未初始化的变量放在bss段
        } else {
            armv8Module.addDataVar(armv8GlobalVar); // 初始化的变量放在data段
        }
        
        LabelList.put(globalVariable, armv8GlobalVar);
    }

    private void generateFunction(Function function) {
        if (function.isExternal()) {
            return; // 跳过外部函数
        }

        String functionName = removeLeadingAt(function.getName());
        curArmv8Function = new Armv8Function(functionName, function);
        stackPos = 0L;
        armv8Module.addFunction(functionName, curArmv8Function);
        Armv8VirReg.resetCounter();

        // 将基本块映射到ARM块
        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            String blockName = removeLeadingAt(functionName) + "_" + basicBlock.getName();
            Armv8Block armv8Block = new Armv8Block(blockName);
            LabelList.put(basicBlock, armv8Block);
            curArmv8Function.addBlock(armv8Block);
        }
        
        // 获取函数参数，确保它们被保存和正确处理
        List<Argument> arguments = function.getArguments();
        for (Argument arg : arguments) {
            // 确认参数已被正确记录，无需其他处理
            if (!RegList.containsKey(arg)) {
                Armv8Reg argReg = curArmv8Function.getRegArg(arg);
                if (argReg != null) {
                    RegList.put(arg, argReg); // 确保参数有正确的寄存器映射
                }
            }
        }
        
        // 生成函数体的指令
        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            curArmv8Block = (Armv8Block) LabelList.get(basicBlock);
            generateBasicBlock(basicBlock);
        }
        
        // 进行寄存器分配
        System.out.println("\n===== 开始对函数 " + functionName + " 进行寄存器分配 =====");
        RegisterAllocator allocator = new RegisterAllocator(curArmv8Function);
        allocator.allocateRegisters();
        
        // 应用寄存器分配后的优化
        PostRegisterOptimizer optimizer = new PostRegisterOptimizer(curArmv8Function);
        optimizer.optimize();
        
        System.out.println("===== 函数 " + functionName + " 寄存器分配完成 =====\n");
    }

    private void generateBasicBlock(BasicBlock basicBlock) {
        // 检查基本块是否为空
        if (basicBlock.getInstructions().isEmpty()) {
            return;
        }
        
        // 仅处理实际的指令，不添加额外的地址计算等指令
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
        } else if (ins instanceof UnaryInstruction) {
            parseUnaryInst((UnaryInstruction) ins, predefine);
        } else if (ins instanceof MoveInstruction) {
            parseMoveInst((MoveInstruction) ins, predefine);
        } else if (ins instanceof PhiInstruction) {
            parsePhiInst((PhiInstruction) ins, predefine);
        } else {
            System.err.println("错误: 不支持的指令: " + ins.getClass().getName());
        }
    }

    // 指令解析方法将在这里实现
    private void parseAlloc(AllocaInstruction ins, boolean predefine) {
        // 检查函数是否真的需要局部变量
        // 对于简单函数（如func函数），可能不需要任何栈空间
        if (ins.getType() == null) {
            System.err.println("警告: AllocaInstruction 没有类型信息: " + ins);
            return;
        }
        
        long size;
        // 判断是数组分配还是单一变量分配
        if (ins.isArrayAllocation()) {
            size = 8 * ins.getArraySize();
        } else {
            // 单一变量
            size = 8;
        }
        
        // 在函数栈上分配空间（如果真正需要）
        curArmv8Function.addStack(ins, size);
        
        // 记录分配的偏移量，用于后续访问
        ptrList.put(ins, stackPos);
        
        // 分配虚拟寄存器用于存储地址，注意不要影响参数寄存器
        if (!predefine) {
            // 创建新的虚拟寄存器，不使用物理寄存器x0-x7
            Armv8VirReg allocaReg = new Armv8VirReg(false);
            RegList.put(ins, allocaReg);
            
            // 计算局部变量地址：SP + 偏移量
            Armv8Binary addInst = new Armv8Binary(
                allocaReg,  // 必须使用虚拟寄存器，确保寄存器分配后不会是x0!
                Armv8CPUReg.getArmv8SpReg(), 
                new Armv8Imm(stackPos), 
                Armv8Binary.Armv8BinaryType.add
            );
            
            // 将指令添加到当前基本块
            curArmv8Block.addArmv8Instruction(addInst);
        }
        
        // 更新栈指针偏移
        stackPos += size;
    }

    private void parseBinaryInst(BinaryInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取IR指令的操作码和操作数
        OpCode opCode = ins.getOpCode();
        Value leftOperand = ins.getLeft();
        Value rightOperand = ins.getRight();
        
        // 检查是否是浮点操作
        boolean isFloatOperation = leftOperand.getType() instanceof FloatType || 
                                 rightOperand.getType() instanceof FloatType;
 
        // 获取或分配寄存器
        Armv8Reg destReg;
        if (isFloatOperation) {
            destReg = new Armv8VirReg(true);
        } else {
            destReg = new Armv8VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 处理左操作数
        Armv8Operand leftOp = processOperand(leftOperand, insList, predefine, isFloatOperation, opCode == OpCode.MUL || opCode == OpCode.DIV);
        
        // 处理右操作数
        Armv8Operand rightOp = processOperand(rightOperand, insList, predefine, isFloatOperation, opCode == OpCode.MUL || opCode == OpCode.DIV);
        
        // 创建操作数列表
        ArrayList<Armv8Operand> operands = new ArrayList<>();
        operands.add(leftOp);
        operands.add(rightOp);
        
        // 后面是根据操作码生成指令的代码，保持不变
        // 根据操作码和操作数类型生成对应的ARM指令
        if (isFloatOperation) {
            // 浮点运算处理
            switch (opCode) {
                case FADD:
                    // 浮点加法
                    Armv8FBinary faddInst = new Armv8FBinary(operands, destReg, Armv8FBinary.Armv8FBinaryType.fadd);
                    addInstr(faddInst, insList, predefine);
                    break;
                case FSUB:
                    // 浮点减法
                    Armv8FBinary fsubInst = new Armv8FBinary(operands, destReg, Armv8FBinary.Armv8FBinaryType.fsub);
                    addInstr(fsubInst, insList, predefine);
                    break;
                case FMUL:
                    // 浮点乘法
                    Armv8FBinary fmulInst = new Armv8FBinary(operands, destReg, Armv8FBinary.Armv8FBinaryType.fmul);
                    addInstr(fmulInst, insList, predefine);
                    break;
                case FDIV:
                    // 浮点除法
                    Armv8FBinary fdivInst = new Armv8FBinary(operands, destReg, Armv8FBinary.Armv8FBinaryType.fdiv);
                    addInstr(fdivInst, insList, predefine);
                    break;
                default:
                    System.err.println("不支持的浮点二元操作: " + opCode);
                    break;
            }
        } else {
            // 整数运算处理
            // 处理整数操作
            Armv8Binary.Armv8BinaryType binaryType;
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
                // 其他操作类型
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
                case REM:
                    // 处理余数操作（需要特殊处理）
                    handleRemOperation(leftOp, rightOp, destReg, insList, predefine);
                    if (predefine) {
                        predefines.put(ins, insList);
                    }
                    return; // 提前返回，因为REM已经在handleRemOperation中处理
                default:
                    System.err.println("不支持的整数二元操作: " + opCode);
                    if (predefine) {
                        predefines.put(ins, insList);
                    }
                    return;
            }
            
            // 创建二元指令
            Armv8Binary binaryInst = new Armv8Binary(operands, destReg, binaryType);
            addInstr(binaryInst, insList, predefine);
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }
    
    /**
     * 处理操作数，返回对应的ARM操作数
     */
    private Armv8Operand processOperand(Value operand, ArrayList<Armv8Instruction> insList, 
                                       boolean predefine, boolean isFloat, boolean requiresReg) {
        // 处理常量
        if (operand instanceof ConstantInt) {
            long value = ((ConstantInt) operand).getValue();
            if (requiresReg) {
                // 如果需要寄存器形式（如乘除运算的操作数），加载到寄存器
                Armv8Reg tempReg = new Armv8VirReg(false);
                Armv8Move moveInst = new Armv8Move(tempReg, new Armv8Imm(value), true);
                addInstr(moveInst, insList, predefine);
                return tempReg;
            } else {
                // 否则使用立即数形式
                return new Armv8Imm(value);
            }
        } 
        else if (operand instanceof ConstantFloat) {
            // 浮点常量总是需要加载到寄存器
            double floatValue = ((ConstantFloat) operand).getValue();
            Armv8VirReg fReg = new Armv8VirReg(true);
            loadFloatConstant(fReg, floatValue, insList, predefine);
            return fReg;
        } 
        else if (operand instanceof Argument) {
            // 处理函数参数 - 使用辅助方法确保正确获取参数值
            Argument arg = (Argument) operand;
            Armv8Reg paramReg = ensureArgumentRegister(arg, insList, predefine);
            if (paramReg != null) {
                return paramReg;
            } else {
                System.err.println("警告: 无法获取参数 " + arg);
                return new Armv8Imm(0); // 返回默认值
            }
        } 
        else if (operand instanceof Instruction) {
            // 处理其他指令的结果
            if (!RegList.containsKey(operand)) {
                parseInstruction((Instruction) operand, true);
            }
            if (RegList.containsKey(operand)) {
                return RegList.get(operand);
            } else {
                System.err.println("警告: 无法获取指令结果寄存器: " + operand);
                return new Armv8Imm(0);
            }
        } 
        else if (RegList.containsKey(operand)) {
            // 已经有对应的寄存器，直接返回
            return RegList.get(operand);
        } 
        else {
            // 其他情况，返回0
            System.err.println("警告: 无法处理的操作数: " + operand);
            return new Armv8Imm(0);
        }
    }
    
    /**
     * 处理余数运算 (a % b = a - (a / b) * b)
     */
    private void handleRemOperation(Armv8Operand leftOp, Armv8Operand rightOp, Armv8Reg destReg,
                                  ArrayList<Armv8Instruction> insList, boolean predefine) {
        // 确保右操作数是寄存器
        Armv8Reg rightReg;
        if (rightOp instanceof Armv8Imm) {
            rightReg = new Armv8VirReg(false);
            Armv8Move moveInst = new Armv8Move(rightReg, rightOp, true);
            addInstr(moveInst, insList, predefine);
        } else {
            rightReg = (Armv8Reg) rightOp;
        }
        
        // 1. 除法运算: destReg = leftOp / rightReg
        ArrayList<Armv8Operand> divOperands = new ArrayList<>();
        divOperands.add(leftOp);
        divOperands.add(rightReg);
        Armv8Binary divInst = new Armv8Binary(divOperands, destReg, Armv8Binary.Armv8BinaryType.sdiv);
        addInstr(divInst, insList, predefine);
        
        // 2. 乘法运算: tempReg = destReg * rightReg
        Armv8VirReg tempReg = new Armv8VirReg(false);
        ArrayList<Armv8Operand> mulOperands = new ArrayList<>();
        mulOperands.add(destReg);
        mulOperands.add(rightReg);
        Armv8Binary mulInst = new Armv8Binary(mulOperands, tempReg, Armv8Binary.Armv8BinaryType.mul);
        addInstr(mulInst, insList, predefine);
        
        // 3. 减法运算: destReg = leftOp - tempReg
        ArrayList<Armv8Operand> subOperands = new ArrayList<>();
        subOperands.add(leftOp);
        subOperands.add(tempReg);
        Armv8Binary subInst = new Armv8Binary(subOperands, destReg, Armv8Binary.Armv8BinaryType.sub);
        addInstr(subInst, insList, predefine);
    }

    private void parseBrInst(BranchInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        if (ins.isUnconditional()) {
            // 处理无条件跳转
            BasicBlock targetBlock = ins.getTrueBlock();
            Armv8Block armTarget = (Armv8Block) LabelList.get(targetBlock);
            
            // 创建无条件跳转指令
            Armv8Jump jumpInst = new Armv8Jump(armTarget, curArmv8Block);
            addInstr(jumpInst, insList, predefine);
        } else {
            // 处理条件跳转
            Value condition = ins.getCondition();
            BasicBlock trueBlock = ins.getTrueBlock();
            BasicBlock falseBlock = ins.getFalseBlock();
            
            Armv8Block armTrueBlock = (Armv8Block) LabelList.get(trueBlock);
            Armv8Block armFalseBlock = (Armv8Block) LabelList.get(falseBlock);
            
            // 条件指令需要首先处理条件
            if (condition instanceof CompareInstruction) {
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
                    // ARM要求CMP的第一个操作数必须是寄存器
                    Armv8Reg leftReg = new Armv8VirReg(false);
                    Armv8Move moveInst = new Armv8Move(leftReg, new Armv8Imm(value), true);
                    addInstr(moveInst, insList, predefine);
                    leftOp = leftReg;
                } else {
                    leftOp = RegList.get(left);
                }
                
                // 处理右操作数
                if (right instanceof ConstantInt) {
                    long value = ((ConstantInt) right).getValue();
                    rightOp = new Armv8Imm(value);
                } else {
                    rightOp = RegList.get(right);
                }
                
                // 创建比较指令
                Armv8Compare.CmpType cmpType = Armv8Compare.CmpType.cmp;
                if (!cmpIns.isFloatCompare() && rightOp instanceof Armv8Imm) {
                    long value = ((Armv8Imm) rightOp).getValue();
                    if (value < 0) {
                        // 使用CMN指令，比较负值（相当于CMP reg, -imm）
                        cmpType = Armv8Compare.CmpType.cmn;
                        // 将立即数改为正值
                        rightOp = new Armv8Imm(-value);
                    }
                }
                
                Armv8Compare compareInst = new Armv8Compare(leftOp, rightOp, cmpType);
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
                    condOp = RegList.get(condition);
                    
                    // PHI消除后的修复：如果直接查找失败，按名称查找
                    if (condOp == null) {
                        String condName = condition.getName();
                        for (Map.Entry<Value, Armv8Reg> entry : RegList.entrySet()) {
                            if (entry.getKey().getName().equals(condName)) {
                                condOp = entry.getValue();
                                break;
                            }
                        }
                    }
                    
                    // 最终错误检查
                    if (condOp == null) {
                        System.err.println("错误: 无法找到条件变量的寄存器映射: " + condition.getName());
                        System.err.println("条件对象类型: " + condition.getClass().getName());
                        System.err.println("可用的寄存器映射:");
                        for (Map.Entry<Value, Armv8Reg> entry : RegList.entrySet()) {
                            System.err.println("  " + entry.getKey().getName() + " -> " + entry.getValue());
                        }
                        // 创建一个临时寄存器作为fallback
                        condOp = new Armv8VirReg(false);
                        System.err.println("使用临时寄存器作为fallback");
                    }
                    
                    // 使用CBZ指令测试条件是否为0
                    
                    // 如果为0则跳转到false块
                    Armv8Cbz cbzInst = new Armv8Cbz((Armv8Reg) condOp, armFalseBlock);
                    cbzInst.setPredSucc(curArmv8Block);
                    addInstr(cbzInst, insList, predefine);
                    
                    // 否则跳转到false块
                    Armv8Jump jumpFalseInst = new Armv8Jump(armFalseBlock, curArmv8Block);
                    addInstr(jumpFalseInst, insList, predefine);
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
        
        // ARMv8调用约定：前8个整型参数用x0-x7，前8个浮点参数用v0-v7，其余参数通过栈传递
        int intArgCount = 0;     // 整型参数计数器
        int floatArgCount = 0;   // 浮点参数计数器
        long stackOffset = 0;     // 栈参数偏移量
        ArrayList<Value> stackArgList = new ArrayList<>();
        for (int i = 0; i < argCount; i++) {
            Value arg = arguments.get(i);
            boolean isFloat = arg.getType() instanceof FloatType;
            
            // 检查是否需要使用栈传递
            boolean useStack = false;
            if (isFloat) {
                useStack = floatArgCount >= 8; // 超过8个浮点参数使用栈
                floatArgCount++;
            } else {
                useStack = intArgCount >= 8;   // 超过8个整型参数使用栈
                intArgCount++;
            }
            
            if (!useStack) {           
                Armv8Reg argReg;
                if (isFloat) {
                    // 浮点参数使用v0-v7寄存器，使用floatArgCount-1是因为已经自增了
                    argReg = Armv8FPUReg.getArmv8FArgReg(floatArgCount-1);
                } else {
                    // 整数参数使用x0-x7寄存器，使用intArgCount-1是因为已经自增了
                    argReg = Armv8CPUReg.getArmv8ArgReg(intArgCount-1);
                }
                 // 处理参数值
                if (arg instanceof ConstantInt) {
                    // 对于常量整数，直接加载到对应的参数寄存器
                    long value = ((ConstantInt) arg).getValue();
                    Armv8Imm imm = new Armv8Imm(value);
                    
                    // 生成加载立即数到寄存器指令
                    Armv8Move moveInst = new Armv8Move(argReg, imm, true);
                    addInstr(moveInst, insList, predefine);
                } else if (arg instanceof ConstantFloat) {
                    // 对于浮点常量，先加载到虚拟寄存器，再移动到参数寄存器
                    double floatValue = ((ConstantFloat) arg).getValue();
                    
                    // 为浮点常量分配虚拟寄存器
                    Armv8VirReg fReg = new Armv8VirReg(true);
                    
                    // 加载浮点常量到虚拟寄存器
                    loadFloatConstant(fReg, floatValue, insList, predefine);
                    
                    // 将虚拟寄存器移动到参数寄存器
                    Armv8Fmov fmovInst = new Armv8Fmov(argReg, fReg);
                    addInstr(fmovInst, insList, predefine);
                } else {
                    // 变量参数，检查是否已经有关联的寄存器
                    if (!RegList.containsKey(arg)) {
                        // 如果没有关联的寄存器，需要创建一个
                        if (arg instanceof Instruction) {
                            // 递归处理指令
                            parseInstruction((Instruction) arg, true);
                        } else if (arg instanceof GlobalVariable) {
                            // 处理全局变量参数
                            GlobalVariable globalVar = (GlobalVariable) arg;
                            String globalName = removeLeadingAt(globalVar.getName());
                            Armv8Label label = new Armv8Label(globalName);
                            
                            // 为全局变量分配一个临时寄存器
                            Armv8VirReg tempReg = new Armv8VirReg(false);
                            Armv8Adr adrInst = new Armv8Adr(tempReg, label);
                            addInstr(adrInst, insList, predefine);
                            
                            // 加载全局变量的值
                            Armv8VirReg valueReg;
                            if (arg.getType() instanceof FloatType) {
                                valueReg = new Armv8VirReg(true);
                            } else {
                                valueReg = new Armv8VirReg(false);
                            }
                            
                            Armv8Load loadInst = new Armv8Load(tempReg, new Armv8Imm(0), valueReg);
                            addInstr(loadInst, insList, predefine);
                            
                            // 关联全局变量与其值寄存器
                            RegList.put(arg, valueReg);
                        } else if (arg.getType() instanceof FloatType) {
                            // 为浮点类型分配寄存器
                            Armv8VirReg floatReg = new Armv8VirReg(true);
                            RegList.put(arg, floatReg);
                        } else {
                            // 为整数类型分配寄存器
                            Armv8VirReg intReg = new Armv8VirReg(false);
                            RegList.put(arg, intReg);
                        }
                    }
                    
                    // 获取参数的寄存器
                    Armv8Reg argValueReg = RegList.get(arg);
                    if (argValueReg != null) {
                        Armv8Move moveInst = new Armv8Move(argReg, argValueReg, false);
                        addInstr(moveInst, insList, predefine);
                    } else {
                        // 如果仍然没有寄存器，使用零寄存器
                        System.err.println("警告: 参数 " + arg + " 没有关联的寄存器，使用零寄存器");
                        Armv8Move moveInst = new Armv8Move(argReg, Armv8CPUReg.getZeroReg(), false);
                        addInstr(moveInst, insList, predefine);
                    }
                }
                curArmv8Function.addRegArg(arg, argReg);
                RegList.put(arg, argReg);
            } else {
                // 使用栈传递参数
                stackOffset += 8;
                stackArgList.add(arg);
                curArmv8Function.addStackArg(arg, stackOffset);
            }
        }
        // 如果栈偏移量大于0，则需要减去栈偏移量
        if (stackOffset > 0) {
            Armv8Binary subInst = new Armv8Binary(Armv8CPUReg.getArmv8SpReg(), Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(stackOffset), Armv8Binary.Armv8BinaryType.sub);
            addInstr(subInst, insList, predefine);
        }


        if (stackArgList.size() > 0) {
            int tempStackOffset = 0;
            for (Value arg : stackArgList) {
                if (arg instanceof ConstantInt) {
                    // 对于常量，先加载到临时寄存器再存入栈
                    long value = ((ConstantInt) arg).getValue();
                    Armv8VirReg tempReg = new Armv8VirReg(false);
                    Armv8Imm imm = new Armv8Imm(value);
                    
                    // 生成加载立即数到临时寄存器指令
                    Armv8Move moveInst = new Armv8Move(tempReg, imm, true);
                    addInstr(moveInst, insList, predefine);
                    
                    // 将临时寄存器值存储到栈上
                    Armv8CPUReg spReg = Armv8CPUReg.getArmv8SpReg();
                    Armv8Store storeInst = new Armv8Store(tempReg, spReg, new Armv8Imm(tempStackOffset));
                    addInstr(storeInst, insList, predefine);
                } else if (arg instanceof ConstantFloat) {
                    // 处理浮点常量
                    double floatValue = ((ConstantFloat) arg).getValue();
                    
                    // 为浮点常量分配虚拟寄存器
                    Armv8VirReg fReg = new Armv8VirReg(true);
                    
                    // 加载浮点常量到虚拟寄存器
                    loadFloatConstant(fReg, floatValue, insList, predefine);
                    
                    // 将虚拟寄存器值存储到栈上
                    Armv8CPUReg spReg = Armv8CPUReg.getArmv8SpReg();
                    Armv8Store storeInst = new Armv8Store(fReg, spReg, new Armv8Imm(tempStackOffset));
                    addInstr(storeInst, insList, predefine);
                } else {
                                            // 变量参数，检查是否已有寄存器
                    if (!RegList.containsKey(arg)) {
                        // 如果没有关联的寄存器，需要创建一个
                        if (arg instanceof Instruction) {
                            // 递归处理指令
                            parseInstruction((Instruction) arg, true);
                        } else if (arg instanceof GlobalVariable) {
                            // 处理全局变量参数
                            GlobalVariable globalVar = (GlobalVariable) arg;
                            String globalName = removeLeadingAt(globalVar.getName());
                            Armv8Label label = new Armv8Label(globalName);
                            
                            // 为全局变量分配一个临时寄存器
                            Armv8VirReg tempReg = new Armv8VirReg(false);
                            Armv8Adr adrInst = new Armv8Adr(tempReg, label);
                            addInstr(adrInst, insList, predefine);
                            
                            // 加载全局变量的值
                            Armv8VirReg valueReg;
                            if (arg.getType() instanceof FloatType) {
                                valueReg = new Armv8VirReg(true);
                            } else {
                                valueReg = new Armv8VirReg(false);
                            }
                            
                            Armv8Load loadInst = new Armv8Load(tempReg, new Armv8Imm(0), valueReg);
                            addInstr(loadInst, insList, predefine);
                            
                            // 关联全局变量与其值寄存器
                            RegList.put(arg, valueReg);
                        } else if (arg.getType() instanceof FloatType) {
                            // 为浮点类型分配寄存器
                            Armv8VirReg floatReg = new Armv8VirReg(true);
                            RegList.put(arg, floatReg);
                        } else {
                            // 为整数类型分配寄存器
                            Armv8VirReg intReg = new Armv8VirReg(false);
                            RegList.put(arg, intReg);
                        }
                    }
                    
                    // 获取参数的寄存器
                    Armv8Reg argValueReg = RegList.get(arg);
                    if (argValueReg != null) {
                        // 将变量寄存器的值存储到栈上
                        Armv8CPUReg spReg = Armv8CPUReg.getArmv8SpReg();
                        // 使用通用存储指令
                        Armv8Store storeInst = new Armv8Store(argValueReg, spReg, new Armv8Imm(tempStackOffset));
                        addInstr(storeInst, insList, predefine);
                    } else {
                        // 如果仍然没有寄存器，使用零寄存器
                        System.err.println("警告: 栈参数 " + arg + " 没有关联的寄存器，使用零寄存器");
                        Armv8VirReg tempReg = new Armv8VirReg(false);
                        Armv8Move moveInst = new Armv8Move(tempReg, Armv8CPUReg.getZeroReg(), false);
                        addInstr(moveInst, insList, predefine);
                        
                        // 将临时寄存器值存储到栈上
                        Armv8CPUReg spReg = Armv8CPUReg.getArmv8SpReg();
                        Armv8Store storeInst = new Armv8Store(tempReg, spReg, new Armv8Imm(tempStackOffset));
                        addInstr(storeInst, insList, predefine);
                    }
                }
                tempStackOffset += 8;
            }
        }
        // 创建调用标签
        Armv8Label functionLabel = new Armv8Label(functionName);
        
        // 生成调  用指令
        Armv8Call callInst = new Armv8Call(functionLabel);
        
        // 记录使用的寄存器（用于调用者保存寄存器的保存和恢复）
        // for (Value value : RegList.keySet()) {
        //     Armv8Reg reg = RegList.get(value);
        //     if (reg instanceof Armv8CPUReg && ((Armv8CPUReg) reg).canBeReorder()) {
        //         callInst.addUsedReg(reg);
        //     }
        // }
        
        addInstr(callInst, insList, predefine);
        
        // 处理返回值
        if (!ins.isVoidCall()) {
            Armv8Reg returnReg, resultReg;
            if (ins.getCallee().getReturnType().isIntegerType()) {
                returnReg = Armv8CPUReg.getArmv8CPURetValueReg();
                resultReg = new Armv8VirReg(false);
            } else {
                returnReg = Armv8FPUReg.getArmv8FPURetValueReg();
                resultReg = new Armv8VirReg(true);
            }
            
            // 为调用指令分配一个寄存器存储返回值
            RegList.put(ins, resultReg);
            
            // 将返回值从x0/v0移动到结果寄存器
            if (returnReg != null) {  // 确保返回寄存器不为空
                Armv8Move moveReturnInst = new Armv8Move(resultReg, returnReg, false);
                addInstr(moveReturnInst, insList, predefine);
            } else {
                System.err.println("错误: 返回寄存器为空，跳过移动返回值");
            }
        }

        if (stackOffset > 0) {
            Armv8Binary addInst = new Armv8Binary(Armv8CPUReg.getArmv8SpReg(), Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(stackOffset), Armv8Binary.Armv8BinaryType.add);
            addInstr(addInst, insList, predefine);
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
            Armv8Imm imm = new Armv8Imm(value);
            srcReg = new Armv8VirReg(false);
            
            // 创建一个移动指令将常量加载到寄存器
            Armv8Move moveInst = new Armv8Move(srcReg, imm, false);
            addInstr(moveInst, insList, predefine);
        } else if (source instanceof ConstantFloat) {
            double value = ((ConstantFloat) source).getValue();
            Armv8VirReg fReg = new Armv8VirReg(true);
            loadFloatConstant(fReg, value, insList, predefine);
            srcReg = fReg;
        } else if (RegList.containsKey(source)) {
            srcReg = RegList.get(source);
        } else {
            srcReg = null;
            System.out.println("error no virReg: " + source);
        }
        
        // 分配目标寄存器
        Armv8Reg destReg;
        if (targetType.isFloatType()) {
            // 目标是浮点类型，使用浮点寄存器
            destReg = new Armv8VirReg(true);
        } else {
            // 目标是整数类型，使用通用寄存器
            destReg = new Armv8VirReg(false);
        }
        RegList.put(ins, destReg);
        
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
            
            Armv8Imm maskImm = new Armv8Imm(mask);
            andOps.add(maskImm);
            
            Armv8Binary andInst = new Armv8Binary(andOps, destReg, Armv8Binary.Armv8BinaryType.and);
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
                Armv8Move zextInst = new Armv8Move(destReg, srcReg, false); // 使用64位模式
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
        // 判断是否是浮点数类型
        boolean isFloat = loadedType instanceof FloatType;
        
        // 为结果分配目标寄存器
        Armv8Reg destReg;
        if (isFloat) {
            // 浮点数应该使用浮点寄存器
            destReg = new Armv8VirReg(true);
        } else {
            // 整数使用通用寄存器
            destReg = new Armv8VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 处理指针操作数
        Armv8Reg baseReg = null;
        Armv8Operand offsetOp = new Armv8Imm(0); // 默认偏移量为0
        
        // 处理各种类型的指针
        if (pointer instanceof GetElementPtrInstruction) {
            // 如果是GEP指令，检查是否已经有关联的寄存器
            if (!RegList.containsKey(pointer)) {
                // 如果没有，解析GEP指令
                System.out.println("error: GEPinst not parse!");
                parsePtrInst((GetElementPtrInstruction) pointer, true);
            }
            
            if (RegList.containsKey(pointer)) {
                // 使用GEP结果作为基地址
                baseReg = RegList.get(pointer);
            } 
            // else if (ptrList.containsKey(pointer)) {
            //     // 使用栈指针加上偏移量
            //     baseReg = Armv8CPUReg.getArmv8SpReg();
            //     offsetOp = new Armv8Imm(ptrList.get(pointer).intValue());
            // } 
            else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            // 对于全局变量，使用标签
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            Armv8Label label = new Armv8Label(globalName);
            
            // 为全局变量地址分配寄存器
            baseReg = new Armv8VirReg(false);
            
            // 创建加载地址指令
            Armv8Adr adrInst = new Armv8Adr(baseReg, label);
            addInstr(adrInst, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果是局部变量分配指令
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction) pointer, true);
            }
            
            // 使用栈指针加上偏移量
            baseReg = Armv8CPUReg.getArmv8SpReg();
            offsetOp = new Armv8Imm(ptrList.get(pointer));
        } else if (pointer instanceof Argument) {
            // 处理函数参数，为其分配寄存器
            Argument arg = (Argument) pointer;
            if (curArmv8Function.getRegArg(arg) != null) {
                baseReg = curArmv8Function.getRegArg(arg);
            } else if (curArmv8Function.getStackArg(arg) != null) {
                baseReg = new Armv8VirReg(false);
                long stackParamOffset = curArmv8Function.getStackArg(arg); // 前8个在寄存器中
                
                // 创建加载指令：从FP+偏移量加载到临时寄存器
                Armv8Load loadParamInst = new Armv8Load(Armv8CPUReg.getArmv8FPReg(), 
                    new Armv8Imm(stackParamOffset), baseReg);
                addInstr(loadParamInst, insList, predefine);
            }
            
            RegList.put(pointer, baseReg);
        } else {
            // 其他类型的指针，尝试获取关联的寄存器
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                } else if (pointer.getType() instanceof PointerType) {
                    // 处理指针类型
                    Armv8Reg ptrReg = new Armv8VirReg(false);
                    RegList.put(pointer, ptrReg);
                } else if (pointer.getType() instanceof FloatType) {
                    // 处理浮点类型
                    Armv8Reg floatReg = new Armv8VirReg(true);
                    RegList.put(pointer, floatReg);
                } else if (pointer.getType() instanceof IntegerType) {
                    // 处理整数类型
                    Armv8Reg intReg = new Armv8VirReg(false);
                    RegList.put(pointer, intReg);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = RegList.get(pointer);
        }
        
        // 确保baseReg一定有值
        if (baseReg == null) {
            throw new RuntimeException("无法确定指针的基地址寄存器: " + pointer);
        }
        
        // 创建加载指令
        Armv8Load loadInst = new Armv8Load(baseReg, offsetOp, destReg);
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
        Armv8Reg destReg = new Armv8VirReg(false);
        RegList.put(ins, destReg);
        
        // 处理基地址指针
        Armv8Reg baseReg = null;

        if (pointer instanceof GlobalVariable) {
            // 如果基地址是全局变量
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            Armv8Label label = new Armv8Label(globalName);
            
            // 加载全局变量地址
            baseReg = new Armv8VirReg(false);
            Armv8Adr adrInst = new Armv8Adr(baseReg, label);
            addInstr(adrInst, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果基地址是局部变量
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction) pointer, true);
            }
            
            // 计算局部变量地址：SP + 偏移
            baseReg = new Armv8VirReg(false);
            
            Armv8Binary addInst = new Armv8Binary(baseReg, Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(ptrList.get(pointer)), Armv8Binary.Armv8BinaryType.add);
            addInstr(addInst, insList, predefine);
        } else if (pointer instanceof Argument) {
            // 处理函数参数，为其分配寄存器
            Argument arg = (Argument) pointer;
            if (curArmv8Function.getRegArg(arg) != null) {
                baseReg = curArmv8Function.getRegArg(arg);
            } else if (curArmv8Function.getStackArg(arg) != null) {
                baseReg = new Armv8VirReg(false);
                long stackParamOffset = curArmv8Function.getStackArg(arg); // 前8个在寄存器中
                
                // 创建加载指令：从FP+偏移量加载到临时寄存器
                Armv8Load loadParamInst = new Armv8Load(Armv8CPUReg.getArmv8FPReg(), 
                    new Armv8Imm(stackParamOffset), baseReg);
                addInstr(loadParamInst, insList, predefine);
            }
            RegList.put(pointer, baseReg);
        } else {
            // 其他类型的指针（如其他指令的结果）
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                } else if (pointer.getType() instanceof PointerType) {
                    // 处理指针类型
                    Armv8Reg ptrReg = new Armv8VirReg(false);
                    RegList.put(pointer, ptrReg);
                } else if (pointer.getType() instanceof FloatType) {
                    // 处理浮点类型
                    Armv8Reg floatReg = new Armv8VirReg(true);
                    RegList.put(pointer, floatReg);
                } else if (pointer.getType() instanceof IntegerType) {
                    // 处理整数类型
                    Armv8Reg intReg = new Armv8VirReg(false);
                    RegList.put(pointer, intReg);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = RegList.get(pointer);
        }
        
        // 确保baseReg一定有值
        if (baseReg == null) {
            throw new RuntimeException("无法确定指针的基地址寄存器: " + pointer);
        }
        
        // 将基地址拷贝到目标寄存器，作为起始地址
        Armv8Move moveInst = new Armv8Move(destReg, baseReg, false);
        addInstr(moveInst, insList, predefine);
        
        // 处理数组索引 - 对于数组访问，不应该跳过任何索引
        // 处理多维数组索引
        if (indices.size() > 0) {
            // 获取数组的类型信息
            Type arrayType = ((PointerType)pointer.getType()).getElementType();
            
            // 遍历所有索引
            for (int i = 0; i < indices.size(); i++) {
                Value indexValue = indices.get(i);
                
                // 获取当前维度的元素大小
                int elementSize;
                if (arrayType instanceof PointerType) {
                    // 如果是多层指针，元素大小是指针大小（通常为8字节）
                    elementSize = 8;
                    arrayType = ((PointerType) arrayType).getElementType();
                } else {
                    // 基本类型元素
                    elementSize = arrayType.getSize();
                }
                
                // 处理索引值
                if (indexValue instanceof ConstantInt) {
                    // 对于常量索引，直接计算偏移
                    long indexVal = ((ConstantInt) indexValue).getValue();
                    long offset = indexVal * elementSize;
                    
                    // 如果偏移非零，添加到当前地址
                    if (offset != 0) {
                        Armv8Binary addInst = new Armv8Binary(destReg, destReg, new Armv8Imm((int)offset), Armv8Binary.Armv8BinaryType.add);
                        addInstr(addInst, insList, predefine);
                    }
                } else {
                    // 对于变量索引，需要计算 索引*元素大小
                    if (!RegList.containsKey(indexValue)) {
                        if (indexValue instanceof Instruction) {
                            parseInstruction((Instruction) indexValue, true);
                        } else {
                            throw new RuntimeException("未处理的索引类型: " + indexValue);
                        }
                    }
                    
                    // 获取索引寄存器
                    Armv8Reg indexReg = RegList.get(indexValue);
                    
                    // 为计算结果分配临时寄存器
                    Armv8Reg tempReg = new Armv8VirReg(false);
                    
                    if (elementSize != 1) {
                        // 计算 索引 * 元素大小
                        if (isPowerOfTwo(elementSize)) {
                            // 如果元素大小是2的幂，使用左移操作
                            int shiftAmount = (int)(Math.log(elementSize) / Math.log(2));
                            Armv8Binary lslInst = new Armv8Binary(tempReg, indexReg, new Armv8Imm(shiftAmount), Armv8Binary.Armv8BinaryType.lsl);
                            addInstr(lslInst, insList, predefine);
                        } else {
                            // 否则使用乘法操作
                            // 首先加载元素大小到临时寄存器
                            Armv8Move moveElemSizeInst = new Armv8Move(tempReg, new Armv8Imm(elementSize), false);
                            addInstr(moveElemSizeInst, insList, predefine);
                            
                            // 执行乘法计算
                            ArrayList<Armv8Operand> mulOperands = new ArrayList<>();
                            mulOperands.add(indexReg);
                            mulOperands.add(tempReg);
                            
                            Armv8Binary mulInst = new Armv8Binary(mulOperands, tempReg, Armv8Binary.Armv8BinaryType.mul);
                            addInstr(mulInst, insList, predefine);
                        }
                    } else {
                        // 如果元素大小为1，索引值就是偏移量
                        Armv8Move moveIndexInst = new Armv8Move(tempReg, indexReg, false);
                        addInstr(moveIndexInst, insList, predefine);
                    }
                    
                    // 将偏移量添加到基址
                    ArrayList<Armv8Operand> addOperands = new ArrayList<>();
                    addOperands.add(destReg);
                    addOperands.add(tempReg);
                    
                    Armv8Binary addInst = new Armv8Binary(addOperands, destReg, Armv8Binary.Armv8BinaryType.add);
                    addInstr(addInst, insList, predefine);
                }
            }
        }
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    /**
     * 判断一个整数是否是2的幂
     */
    private boolean isPowerOfTwo(long n) {
        if (n <= 0) return false;
        return (n & (n - 1)) == 0;
    }

    private void parseRetInst(ReturnInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 检查是否有返回值
        if (!ins.isVoidReturn()) {
            // 获取返回值
            Value returnValue = ins.getReturnValue();
            
            // 获取返回寄存器 - ARM调用规范中整数返回值必须在x0中，浮点返回值在v0中
            Armv8Reg returnReg;
            if (returnValue.getType() instanceof FloatType) {
                returnReg = Armv8FPUReg.getArmv8FPURetValueReg(); // v0
            } else {
                returnReg = Armv8CPUReg.getArmv8CPURetValueReg(); // x0
            }
            
            // 将值加载到返回寄存器中
            if (returnValue instanceof ConstantInt) {
                // 对于常量整数，直接移动到返回寄存器
                long value = ((ConstantInt) returnValue).getValue();
                addInstr(new Armv8Move(returnReg, new Armv8Imm(value), false), insList, predefine);
            } else if (returnValue instanceof ConstantFloat) {
                // 对于常量浮点数，加载到返回寄存器
                float value = (float)((ConstantFloat) returnValue).getValue();
                loadFloatConstant(returnReg, value, insList, predefine);
            } else if (returnValue instanceof Argument) {
                // 处理参数作为返回值 - 直接使用辅助方法获取参数值寄存器
                Argument arg = (Argument) returnValue;
                Armv8Reg paramReg = ensureArgumentRegister(arg, insList, predefine);
                
                // 将参数值移动到返回寄存器（如果需要）
                if (paramReg != null && !paramReg.equals(returnReg)) {
                    addInstr(new Armv8Move(returnReg, paramReg, returnValue.getType() instanceof FloatType), 
                        insList, predefine);
                }
                // 如果参数已经在返回寄存器中，无需操作
            } else {
                // 处理其他类型的返回值
                Armv8Operand operand = processOperand(returnValue, insList, predefine, 
                                                     returnValue.getType() instanceof FloatType, false);
                if (operand instanceof Armv8Reg) {
                    // 如果操作数是寄存器且不是返回寄存器，则需要移动
                    if (!operand.equals(returnReg)) {
                        addInstr(new Armv8Move(returnReg, operand, returnValue.getType() instanceof FloatType), 
                            insList, predefine);
                    }
                } else if (operand instanceof Armv8Imm) {
                    // 如果是立即数，直接加载到返回寄存器
                    addInstr(new Armv8Move(returnReg, operand, false), insList, predefine);
                }
            }
        }
        
        // 获取函数的栈大小
        Long localSize = curArmv8Function.getStackSize();
        if (localSize > 0) {
            // 计算对齐后的栈大小
            long alignedSize = (localSize + 15) & ~15;  // 对齐到16字节
            
            // 如果有局部变量空间，调整SP释放局部变量空间
            Armv8Binary addSpInst = new Armv8Binary(
                Armv8CPUReg.getArmv8SpReg(), 
                Armv8CPUReg.getArmv8SpReg(), 
                new Armv8Imm(alignedSize), 
                Armv8Binary.Armv8BinaryType.add
            );
            addInstr(addSpInst, insList, predefine);
        }
        
        // 恢复FP(x29)和LR(x30)寄存器，并调整SP
        Armv8LoadPair ldpInst = new Armv8LoadPair(
            Armv8CPUReg.getArmv8SpReg(),
            new Armv8Imm(16), 
            Armv8CPUReg.getArmv8FPReg(),  // x29
            Armv8CPUReg.getArmv8RetReg(),  // x30/LR
            false,  // 不是32位寄存器
            true    // 后索引模式
        );
        addInstr(ldpInst, insList, predefine);
        
        // 添加返回指令
        Armv8Ret retInst = new Armv8Ret();
        addInstr(retInst, insList, predefine);
        
        // 标记当前基本块包含返回指令
        if (!predefine) {
            curArmv8Block.setHasReturnInstruction(true);
        }
        
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
        
        // 处理待存储值，获取或创建包含值的寄存器
        Armv8Reg valueReg;
        
        if (valueToStore instanceof ConstantInt) {
            // 对于整数常量，创建一个临时寄存器并加载值
            valueReg = new Armv8VirReg(false);
            Armv8Move moveInst = new Armv8Move(valueReg, new Armv8Imm((int)((ConstantInt)valueToStore).getValue()), false);
            addInstr(moveInst, insList, predefine);
        } 
        else if (valueToStore instanceof ConstantFloat) {
            valueReg = new Armv8VirReg(true);
            // 处理浮点常量的存储
            double floatValue = ((ConstantFloat) valueToStore).getValue();
            loadFloatConstant(valueReg, floatValue, insList, predefine);
        }
        else if (valueToStore instanceof Argument) {
            // 处理函数参数作为存储值的情况
            Argument arg = (Argument) valueToStore;
            
            // 检查参数是否已分配寄存器
            if (!RegList.containsKey(valueToStore)) {
                valueReg = valueToStore.getType() instanceof FloatType ? 
                           new Armv8VirReg(true) : new Armv8VirReg(false);
                
                // 检查参数是否在寄存器中
                if (curArmv8Function.getRegArg(arg) != null) {
                    // 参数在寄存器中，直接使用该寄存器
                    Armv8Reg argReg = curArmv8Function.getRegArg(arg);
                    Armv8Move moveInst = new Armv8Move(valueReg, argReg, valueReg instanceof Armv8FPUReg);
                    addInstr(moveInst, insList, predefine);
                } else if (curArmv8Function.getStackArg(arg) != null) {
                    // 参数在栈上，使用FP+偏移量加载
                    long stackParamOffset = curArmv8Function.getStackArg(arg);
                    
                    // 从FP+偏移量加载到临时寄存器
                    Armv8Load loadParamInst = new Armv8Load(Armv8CPUReg.getArmv8FPReg(), 
                        new Armv8Imm(stackParamOffset), valueReg);
                    addInstr(loadParamInst, insList, predefine);
                }
                // 将寄存器与参数关联
                RegList.put(valueToStore, valueReg);
            } else {
                valueReg = RegList.get(valueToStore);
            }
        }
        else {
            // 对于变量，确保已经有关联的寄存器
            if (!RegList.containsKey(valueToStore)) {
                if (valueToStore instanceof Instruction) {
                    parseInstruction((Instruction)valueToStore, true);
                } else if (valueToStore.getType() instanceof FloatType) {
                    // 处理浮点变量
                    // 分配一个浮点寄存器
                    Armv8Reg floatReg = new Armv8VirReg(true);
                    
                    RegList.put(valueToStore, floatReg);
                } else if (valueToStore.getType() instanceof IntegerType) {
                    // 处理整数变量
                    // 分配一个通用寄存器
                    Armv8Reg cpuReg = new Armv8VirReg(false);
                    RegList.put(valueToStore, cpuReg);
                } else {
                    throw new RuntimeException("未处理的存储值类型: " + valueToStore);
                }
            }
            valueReg = RegList.get(valueToStore);
        }
        
        // 处理指针操作数
        Armv8Reg baseReg = new Armv8VirReg(false);  // 默认分配一个寄存器
        Armv8Operand offsetOp = new Armv8Imm(0); // 默认偏移量为0
        
        // 处理各种类型的指针
        if (pointer instanceof GetElementPtrInstruction) {
            // 如果是GEP指令，检查是否已经有关联的寄存器
            if (!RegList.containsKey(pointer)) {
                // 如果没有，解析GEP指令
                parsePtrInst((GetElementPtrInstruction)pointer, true);
            }
            
            if (RegList.containsKey(pointer)) {
                // 使用GEP结果作为基地址
                baseReg = RegList.get(pointer);
            } else if (ptrList.containsKey(pointer)) {
                // 使用栈指针加上偏移量
                baseReg = Armv8CPUReg.getArmv8SpReg();
                offsetOp = new Armv8Imm(ptrList.get(pointer));
            } else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            // 对于全局变量，使用标签
            String globalName = removeLeadingAt(((GlobalVariable)pointer).getName());
            Armv8Label label = new Armv8Label(globalName);
            
            // 为全局变量地址分配寄存器
            baseReg = new Armv8VirReg(false);
            
            // 创建加载地址指令
            Armv8Adr adrInst = new Armv8Adr(baseReg, label);
            addInstr(adrInst, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果是局部变量分配指令
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction)pointer, true);
            }
            
            // 使用栈指针加上偏移量
            baseReg = Armv8CPUReg.getArmv8SpReg();
            offsetOp = new Armv8Imm(ptrList.get(pointer).intValue());
        } else if (pointer instanceof Argument) {
            // 处理函数参数，为其分配寄存器
            Argument arg = (Argument) pointer;
            if (curArmv8Function.getRegArg(arg) != null) {
                baseReg = curArmv8Function.getRegArg(arg);
            } else if (curArmv8Function.getStackArg(arg) != null) {
                baseReg = new Armv8VirReg(false);
                long stackParamOffset = curArmv8Function.getStackArg(arg); // 前8个在寄存器中
                
                // 创建加载指令：从FP+偏移量加载到临时寄存器
                Armv8Load loadParamInst = new Armv8Load(Armv8CPUReg.getArmv8FPReg(), 
                    new Armv8Imm(stackParamOffset), baseReg);
                addInstr(loadParamInst, insList, predefine);
            }
            RegList.put(pointer, baseReg);
        } else {
            // 其他类型的指针，尝试获取关联的寄存器
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction)pointer, true);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = RegList.get(pointer);
        }
        
        // 确保baseReg一定有值
        if (baseReg == null) {
            throw new RuntimeException("无法确定指针的基地址寄存器: " + pointer);
        }
        
        // 创建存储指令
        Armv8Store storeInst = new Armv8Store(valueReg, baseReg, offsetOp);
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
        Armv8Reg destReg = new Armv8VirReg(false);
        RegList.put(ins, destReg);
        
        // 处理左操作数
        Armv8Operand leftOp;
        if (left instanceof ConstantInt) {
            // 对于整数常量，创建临时寄存器并加载立即数
            // ARM要求CMP的第一个操作数必须是寄存器
            long value = ((ConstantInt) left).getValue();
            Armv8Reg leftReg = new Armv8VirReg(false);
            Armv8Move moveInst = new Armv8Move(leftReg, new Armv8Imm(value), true);
            addInstr(moveInst, insList, predefine);
            leftOp = leftReg;
        } else if (left instanceof ConstantFloat) {
            // 处理浮点常量
            double floatValue = ((ConstantFloat) left).getValue();
            
            // 为浮点常量分配寄存器
            Armv8Reg fpuReg = new Armv8VirReg(true);
            // 加载浮点常量到寄存器
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            
            leftOp = fpuReg;
        } else {
            // 对于变量，使用寄存器
            if (!RegList.containsKey(left)) {
                if (left.getType() instanceof FloatType) {
                    // 浮点类型使用浮点寄存器
                    Armv8Reg leftReg = new Armv8VirReg(true);
                    
                    RegList.put(left, leftReg);
                    leftOp = leftReg;
                } else {
                    // 整数类型使用通用寄存器
                    Armv8Reg leftReg = new Armv8VirReg(false);
                    RegList.put(left, leftReg);
                    leftOp = leftReg;
                }
            } else {
                leftOp = RegList.get(left);
            }
        }
        
        // 处理右操作数
        Armv8Operand rightOp;
        if (right instanceof ConstantInt) {
            // 对于整数常量，创建立即数操作数
            long value = ((ConstantInt) right).getValue();
            rightOp = new Armv8Imm(value);
        } else if (right instanceof ConstantFloat) {
            // 处理浮点常量
            double floatValue = ((ConstantFloat) right).getValue();
            
            // 为浮点常量分配寄存器
            Armv8Reg fpuReg = new Armv8VirReg(true);
            // 加载浮点常量到寄存器
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            
            rightOp = fpuReg;
        } else {
            // 对于变量，使用寄存器
            if (!RegList.containsKey(right)) {
                if (right.getType() instanceof FloatType) {
                    // 浮点类型使用浮点寄存器
                    Armv8Reg rightReg = new Armv8VirReg(true);
                    
                    RegList.put(right, rightReg);
                    rightOp = rightReg;
                } else {
                    // 整数类型使用通用寄存器
                    Armv8Reg rightReg = new Armv8VirReg(false);
                    RegList.put(right, rightReg);
                    rightOp = rightReg;
                }
            } else {
                rightOp = RegList.get(right);
            }
        }
        
        // 创建比较指令
        Armv8Compare.CmpType cmpType;
        
        // 检查是否可以使用CMN指令优化（当比较一个负数常量时）
        if (!isFloat && rightOp instanceof Armv8Imm) {
            long value = ((Armv8Imm) rightOp).getValue();
            if (value < 0) {
                // 使用CMN指令，比较负值（相当于CMP reg, -imm）
                cmpType = Armv8Compare.CmpType.cmn;
                // 将立即数改为正值
                rightOp = new Armv8Imm(-value);
            } else {
                cmpType = Armv8Compare.CmpType.cmp;
            }
        } else if (isFloat) {
            cmpType = Armv8Compare.CmpType.fcmp;
        } else {
            cmpType = Armv8Compare.CmpType.cmp;
        }
        
        Armv8Compare compareInst = new Armv8Compare(leftOp, rightOp, cmpType);
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
                condType = isFloat ? Armv8Tools.CondType.gt : Armv8Tools.CondType.gt;
                break;
            case SGE:
                condType = isFloat ? Armv8Tools.CondType.ge : Armv8Tools.CondType.ge;
                break;
            case SLT:
                condType = isFloat ? Armv8Tools.CondType.lt : Armv8Tools.CondType.lt;
                break;
            case SLE:
                condType = isFloat ? Armv8Tools.CondType.le : Armv8Tools.CondType.le;
                break;
            case UGT:
                condType = isFloat ? Armv8Tools.CondType.hi : Armv8Tools.CondType.hi; // 浮点无序大于
                break;
            case UGE:
                condType = isFloat ? Armv8Tools.CondType.cs : Armv8Tools.CondType.cs; // 浮点无序大于等于
                break;
            case ULT:
                condType = isFloat ? Armv8Tools.CondType.cc : Armv8Tools.CondType.cc; // 浮点无序小于
                break;
            case ULE:
                condType = isFloat ? Armv8Tools.CondType.ls : Armv8Tools.CondType.ls; // 浮点无序小于等于
                break;
            case UNE:
                // 浮点无序不等于 (ARM中的vs, overflow set)
                condType = Armv8Tools.CondType.vs;
                break;
            case ORD:
                // 浮点有序 (ARM中的vc, overflow clear)
                condType = Armv8Tools.CondType.vc;
                break;
            case UNO:
                // 浮点无序 (ARM中的vs, overflow set)
                condType = Armv8Tools.CondType.vs;
                break;
            default:
                System.err.println("不支持的比较谓词: " + predicate);
                condType = Armv8Tools.CondType.eq; // 默认为等于
                break;
        }
        
        // 直接使用一条指令设置结果
        // 使用指定的条件码将结果设置为1或0
        Armv8Cset csetInst = new Armv8Cset(destReg, condType);
        addInstr(csetInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parsePhiInst(PhiInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 为Phi结果分配目标寄存器
        Armv8Reg destReg;
        if (ins.getType() instanceof FloatType) {
            destReg = new Armv8VirReg(true);
        } else {
            destReg = new Armv8VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 获取Phi指令的所有输入块和对应值
        Map<BasicBlock, Value> incomingValues = ins.getIncomingValues();
        
        // 处理每个输入值
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            BasicBlock incomingBlock = entry.getKey();
            Value incomingValue = entry.getValue();
            
            // 获取对应的ARM块
            Armv8Block armv8IncomingBlock = (Armv8Block) LabelList.get(incomingBlock);
            if (armv8IncomingBlock == null) {
                System.err.println("警告: 找不到Phi指令输入块的对应ARM块: " + incomingBlock.getName());
                continue;
            }
            
            // 为输入值创建源操作数
            Armv8Operand srcOp;
            if (incomingValue instanceof ConstantInt) {
                // 常量值，创建立即数
                long value = ((ConstantInt) incomingValue).getValue();
                srcOp = new Armv8Imm(value);
            } else if (incomingValue instanceof ConstantFloat) {
                double floatValue = ((ConstantFloat) incomingValue).getValue();
                Armv8Reg fpuReg = new Armv8VirReg(true);
                loadFloatConstant(fpuReg, floatValue, insList, predefine);
                srcOp = fpuReg;
            }
            else {
                // 非常量值，使用寄存器
                if (!RegList.containsKey(incomingValue)) {
                    if (incomingValue instanceof Instruction) {
                        // 递归处理未解析的指令
                        parseInstruction((Instruction) incomingValue, true);
                    } else {
                        // 为未分配寄存器的值分配一个新寄存器
                        Armv8Reg valueReg = new Armv8VirReg(false);
                        RegList.put(incomingValue, valueReg);
                    }
                }
                
                if (RegList.containsKey(incomingValue)) {
                    srcOp = RegList.get(incomingValue);
                } else {
                    System.err.println("警告: 无法为Phi指令的输入值创建操作数: " + incomingValue);
                    continue;
                }
            }
            
            // 创建移动指令
            Armv8Move moveInst;
            if (srcOp instanceof Armv8Imm) {
                moveInst = new Armv8Move(destReg, srcOp, true);
            } else {
                moveInst = new Armv8Move(destReg, (Armv8Reg) srcOp, false);
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
        Armv8Reg destReg;
        if (operand instanceof ConstantInt) {
            destReg = new Armv8VirReg(false);
        } else if (operand instanceof ConstantFloat) {
            destReg = new Armv8VirReg(true);
        } else {
            destReg = new Armv8VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 处理操作数
        Armv8Operand srcOp;
        if (operand instanceof ConstantInt) {
            // 对于常量，创建立即数操作数
            long value = ((ConstantInt) operand).getValue();
            srcOp = new Armv8Imm(value);  // 显式转换为int
        } else if (operand instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) operand).getValue();
            Armv8Reg fpuReg = new Armv8VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            srcOp = fpuReg;
        } else {
            // 对于变量，使用寄存器
            if (!RegList.containsKey(operand)) {
                if (operand instanceof Instruction) {
                    parseInstruction((Instruction) operand, true);
                } else {
                    Armv8Reg srcReg = new Armv8VirReg(false);
                    RegList.put(operand, srcReg);
                }
            }
            srcOp = RegList.get(operand);
        }
        
        
        switch (opCode) {
            case NEG: // 取负操作
                if (srcOp instanceof Armv8Imm) {
                    // 如果是立即数，可以直接计算负值
                    long value = ((Armv8Imm) srcOp).getValue();
                    Armv8Move moveInst = new Armv8Move(destReg, new Armv8Imm(-value), true);
                    addInstr(moveInst, insList, predefine);
                } else {
                    // 如果是寄存器，使用NEG指令
                    Armv8Reg srcReg = (Armv8Reg) srcOp;
                    Armv8Unary negInst = new Armv8Unary(srcReg, destReg, Armv8Unary.Armv8UnaryType.neg);
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

    private void parseMoveInst(MoveInstruction ins, boolean predefine) {
        ArrayList<Armv8Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取源值
        Value source = ins.getSource();
        
        // 为目标分配寄存器 - 关键修复：处理PHI消除后的重复名称
        Armv8Reg destReg;
        
        // 检查是否已经有同名的寄存器映射（来自PHI消除）
        // 如果有，使用现有的寄存器；否则创建新的
        String destName = ins.getName();
        
        // 查找是否已经有同名的Value映射到寄存器
        Value existingValue = null;
        for (Map.Entry<Value, Armv8Reg> entry : RegList.entrySet()) {
            if (entry.getKey().getName().equals(destName)) {
                existingValue = entry.getKey();
                break;
            }
        }
        
        if (existingValue != null) {
            // 使用现有的寄存器
            destReg = RegList.get(existingValue);
        } else {
            // 创建新的寄存器
            if (ins.getType() instanceof FloatType) {
                destReg = new Armv8VirReg(true);
            } else {
                destReg = new Armv8VirReg(false);
            }
        }
        
        // 为当前Move指令建立映射
        RegList.put(ins, destReg);
        
        // 关键修复：为目标名称建立映射，让其他指令能按名称找到寄存器
        // 创建一个代理Value对象来代表目标变量
        if (existingValue == null) {
            // 创建一个代理Value对象，使用Move指令的名称
            Value targetValue = new Value(destName, ins.getType()) {
                @Override
                public String toString() {
                    return destName;
                }
            };
            RegList.put(targetValue, destReg);
        }
        
        // 处理源操作数
        Armv8Operand srcOp;
        if (source instanceof ConstantInt) {
            long value = ((ConstantInt) source).getValue();
            srcOp = new Armv8Imm(value);
        } else if (source instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) source).getValue();
            Armv8Reg fpuReg = new Armv8VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            srcOp = fpuReg;
        } else {
            // 从RegList获取源寄存器
            if (!RegList.containsKey(source)) {
                if (source.getType() instanceof FloatType) {
                    Armv8Reg srcReg = new Armv8VirReg(true);
                    RegList.put(source, srcReg);
                    srcOp = srcReg;
                } else {
                    Armv8Reg srcReg = new Armv8VirReg(false);
                    RegList.put(source, srcReg);
                    srcOp = srcReg;
                }
            } else {
                srcOp = RegList.get(source);
            }
        }
        
        // 创建移动指令
        Armv8Move moveInst;
        if (srcOp instanceof Armv8Imm) {
            moveInst = new Armv8Move(destReg, srcOp, true);
        } else {
            moveInst = new Armv8Move(destReg, (Armv8Reg) srcOp, false);
        }
        
        addInstr(moveInst, insList, predefine);
        
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

    public void dump(String outputFilePath) {
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(outputFilePath));
            out.write(armv8Module.toString());
            out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Armv8Module getArmv8Module() {
        return armv8Module;
    }

    /**
     * 加载浮点常量到寄存器
     * @param destReg 目标浮点寄存器
     * @param value 要加载的浮点值
     * @param insList 指令列表
     * @param predefine 是否是预定义阶段
     */
    private void loadFloatConstant(Armv8Reg destReg, double value, ArrayList<Armv8Instruction> insList, boolean predefine) {
        // 先将浮点数转换为原始二进制表示
        long bits = Double.doubleToRawLongBits(value);
        
        // 分配一个虚拟寄存器用于存放位模式 (整数类型)
        Armv8VirReg tempReg = new Armv8VirReg(false);
        
        // 使用MOVZ和MOVK指令加载64位浮点值的位模式到通用寄存器
        // 加载低16位
        Armv8Move movzInst = new Armv8Move(tempReg, new Armv8Imm(bits & 0xFFFF), true, Armv8Move.MoveType.MOVZ);
        addInstr(movzInst, insList, predefine);
        
        // 加载第二个16位块
        Armv8Move movk1Inst = new Armv8Move(tempReg, new Armv8Imm((bits >> 16) & 0xFFFF), true, Armv8Move.MoveType.MOVK);
        movk1Inst.setShift(16);
        addInstr(movk1Inst, insList, predefine);
        
        // 加载第三个16位块
        Armv8Move movk2Inst = new Armv8Move(tempReg, new Armv8Imm((bits >> 32) & 0xFFFF), true, Armv8Move.MoveType.MOVK);
        movk2Inst.setShift(32);
        addInstr(movk2Inst, insList, predefine);
        
        // 加载最高16位
        Armv8Move movk3Inst = new Armv8Move(tempReg, new Armv8Imm((bits >> 48) & 0xFFFF), true, Armv8Move.MoveType.MOVK);
        movk3Inst.setShift(48);
        addInstr(movk3Inst, insList, predefine);
        
        // 使用FMOV指令将通用寄存器的位模式移动到浮点寄存器
        Armv8Fmov fmovInst = new Armv8Fmov(destReg, tempReg);
        addInstr(fmovInst, insList, predefine);
    }

    /**
     * 创建一个MADD指令（乘加运算 d = a + b*c）
     * @param destReg 目标寄存器
     * @param addReg 加数寄存器
     * @param mulReg1 被乘数1
     * @param mulReg2 被乘数2
     * @param insList 指令列表
     * @param predefine 是否是预定义阶段
     */
    private void createMaddInstruction(Armv8Reg destReg, Armv8Reg addReg, Armv8Reg mulReg1, Armv8Reg mulReg2, 
                                     ArrayList<Armv8Instruction> insList, boolean predefine) {
        ArrayList<Armv8Operand> operands = new ArrayList<>();
        operands.add(addReg);   // 第一个操作数是加数
        operands.add(mulReg1);  // 第二个操作数是被乘数1
        operands.add(mulReg2);  // 第三个操作数是被乘数2
        
        Armv8Binary maddInst = new Armv8Binary(operands, destReg, Armv8Binary.Armv8BinaryType.madd);
        addInstr(maddInst, insList, predefine);
    }

    /**
     * 确保正确获取函数参数
     * 在ARM调用约定中，前8个整型参数在x0-x7中，前8个浮点参数在v0-v7中
     */
    private Armv8Reg ensureArgumentRegister(Argument arg, ArrayList<Armv8Instruction> insList, boolean predefine) {
        if (RegList.containsKey(arg)) {
            return RegList.get(arg);
        }
        
        // 检查参数是否在寄存器中
        Armv8Reg argReg = curArmv8Function.getRegArg(arg);
        if (argReg != null) {
            // 参数在寄存器中，直接使用
            RegList.put(arg, argReg);
            return argReg;
        }
        
        // 检查参数是否在栈上
        Long stackOffset = curArmv8Function.getStackArg(arg);
        if (stackOffset != null) {
            // 参数在栈上，需要加载到寄存器
            Armv8VirReg tempReg = arg.getType() instanceof FloatType ? 
                new Armv8VirReg(true) : new Armv8VirReg(false);
                
            // 从FP相对位置加载参数值
            Armv8Load loadInst = new Armv8Load(
                Armv8CPUReg.getArmv8FPReg(), 
                new Armv8Imm(stackOffset), 
                tempReg
            );
            addInstr(loadInst, insList, predefine);
            
            // 记录映射
            RegList.put(arg, tempReg);
            return tempReg;
        }
        
        // 无法找到参数位置，报错
        System.err.println("错误: 无法找到参数 " + arg + " 的位置");
        return null;
    }
} 
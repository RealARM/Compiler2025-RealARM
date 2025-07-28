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

    // private int calculateStackSize(Function function) {
    //     int size = 0;
    //     int localVarCount = 0;
        
    //     // 遍历所有基本块的所有指令，统计局部变量的数量
    //     for (BasicBlock block : function.getBasicBlocks()) {
    //         for (Instruction instruction : block.getInstructions()) {
    //             if (instruction instanceof AllocaInstruction) {
    //                 localVarCount++;
    //             }
    //         }
    //     }
    //     size += localVarCount * 8;
    //     size = (size + 15) & ~15;        
    //     return size;
    // }

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
        long size;
        // 判断是数组分配还是单一变量分配
        if (ins.isArrayAllocation()) {
            size = 8 * ins.getArraySize();
        } else {
            // 单一变量
            size = 8;
        }
        curArmv8Function.addStack(ins, size);
        ptrList.put(ins, stackPos);
        
        // 为alloca指令分配一个虚拟寄存器来存储地址
        if (!predefine) {
            Armv8VirReg allocaReg = new Armv8VirReg(false);
            RegList.put(ins, allocaReg);
            
            // 生成计算地址的指令：SP + 偏移
            Armv8Binary addInst = new Armv8Binary(allocaReg, Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(stackPos), Armv8Binary.Armv8BinaryType.add);
            curArmv8Block.addArmv8Instruction(addInst);
        }
        
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
        
        Armv8Operand leftOp, rightOp;
        
        // 处理左操作数
        if (leftOperand instanceof ConstantInt) {
            long value = ((ConstantInt) leftOperand).getValue();
            // 对于MUL、DIV操作，立即数必须先加载到寄存器
            if (opCode == OpCode.MUL || opCode == OpCode.DIV || opCode == OpCode.FDIV || opCode == OpCode.FMUL) {
                Armv8Reg tempReg = new Armv8VirReg(false);
                Armv8Move moveInst = new Armv8Move(tempReg, new Armv8Imm(value), true);
                addInstr(moveInst, insList, predefine);
                leftOp = tempReg;
            } else {
                leftOp = new Armv8Imm(value);
            }
        } else if (leftOperand instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) leftOperand).getValue();
            Armv8VirReg fReg = new Armv8VirReg(true);
            // 加载浮点常量到寄存器
            loadFloatConstant(fReg, floatValue, insList, predefine);
            leftOp = fReg;
        } else {
            // 变量已经被load出来了
            leftOp = RegList.get(leftOperand);
        }
        
        // 处理右操作数
        if (rightOperand instanceof ConstantInt) {
            long value = ((ConstantInt) rightOperand).getValue();
            // 对于MUL、DIV操作，立即数必须先加载到寄存器
            if (opCode == OpCode.MUL || opCode == OpCode.DIV || opCode == OpCode.FDIV || opCode == OpCode.FMUL) {
                Armv8Reg tempReg = new Armv8VirReg(false);
                Armv8Move moveInst = new Armv8Move(tempReg, new Armv8Imm(value), true);
                addInstr(moveInst, insList, predefine);
                rightOp = tempReg;
            } else {
                rightOp = new Armv8Imm(value);
            }
        } else if (rightOperand instanceof ConstantFloat) {
            // 处理浮点常量
            double floatValue = ((ConstantFloat) rightOperand).getValue();
            Armv8VirReg fReg = new Armv8VirReg(true);
            // 加载浮点常量到寄存器
            loadFloatConstant(fReg, floatValue, insList, predefine);
            rightOp = fReg;
        } else {
            rightOp = RegList.get(rightOperand);
        }
        
        // 创建操作数列表
        ArrayList<Armv8Operand> operands = new ArrayList<>();
        operands.add(leftOp);
        operands.add(rightOp);
        
        // 根据操作码和操作数类型生成对应的ARM指令
        if (isFloatOperation) {
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
            // 处理整数操作
            Armv8Binary.Armv8BinaryType binaryType = null;
            switch (opCode) {
                case ADD:
                    binaryType = Armv8Binary.Armv8BinaryType.add;
                    
                    // 如果加法的操作数涉及两个以上寄存器，考虑使用MADD指令
                    if (leftOp instanceof Armv8Reg && rightOp instanceof Armv8Reg) {
                        Armv8Reg leftReg = (Armv8Reg) leftOp;
                        Armv8Reg rightReg = (Armv8Reg) rightOp;
                        
                        // 检查是否有一个寄存器的值可能是常数2的乘积
                        // 这是一个启发式判断，在某些情况下可以使用MADD优化
                        if (rightOperand instanceof ConstantInt && ((ConstantInt) rightOperand).getValue() == 2) {
                            // 创建MADD指令: destReg = leftReg + leftReg (左寄存器乘以1再加上自身)
                            ArrayList<Armv8Operand> maddOperands = new ArrayList<>();
                            maddOperands.add(leftReg);  // 加数
                            maddOperands.add(leftReg);  // 被乘数1
                            maddOperands.add(Armv8CPUReg.getZeroReg()); // 被乘数2 (值为1)
                            
                            Armv8Binary maddInst = new Armv8Binary(maddOperands, destReg, Armv8Binary.Armv8BinaryType.madd);
                            addInstr(maddInst, insList, predefine);
                            
                            if (predefine) {
                                predefines.put(ins, insList);
                            }
                            return; // 提前返回
                        }
                    }
                    break;
                case SUB:
                    binaryType = Armv8Binary.Armv8BinaryType.sub;
                    // 检查是否是从立即数0减去寄存器的情况
                    if (leftOp instanceof Armv8Imm && ((Armv8Imm) leftOp).getValue() == 0) {
                        // 如果是 0 - reg 的情况，使用零寄存器，重新创建操作数列表
                        operands.clear();
                        operands.add(Armv8CPUReg.getZeroReg());
                        operands.add(rightOp);
                    }
                    break;
                case MUL:
                    // 检查是否可以使用位移指令优化乘法
                    if (rightOp instanceof Armv8Imm) {
                        long value = ((Armv8Imm) rightOp).getValue();
                        if (value > 0 && isPowerOfTwo(value)) {
                            // 如果是2的幂，使用LSL指令
                            int shiftBits = (int) (Math.log(value) / Math.log(2));
                            binaryType = Armv8Binary.Armv8BinaryType.lsl;
                            
                            // 将右操作数替换为移位值
                            operands.remove(1); // 删除原始的立即数
                            operands.add(new Armv8Imm(shiftBits)); // 添加移位值
                            
                            Armv8Binary lslInst = new Armv8Binary(operands, destReg, binaryType);
                            addInstr(lslInst, insList, predefine);
                            
                            if (predefine) {
                                predefines.put(ins, insList);
                            }
                            return; // 提前返回，不执行后面的通用指令创建
                        }
                    }
                    // 如果不能优化，使用普通MUL指令
                    binaryType = Armv8Binary.Armv8BinaryType.mul;
                    
                    // 对于乘法操作，右操作数必须是寄存器而非立即数
                    if (rightOp instanceof Armv8Imm) {
                        Armv8VirReg immReg = new Armv8VirReg(false);
                        Armv8Move loadImmInst = new Armv8Move(immReg, rightOp, false);
                        addInstr(loadImmInst, insList, predefine);
                        rightOp = immReg;
                        // 更新操作数列表
                        operands.remove(1);
                        operands.add(immReg);
                    }
                    break;
                case DIV:
                    // 对于除法操作，右操作数必须是寄存器而非立即数
                    if (rightOp instanceof Armv8Imm) {
                        Armv8VirReg immReg = new Armv8VirReg(false);
                        Armv8Move loadImmInst = new Armv8Move(immReg, rightOp, false);
                        addInstr(loadImmInst, insList, predefine);
                        rightOp = immReg;
                        // 更新操作数列表
                        operands.remove(1);
                        operands.add(immReg);
                    }
                    binaryType = Armv8Binary.Armv8BinaryType.sdiv;
                    break;
                case REM:
                    // 余数操作在ARM中需要特殊处理
                    // 1. 先做除法 dest = a / b
                    // 2. 然后计算余数 dest = a - dest * b
                    
                    // 对于立即数右操作数，需要先加载到寄存器中
                    if (rightOp instanceof Armv8Imm) {
                        Armv8VirReg immReg = new Armv8VirReg(false);
                        Armv8Move loadImmInst = new Armv8Move(immReg, rightOp, false);
                        addInstr(loadImmInst, insList, predefine);
                        rightOp = immReg;
                        // 更新操作数列表
                        operands.remove(1);
                        operands.add(immReg);
                    }
                    // System.out.println(rightOp);
                    // 创建除法操作
                    Armv8Binary divInst = new Armv8Binary(operands, destReg, Armv8Binary.Armv8BinaryType.sdiv);
                    // System.out.println(operands);
                    addInstr(divInst, insList, predefine);
                    
                    // 创建临时寄存器存储乘法结果
                    Armv8VirReg tempReg = new Armv8VirReg(false);
                    
                    // 创建乘法操作 temp = dest * b
                    ArrayList<Armv8Operand> mulOperands = new ArrayList<>();
                    mulOperands.add(destReg);
                    mulOperands.add(rightOp);
                    Armv8Binary mulInst = new Armv8Binary(mulOperands, tempReg, Armv8Binary.Armv8BinaryType.mul);
                    addInstr(mulInst, insList, predefine);
                    
                    // 创建减法操作 dest = a - temp
                    ArrayList<Armv8Operand> subOperands = new ArrayList<>();
                    subOperands.add(leftOp);
                    subOperands.add(tempReg);
                    Armv8Binary subInst = new Armv8Binary(subOperands, destReg, Armv8Binary.Armv8BinaryType.sub);
                    addInstr(subInst, insList, predefine);
                    
                    if (predefine) {
                        predefines.put(ins, insList);
                    }
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
                    leftOp = new Armv8Imm((int) value);
                } else {
                    leftOp = RegList.get(left);
                }
                
                // 处理右操作数
                if (right instanceof ConstantInt) {
                    long value = ((ConstantInt) right).getValue();
                    rightOp = new Armv8Imm((int) value);
                } else {
                    rightOp = RegList.get(right);
                }
                
                // 创建比较指令
                Armv8Compare.CmpType cmpType = Armv8Compare.CmpType.cmp;
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
                    
                    // 使用CBZ指令测试条件是否为0
                    
                    // 如果为0则跳转到false块
                    Armv8Cbz cbzInst = new Armv8Cbz((Armv8Reg) condOp, armFalseBlock);
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
                Armv8Move moveInst = new Armv8Move(returnReg, new Armv8Imm(((ConstantInt)returnValue).getValue()), true);
                addInstr(moveInst, insList, predefine);
            }
            else if (returnValue instanceof ConstantFloat) {
                // 处理浮点常量的返回
                double floatValue = ((ConstantFloat) returnValue).getValue();
                loadFloatConstant(returnReg, floatValue, insList, predefine);
            }
            else {
                // 如果是变量，需要确保已经有关联的寄存器
                if (!RegList.containsKey(returnValue)) {
                    
                    if (returnValue instanceof Instruction) {
                        parseInstruction((Instruction)returnValue, true);
                    } else if (returnValue instanceof GlobalVariable) {
                        // 处理全局变量
                        GlobalVariable globalVar = (GlobalVariable) returnValue;
                        
                        if (returnValue.getType() instanceof FloatType) {
                            Armv8VirReg tempReg = new Armv8VirReg(true);
                            RegList.put(returnValue, tempReg);
                            // 如果是全局浮点变量，从内存加载其值
                            String globalName = removeLeadingAt(globalVar.getName());
                            Armv8Label label = new Armv8Label(globalName);
                            Armv8VirReg addrReg = new Armv8VirReg(false);
                            Armv8Adr adrInst = new Armv8Adr(addrReg, label);
                            addInstr(adrInst, insList, predefine);
                            // 从地址加载浮点值
                            Armv8Load loadInst = new Armv8Load(addrReg, new Armv8Imm(0), tempReg);
                            addInstr(loadInst, insList, predefine);
                        } else {
                            // 如果是全局整数变量，直接从内存加载其值到返回寄存器
                            String globalName = removeLeadingAt(globalVar.getName());
                            Armv8Label label = new Armv8Label(globalName);
                            Armv8VirReg addrReg = new Armv8VirReg(false);
                            Armv8Adr adrInst = new Armv8Adr(addrReg, label);
                            addInstr(adrInst, insList, predefine);
                            
                            // 从地址加载整数值直接到返回寄存器
                            Armv8Load loadInst = new Armv8Load(addrReg, new Armv8Imm(0), returnReg);
                            addInstr(loadInst, insList, predefine);
                            
                            // 记录寄存器映射
                            Armv8VirReg tempReg = new Armv8VirReg(false);
                            RegList.put(returnValue, tempReg);
                            // 复制返回值到临时寄存器以保持一致性
                            Armv8Move copyInst = new Armv8Move(tempReg, returnReg, false);
                            addInstr(copyInst, insList, predefine);
                        }
                    } else {
                                                // 为其他值分配临时寄存器并初始化为0
                        if (returnValue.getType() instanceof FloatType) {
                             Armv8VirReg tempReg = new Armv8VirReg(true);
                            RegList.put(returnValue, tempReg);
                            loadFloatConstant(tempReg, 0.0, insList, predefine);
                        } else {
                            Armv8VirReg tempReg = new Armv8VirReg(false);
                            RegList.put(returnValue, tempReg);
                            Armv8Move moveInst = new Armv8Move(tempReg, new Armv8Imm(0), true);
                            addInstr(moveInst, insList, predefine);
                        }
                    }
                }
                
                // 将值移动到返回寄存器
                Armv8Reg sourceReg = RegList.get(returnValue);
                
                if (sourceReg != null) {
                    if (returnValue.getType() instanceof FloatType) {
                        // 浮点类型移动
                        Armv8Move moveInst = new Armv8Move(returnReg, sourceReg, false);
                        addInstr(moveInst, insList, predefine);
                    } else {
                        // 整数或指针类型移动
                        Armv8Move moveInst = new Armv8Move(returnReg, sourceReg, false);
                        addInstr(moveInst, insList, predefine);
                    }
                } else {
                    System.err.println("错误: 无法为返回值获取源寄存器: " + returnValue);
                }
            }
        }
        
        // 获取函数的栈大小，用于返回前的栈指针调整
        Armv8CPUReg fpReg = Armv8CPUReg.getArmv8FPReg();  // x29寄存器(FP)
        Armv8CPUReg lrReg = Armv8CPUReg.getArmv8RetReg();  // x30寄存器(LR)
        Armv8CPUReg spReg = Armv8CPUReg.getArmv8SpReg();  // sp寄存器
        
        // 获取函数的栈大小，不包括保存FP和LR的16字节
        Long localSize = curArmv8Function.getStackSize();
        if (localSize > 0) {
            // 如果有局部变量空间，先调整SP释放局部变量空间
            ArrayList<Armv8Operand> operands = new ArrayList<>();
            operands.add(spReg);
            operands.add(new Armv8Imm(localSize));
            Armv8Binary addSpInst = new Armv8Binary(operands, spReg, Armv8Binary.Armv8BinaryType.add);
            addInstr(addSpInst, insList, predefine);
        }
        
        // 恢复FP(x29)和LR(x30)寄存器，并调整SP
        // 使用后索引寻址模式，等效于ldp x29, x30, [sp], #16
        Armv8LoadPair ldpInst = new Armv8LoadPair(spReg, new Armv8Imm(16), fpReg, lrReg, 
                                                false, true); // 不是32位，后索引模式
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
        
        // 创建条件设置指令（CSET）将比较结果存储到目标寄存器
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
} 
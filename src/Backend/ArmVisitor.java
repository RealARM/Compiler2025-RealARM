package Backend;

import Backend.Optimization.PostRegisterOptimizer;
import Backend.RegisterManager.RegisterAllocator;
import Backend.Structure.AArch64Block;
import Backend.Structure.AArch64Function;
import Backend.Structure.AArch64GlobalVariable;
import Backend.Structure.AArch64Module;
import Backend.Utils.AArch64MyLib;
import Backend.Utils.AArch64Tools;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Instruction.Address.AArch64Adrp;
import Backend.Value.Instruction.Arithmetic.AArch64Binary;
import Backend.Value.Instruction.Arithmetic.AArch64FBinary;
import Backend.Value.Instruction.Arithmetic.AArch64Unary;
import Backend.Value.Instruction.Comparison.AArch64Cbz;
import Backend.Value.Instruction.Comparison.AArch64Compare;
import Backend.Value.Instruction.Comparison.AArch64Cset;
import Backend.Value.Instruction.ControlFlow.AArch64Branch;
import Backend.Value.Instruction.ControlFlow.AArch64Call;
import Backend.Value.Instruction.ControlFlow.AArch64Jump;
import Backend.Value.Instruction.ControlFlow.AArch64Ret;
import Backend.Value.Instruction.DataMovement.AArch64Cvt;
import Backend.Value.Instruction.DataMovement.AArch64Fmov;
import Backend.Value.Instruction.DataMovement.AArch64Move;
import Backend.Value.Instruction.Memory.AArch64Load;
import Backend.Value.Instruction.Memory.AArch64LoadPair;
import Backend.Value.Instruction.Memory.AArch64Store;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Register.*;
import Backend.Value.Operand.Symbol.AArch64Label;
import MiddleEnd.IR.Module;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Visitor.IRVisitor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ArmVisitor {
    public Module irModule;
    public AArch64Module armv8Module = new AArch64Module();
    // 使用多态性，这样可以存储AArch64Label及其子类
    private static final LinkedHashMap<Value, AArch64Label> LabelList = new LinkedHashMap<>();
    private static final LinkedHashMap<Value, AArch64Reg> RegList = new LinkedHashMap<>();
    private static final LinkedHashMap<Value, Long> ptrList = new LinkedHashMap<>();
    private AArch64Block curAArch64Block = null;
    private AArch64Function curAArch64Function = null;
    private final LinkedHashMap<Instruction, ArrayList<AArch64Instruction>> predefines = new LinkedHashMap<>();

    public ArmVisitor(MiddleEnd.IR.Module irModule) {
        this.irModule = irModule;
    }

    public String removeLeadingAt(String name) {
        if (name.startsWith("@")) {
            return name.substring(1);
        }
        return name;
    }

    public static LinkedHashMap<Value, AArch64Reg> getRegList() {
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
        // 强制所有整型全局变量都使用8字节（64位）
        int byteSize;
        boolean isFloat = elementType instanceof FloatType;
        
        if (isFloat) {
            byteSize = elementType.getSize();
        } else {
            // 整型强制使用8字节
            byteSize = 8;
        }
        
        if (globalVariable.isArray()) {
            if (isFloat) {
                byteSize = globalVariable.getArraySize() * elementType.getSize();
            } else {
                // 整型数组强制每个元素8字节
                byteSize = globalVariable.getArraySize() * 8;
            }
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
        AArch64GlobalVariable armv8GlobalVar = new AArch64GlobalVariable(varName, initialValues, byteSize, isFloat, elementType);
        
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
        curAArch64Function = new AArch64Function(functionName, function);
        armv8Module.addFunction(functionName, curAArch64Function);
        AArch64VirReg.resetCounter();

        // 将基本块映射到ARM块
        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            String blockName = removeLeadingAt(functionName) + "_" + basicBlock.getName();
            AArch64Block armv8Block = new AArch64Block(blockName);
            LabelList.put(basicBlock, armv8Block);
            curAArch64Function.addBlock(armv8Block);
        }
        
        // 获取函数参数，确保它们被保存和正确处理
        List<Argument> arguments = function.getArguments();
        for (Argument arg : arguments) {
            // 确认参数已被正确记录，无需其他处理
            if (!RegList.containsKey(arg)) {
                AArch64Reg argReg = curAArch64Function.getRegArg(arg);
                if (argReg != null) {
                    RegList.put(arg, argReg); // 确保参数有正确的寄存器映射
                }
            }
        }
        
        boolean first = curAArch64Function.getName().contains("main") ? false : true;
        // 生成函数体的指令
        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            curAArch64Block = (AArch64Block) LabelList.get(basicBlock);
            if (first) {
                curAArch64Function.saveCalleeRegs(curAArch64Block);
                first = false;
            }
            generateBasicBlock(basicBlock);
        }
        
        // 进行寄存器分配
        System.out.println("\n===== 开始对函数 " + functionName + " 进行寄存器分配 =====");
        RegisterAllocator allocator = new RegisterAllocator(curAArch64Function);
        allocator.allocateRegisters();
        
        // 应用寄存器分配后的优化
        PostRegisterOptimizer optimizer = new PostRegisterOptimizer(curAArch64Function);
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
        
        // 获取当前栈大小作为这个变量的偏移量
        long currentStackPos = curAArch64Function.getStackSize();
        
        // 在函数栈上分配空间（如果真正需要）
        curAArch64Function.addStack(ins, size);
        
        // 记录分配的偏移量，用于后续访问
        ptrList.put(ins, currentStackPos);
        
        // 调试信息：打印栈分配情况
        System.err.println("!!! STACK ALLOCATION DEBUG !!! 为变量 " + ins + " 分配栈位置 [sp, #" + currentStackPos + "], 大小: " + size);
        System.err.flush();
        
        // 分配虚拟寄存器用于存储地址，注意不要影响参数寄存器
        if (!predefine) {
            // 创建新的虚拟寄存器，不使用物理寄存器x0-x7
            AArch64VirReg allocaReg = new AArch64VirReg(false);
            RegList.put(ins, allocaReg);
            
            // 计算局部变量地址：SP + 偏移量
            // 检查偏移量是否在范围内，并获取适当的操作数
            AArch64Operand offsetOp = checkImmediate(currentStackPos, ImmediateRange.ADD_SUB, null, predefine);
            
            // 创建加法指令
            if (offsetOp instanceof AArch64Reg) {
                // 如果偏移量转换为了寄存器，使用寄存器形式的ADD
                ArrayList<AArch64Operand> operands = new ArrayList<>();
                operands.add(AArch64CPUReg.getAArch64SpReg());
                operands.add(offsetOp);
                AArch64Binary addInst = new AArch64Binary(operands, allocaReg, AArch64Binary.AArch64BinaryType.add);
                addInstr(addInst, null, predefine);
            } else if (offsetOp instanceof AArch64Imm) {
                // 使用立即数形式的ADD
                AArch64Binary addInst = new AArch64Binary(
                    allocaReg,
                    AArch64CPUReg.getAArch64SpReg(), 
                    (AArch64Imm)offsetOp, 
                    AArch64Binary.AArch64BinaryType.add
                );
                addInstr(addInst, null, predefine);
            }
        }
    }

    private void parseBinaryInst(BinaryInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取IR指令的操作码和操作数
        OpCode opCode = ins.getOpCode();
        Value leftOperand = ins.getLeft();
        Value rightOperand = ins.getRight();
        
        // 检查是否是浮点操作
        boolean isFloatOperation = leftOperand.getType() instanceof FloatType || 
                                 rightOperand.getType() instanceof FloatType;
 
        // 获取或分配寄存器
        AArch64Reg destReg;
        if (isFloatOperation) {
            destReg = new AArch64VirReg(true);
        } else {
            destReg = new AArch64VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 处理左操作数 - 对于大多数二元运算，左操作数必须是寄存器
        boolean leftRequiresReg = opCode == OpCode.MUL || opCode == OpCode.DIV || 
                                 opCode == OpCode.ADD || opCode == OpCode.SUB ||
                                 opCode == OpCode.AND || opCode == OpCode.OR || opCode == OpCode.XOR ||
                                 opCode == OpCode.SHL || opCode == OpCode.LSHR || opCode == OpCode.ASHR ||
                                 opCode == OpCode.REM;
        
        // 确定立即数范围检查类型
        ImmediateRange leftRange = ImmediateRange.DEFAULT;
        ImmediateRange rightRange = ImmediateRange.DEFAULT;
        
        // 对于加减法指令，使用ADD_SUB范围检查
        if (opCode == OpCode.ADD || opCode == OpCode.SUB) {
            leftRange = ImmediateRange.ADD_SUB;
            rightRange = ImmediateRange.ADD_SUB;
        }
        
        AArch64Operand leftOp = processOperand(leftOperand, insList, predefine, isFloatOperation, leftRequiresReg, leftRange);
        
        // 处理右操作数 - 右操作数可以是立即数（除了乘除法）
        boolean rightRequiresReg = opCode == OpCode.MUL || opCode == OpCode.DIV || opCode == OpCode.REM;
        AArch64Operand rightOp = processOperand(rightOperand, insList, predefine, isFloatOperation, rightRequiresReg, rightRange);
        
        // 创建操作数列表
        ArrayList<AArch64Operand> operands = new ArrayList<>();
        operands.add(leftOp);
        operands.add(rightOp);
        
        // 后面是根据操作码生成指令的代码，保持不变
        // 根据操作码和操作数类型生成对应的ARM指令
        if (isFloatOperation) {
            // 浮点运算处理
            switch (opCode) {
                case FADD:
                    // 浮点加法
                    AArch64FBinary faddInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fadd);
                    addInstr(faddInst, insList, predefine);
                    break;
                case FSUB:
                    // 浮点减法
                    AArch64FBinary fsubInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fsub);
                    addInstr(fsubInst, insList, predefine);
                    break;
                case FMUL:
                    // 浮点乘法
                    AArch64FBinary fmulInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fmul);
                    addInstr(fmulInst, insList, predefine);
                    break;
                case FDIV:
                    // 浮点除法
                    AArch64FBinary fdivInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fdiv);
                    addInstr(fdivInst, insList, predefine);
                    break;
                default:
                    System.err.println("不支持的浮点二元操作: " + opCode);
                    break;
            }
        } else {
            // 整数运算处理
            // 处理整数操作
            AArch64Binary.AArch64BinaryType binaryType;
            switch (opCode) {
                case ADD:
                    binaryType = AArch64Binary.AArch64BinaryType.add;
                    break;
                case SUB:
                    binaryType = AArch64Binary.AArch64BinaryType.sub;
                    break;
                case MUL:
                    binaryType = AArch64Binary.AArch64BinaryType.mul;
                    break;
                case DIV:
                    binaryType = AArch64Binary.AArch64BinaryType.sdiv;
                    break;
                // 其他操作类型
                case AND:
                        binaryType = AArch64Binary.AArch64BinaryType.and;
                    break;
                case OR:
                        binaryType = AArch64Binary.AArch64BinaryType.orr;
                    break;
                case XOR:
                    binaryType = AArch64Binary.AArch64BinaryType.eor;
                    break;
                case SHL:
                    binaryType = AArch64Binary.AArch64BinaryType.lsl;
                    break;
                case LSHR:
                    binaryType = AArch64Binary.AArch64BinaryType.lsr;
                    break;
                case ASHR:
                    binaryType = AArch64Binary.AArch64BinaryType.asr;
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
            AArch64Binary binaryInst = new AArch64Binary(operands, destReg, binaryType);
            addInstr(binaryInst, insList, predefine);
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }
    
    

    

    private void parseBrInst(BranchInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        if (ins.isUnconditional()) {
            // 处理无条件跳转
            BasicBlock targetBlock = ins.getTrueBlock();
            AArch64Block armTarget = (AArch64Block) LabelList.get(targetBlock);
            
            // 创建无条件跳转指令
            AArch64Jump jumpInst = new AArch64Jump(armTarget, curAArch64Block);
            addInstr(jumpInst, insList, predefine);
        } else {
            // 处理条件跳转
            Value condition = ins.getCondition();
            BasicBlock trueBlock = ins.getTrueBlock();
            BasicBlock falseBlock = ins.getFalseBlock();
            
            AArch64Block armTrueBlock = (AArch64Block) LabelList.get(trueBlock);
            AArch64Block armFalseBlock = (AArch64Block) LabelList.get(falseBlock);
            
            // 条件指令需要首先处理条件
            if (condition instanceof CompareInstruction) {
                // 如果是比较指令，生成比较指令
                CompareInstruction cmpIns = (CompareInstruction) condition;
                OpCode cmpOp = cmpIns.getCompareType();
                Value left = cmpIns.getLeft();
                Value right = cmpIns.getRight();
                OpCode predicate = cmpIns.getPredicate();
                
                // 获取左右操作数
                AArch64Operand leftOp, rightOp;
                
                // 处理左操作数
                if (left instanceof ConstantInt) {
                    long value = ((ConstantInt) left).getValue();
                    // ARM要求CMP的第一个操作数必须是寄存器
                    AArch64Reg leftReg = new AArch64VirReg(false);
                    // 使用loadLargeImmediate处理可能的大立即数
                    if (value > 65535 || value < -65536) {
                        loadLargeImmediate(leftReg, value, insList, predefine);
                    } else {
                        AArch64Move moveInst = new AArch64Move(leftReg, new AArch64Imm(value), true);
                        addInstr(moveInst, insList, predefine);
                    }
                    leftOp = leftReg;
                } else {
                    leftOp = RegList.get(left);
                }
                
                // 处理右操作数
                if (right instanceof ConstantInt) {
                    long value = ((ConstantInt) right).getValue();
                    rightOp = new AArch64Imm(value);
                } else {
                    rightOp = RegList.get(right);
                }
                
                // 创建比较指令
                AArch64Compare.CmpType cmpType = AArch64Compare.CmpType.cmp;
                if (!cmpIns.isFloatCompare() && rightOp instanceof AArch64Imm) {
                    long value = ((AArch64Imm) rightOp).getValue();
                    if (value < 0) {
                        // 使用CMN指令，比较负值（相当于CMP reg, -imm）
                        cmpType = AArch64Compare.CmpType.cmn;
                        // 将立即数改为正值
                        rightOp = new AArch64Imm(-value);
                    }
                }
                
                AArch64Compare compareInst = new AArch64Compare(leftOp, rightOp, cmpType);
                addInstr(compareInst, insList, predefine);
                
                // 根据比较谓词确定条件分支类型
                AArch64Tools.CondType condType;
                switch (predicate) {
                    case EQ:
                        condType = AArch64Tools.CondType.eq;
                        break;
                    case NE:
                        condType = AArch64Tools.CondType.ne;
                        break;
                    case SGT:
                        condType = AArch64Tools.CondType.gt;
                        break;
                    case SGE:
                        condType = AArch64Tools.CondType.ge;
                        break;
                    case SLT:
                        condType = AArch64Tools.CondType.lt;
                        break;
                    case SLE:
                        condType = AArch64Tools.CondType.le;
                        break;
                    default:
                        System.err.println("不支持的比较谓词: " + predicate);
                        condType = AArch64Tools.CondType.eq;  // 默认为等于
                        break;
                }
                
                // 创建条件跳转到true块
                AArch64Branch branchTrueInst = new AArch64Branch(armTrueBlock, condType);
                branchTrueInst.setPredSucc(curAArch64Block);
                addInstr(branchTrueInst, insList, predefine);
                
                // 无条件跳转到false块作为默认情况
                AArch64Jump jumpFalseInst = new AArch64Jump(armFalseBlock, curAArch64Block);
                addInstr(jumpFalseInst, insList, predefine);
                
            } else {
                // 如果不是直接的比较指令，而是一个布尔值
                AArch64Operand condOp;
                
                if (condition instanceof ConstantInt) {
                    // 对于常量条件，直接生成对应的跳转
                    long value = ((ConstantInt) condition).getValue();
                    if (value != 0) {
                        // 条件为真，直接跳转到true块
                        AArch64Jump jumpTrueInst = new AArch64Jump(armTrueBlock, curAArch64Block);
                        addInstr(jumpTrueInst, insList, predefine);
                    } else {
                        // 条件为假，直接跳转到false块
                        AArch64Jump jumpFalseInst = new AArch64Jump(armFalseBlock, curAArch64Block);
                        addInstr(jumpFalseInst, insList, predefine);
                    }
                    return;
                } else {
                    // 对于变量条件，使用寄存器
                    condOp = RegList.get(condition);
                    
                    // PHI消除后的修复：如果直接查找失败，按名称查找
                    if (condOp == null) {
                        String condName = condition.getName();
                        for (Map.Entry<Value, AArch64Reg> entry : RegList.entrySet()) {
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
                        for (Map.Entry<Value, AArch64Reg> entry : RegList.entrySet()) {
                            System.err.println("  " + entry.getKey().getName() + " -> " + entry.getValue());
                        }
                        // 创建一个临时寄存器作为fallback
                        condOp = new AArch64VirReg(false);
                        System.err.println("使用临时寄存器作为fallback");
                    }
                    
                    // 使用CBZ指令测试条件是否为0
                    
                    // 如果为0则跳转到false块
                    AArch64Cbz cbzInst = new AArch64Cbz((AArch64Reg) condOp, armFalseBlock);
                    cbzInst.setPredSucc(curAArch64Block);
                    addInstr(cbzInst, insList, predefine);
                    
                    // 否则跳转到true块
                    AArch64Jump jumpTrueInst = new AArch64Jump(armTrueBlock, curAArch64Block);
                    addInstr(jumpTrueInst, insList, predefine);
                }
            }
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseCallInst(CallInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        curAArch64Function.saveCallerRegs(curAArch64Block);
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
            if (i == 2) {
                if (callee.getName().contains("memset") && arg instanceof ConstantInt) {
                    ConstantInt arg1 = (ConstantInt) arg;
                    arg = new ConstantInt(arg1.getValue() * 2);
                }
            }
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
                AArch64Reg argReg;
                if (isFloat) {
                    // 浮点参数使用v0-v7寄存器，使用floatArgCount-1是因为已经自增了
                    argReg = AArch64FPUReg.getAArch64FArgReg(floatArgCount-1);
                } else {
                    // 整数参数使用x0-x7寄存器，使用intArgCount-1是因为已经自增了
                    argReg = AArch64CPUReg.getAArch64ArgReg(intArgCount-1);
                }
                 // 处理参数值
                if (arg instanceof ConstantInt) {
                    // 对于常量整数，直接加载到对应的参数寄存器
                    long value = ((ConstantInt) arg).getValue();
                    AArch64Imm imm = new AArch64Imm(value);
                    
                    // 生成加载立即数到寄存器指令
                    AArch64Move moveInst = new AArch64Move(argReg, imm, true);
                    addInstr(moveInst, insList, predefine);
                } else if (arg instanceof ConstantFloat) {
                    // 对于浮点常量，先加载到虚拟寄存器，再移动到参数寄存器
                    double floatValue = ((ConstantFloat) arg).getValue();
                    
                    // 为浮点常量分配虚拟寄存器
                    AArch64VirReg fReg = new AArch64VirReg(true);
                    
                    // 加载浮点常量到虚拟寄存器
                    loadFloatConstant(fReg, floatValue, insList, predefine);
                    
                    // 将虚拟寄存器移动到参数寄存器
                    AArch64Fmov fmovInst = new AArch64Fmov(argReg, fReg);
                    addInstr(fmovInst, insList, predefine);
                } else {
                    // System.out.println(arg); 
                    // System.out.println(arg.getType() instanceof PointerType);
                    // System.out.println(arg instanceof GlobalVariable);
                    // System.out.println(" ");
                    if (!RegList.containsKey(arg)) {
                        // 如果没有关联的寄存器，需要创建一个
                        if (arg instanceof Instruction) {
                            // 递归处理指令 - 对于数组参数需要生成地址计算指令
                            parseInstruction((Instruction) arg, false);
                        } else if (arg instanceof GlobalVariable) {
                            // 处理全局变量参数
                            GlobalVariable globalVar = (GlobalVariable) arg;
                            String globalName = removeLeadingAt(globalVar.getName());
                            AArch64Label label = new AArch64Label(globalName);
                            
                            // 为全局变量分配一个临时寄存器
                            AArch64VirReg tempReg = new AArch64VirReg(false);
                            loadGlobalAddress(tempReg, label, insList, predefine);
                            
                            // 检查参数类型：如果是指针类型，直接使用地址；否则加载值
                            if (arg.getType() instanceof PointerType) {
                                // 对于指针类型，直接使用地址寄存器
                                RegList.put(arg, tempReg);
                            } else {
                                // 对于非指针类型，加载全局变量的值
                                AArch64VirReg valueReg;
                                if (arg.getType() instanceof FloatType) {
                                    valueReg = new AArch64VirReg(true);
                                } else {
                                    valueReg = new AArch64VirReg(false);
                                }
                                
                                AArch64Load loadInst = new AArch64Load(tempReg, new AArch64Imm(0), valueReg);
                                addInstr(loadInst, insList, predefine);
                                
                                RegList.put(arg, valueReg);
                            }
                        } else if (arg.getType() instanceof FloatType) {
                            // 为浮点类型分配寄存器
                            AArch64VirReg floatReg = new AArch64VirReg(true);
                            RegList.put(arg, floatReg);
                        } else {
                            // 为整数类型分配寄存器
                            AArch64VirReg intReg = new AArch64VirReg(false);
                            RegList.put(arg, intReg);
                        }
                    }
                    
                    // 获取参数的寄存器
                    AArch64Reg argValueReg = RegList.get(arg);
                    if (argValueReg != null) {
                        // 检查是否是putfloat调用且参数是浮点类型
                        if (functionName.equals("putfloat") && isFloat && argValueReg instanceof AArch64VirReg) {
                            // putfloat需要单精度参数，但我们内部使用双精度计算
                            // 需要将双精度转换为单精度再传给putfloat
                            AArch64VirReg tempSingleReg = new AArch64VirReg(true);  // 临时单精度寄存器
                            
                            // 添加fcvt指令从双精度转换为单精度
                            AArch64Cvt cvtInst = new AArch64Cvt(argValueReg, AArch64Cvt.CvtType.FCVT_D2S, tempSingleReg);
                            addInstr(cvtInst, insList, predefine);
                            
                            // 然后将单精度寄存器移动到参数寄存器
                            AArch64Move moveInst = new AArch64Move(argReg, tempSingleReg, false);
                            addInstr(moveInst, insList, predefine);
                        } else {
                            AArch64Move moveInst = new AArch64Move(argReg, argValueReg, false);
                            addInstr(moveInst, insList, predefine);
                        }
                    } else {
                        // 如果仍然没有寄存器，使用零寄存器
                        System.err.println("警告: 参数 " + arg + " 没有关联的寄存器，使用零寄存器");
                        AArch64Move moveInst = new AArch64Move(argReg, AArch64CPUReg.getZeroReg(), false);
                        addInstr(moveInst, insList, predefine);
                    }
                }
                curAArch64Function.addRegArg(arg, argReg);
                // 注意：不要覆盖数组的地址寄存器映射
                // 对于非指令类型的参数才更新RegList映射
                // if (!(arg instanceof Instruction) && !(arg instanceof GlobalVariable)) {
                //     RegList.put(arg, argReg);
                // }
            } else {
                // 使用栈传递参数
                stackOffset += 8;
                stackArgList.add(arg);
                curAArch64Function.addStackArg(arg, stackOffset);
            }
        }
        // 如果栈偏移量大于0，则需要减去栈偏移量
        if (stackOffset > 0) {
            // 使用通用的立即数检查机制
            AArch64Operand sizeOp = checkImmediate(stackOffset, ImmediateRange.ADD_SUB, insList, predefine);
            
            // 根据操作数类型创建适当的加法指令
            if (sizeOp instanceof AArch64Reg) {
                // 如果栈大小被加载到寄存器，使用寄存器形式的add指令
                ArrayList<AArch64Operand> operands = new ArrayList<>();
                operands.add(AArch64CPUReg.getAArch64SpReg());
                operands.add(sizeOp);
                AArch64Binary addInst = new AArch64Binary(operands, AArch64CPUReg.getAArch64SpReg(), AArch64Binary.AArch64BinaryType.sub);
                addInstr(addInst, insList, predefine);
            } else {
                // 使用立即数形式的add指令
                AArch64Binary addInst = new AArch64Binary(
                    AArch64CPUReg.getAArch64SpReg(), 
                    AArch64CPUReg.getAArch64SpReg(), 
                    (AArch64Imm)sizeOp, 
                    AArch64Binary.AArch64BinaryType.sub
                );
                addInstr(addInst, insList, predefine);
            }
        }


        if (stackArgList.size() > 0) {
            int tempStackOffset = 0;
            for (Value arg : stackArgList) {
                if (arg instanceof ConstantInt) {
                    // 对于常量，先加载到临时寄存器再存入栈
                    long value = ((ConstantInt) arg).getValue();
                    AArch64VirReg tempReg = new AArch64VirReg(false);
                    AArch64Imm imm = new AArch64Imm(value);
                    
                    // 生成加载立即数到临时寄存器指令
                    AArch64Move moveInst = new AArch64Move(tempReg, imm, true);
                    addInstr(moveInst, insList, predefine);
                    
                                             // 将临时寄存器值存储到栈上
                          AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                          
                          // 使用辅助方法处理栈偏移量
                          handleLargeStackOffset(spReg, tempStackOffset, tempReg, false, false, insList, predefine);
                } else if (arg instanceof ConstantFloat) {
                    // 处理浮点常量
                    double floatValue = ((ConstantFloat) arg).getValue();
                    
                    // 为浮点常量分配虚拟寄存器
                    AArch64VirReg fReg = new AArch64VirReg(true);
                    
                    // 加载浮点常量到虚拟寄存器
                    loadFloatConstant(fReg, floatValue, insList, predefine);
                    
                                          // 将虚拟寄存器值存储到栈上
                      AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                      
                      // 使用辅助方法处理栈偏移量
                      handleLargeStackOffset(spReg, tempStackOffset, fReg, false, true, insList, predefine);
                } else {
                                            // 变量参数，检查是否已有寄存器
                    if (!RegList.containsKey(arg)) {
                        // 如果没有关联的寄存器，需要创建一个
                        if (arg instanceof Instruction) {
                            // 递归处理指令 - 对于数组参数需要生成地址计算指令
                            parseInstruction((Instruction) arg, false);
                        } else if (arg instanceof GlobalVariable) {
                            // 处理全局变量参数
                            GlobalVariable globalVar = (GlobalVariable) arg;
                            String globalName = removeLeadingAt(globalVar.getName());
                            AArch64Label label = new AArch64Label(globalName);
                            
                            // 为全局变量分配一个临时寄存器
                            AArch64VirReg tempReg = new AArch64VirReg(false);
                            loadGlobalAddress(tempReg, label, insList, predefine);
                            
                            // 加载全局变量的值
                            AArch64VirReg valueReg;
                            if (arg.getType() instanceof FloatType) {
                                valueReg = new AArch64VirReg(true);
                            } else {
                                valueReg = new AArch64VirReg(false);
                            }
                            
                            AArch64Load loadInst = new AArch64Load(tempReg, new AArch64Imm(0), valueReg);
                            addInstr(loadInst, insList, predefine);
                            
                            // 关联全局变量与其值寄存器
                            RegList.put(arg, valueReg);
                        } else if (arg.getType() instanceof FloatType) {
                            // 为浮点类型分配寄存器
                            AArch64VirReg floatReg = new AArch64VirReg(true);
                            RegList.put(arg, floatReg);
                        } else {
                            // 为整数类型分配寄存器
                            AArch64VirReg intReg = new AArch64VirReg(false);
                            RegList.put(arg, intReg);
                        }
                    }
                    
                    // 获取参数的寄存器
                    AArch64Reg argValueReg = RegList.get(arg);
                    if (argValueReg != null) {
                                                 // 将变量寄存器的值存储到栈上
                          AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                         
                         // 使用辅助方法处理栈偏移量
                         handleLargeStackOffset(spReg, tempStackOffset, argValueReg, false, arg.getType() instanceof FloatType, insList, predefine);
                    } else {
                        // 如果仍然没有寄存器，使用零寄存器
                        System.err.println("警告: 栈参数 " + arg + " 没有关联的寄存器，使用零寄存器");
                        AArch64VirReg tempReg = new AArch64VirReg(false);
                        AArch64Move moveInst = new AArch64Move(tempReg, AArch64CPUReg.getZeroReg(), false);
                        addInstr(moveInst, insList, predefine);
                        
                        // 将临时寄存器值存储到栈上
                        AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                        
                        // 使用辅助方法处理栈偏移量
                        handleLargeStackOffset(spReg, tempStackOffset, tempReg, false, false, insList, predefine);
                    }
                }
                tempStackOffset += 8;
            }
        }
        // 创建调用标签
        if (AArch64MyLib.has64BitVersion(functionName)) {
            AArch64MyLib.markFunction64Used(functionName);
            functionName = AArch64MyLib.get64BitFunctionName(functionName);
        }
        
        AArch64Label functionLabel = new AArch64Label(functionName);
        
        // 生成调  用指令
        AArch64Call callInst = new AArch64Call(functionLabel);
        
        // 记录使用的寄存器（用于调用者保存寄存器的保存和恢复）
        // for (Value value : RegList.keySet()) {
        //     AArch64Reg reg = RegList.get(value);
        //     if (reg instanceof AArch64CPUReg && ((AArch64CPUReg) reg).canBeReorder()) {
        //         callInst.addUsedReg(reg);
        //     }
        // }
        
        addInstr(callInst, insList, predefine);
        
        // 处理返回值
        if (!ins.isVoidCall()) {
            AArch64Reg returnReg, resultReg;
            if (ins.getCallee().getReturnType().isIntegerType()) {
                returnReg = AArch64CPUReg.getAArch64CPURetValueReg();
                resultReg = new AArch64VirReg(false);
            } else {
                returnReg = AArch64FPUReg.getAArch64FPURetValueReg();
                resultReg = new AArch64VirReg(true);
            }
            
            // 为调用指令分配一个寄存器存储返回值
            RegList.put(ins, resultReg);
            
            // 将返回值从x0/v0移动到结果寄存器
            if (returnReg != null) {  // 确保返回寄存器不为空
                // 检查是否是getfloat调用
                if (functionName.equals("getfloat") && ins.getCallee().getReturnType() instanceof FloatType) {
                    // getfloat返回单精度浮点数在s0，但我们内部使用双精度计算
                    // 需要将单精度转换为双精度
                    AArch64VirReg tempSingleReg = new AArch64VirReg(true);  // 临时单精度寄存器
                    
                    // 首先将s0移动到临时单精度寄存器
                    AArch64Move moveFromS0Inst = new AArch64Move(tempSingleReg, returnReg, false);
                    addInstr(moveFromS0Inst, insList, predefine);
                    
                    // 然后添加fcvt指令从单精度转换为双精度
                    AArch64Cvt cvtInst = new AArch64Cvt(tempSingleReg, AArch64Cvt.CvtType.FCVT_S2D, resultReg);
                    addInstr(cvtInst, insList, predefine);
                } else {
                    AArch64Move moveReturnInst = new AArch64Move(resultReg, returnReg, false);
                    addInstr(moveReturnInst, insList, predefine);
                }
            } else {
                System.err.println("错误: 返回寄存器为空，跳过移动返回值");
            }
        }

        if (stackOffset > 0) {
            // 使用通用的立即数检查机制
            AArch64Operand sizeOp = checkImmediate(stackOffset, ImmediateRange.ADD_SUB, insList, predefine);
            
            // 根据操作数类型创建适当的加法指令
            if (sizeOp instanceof AArch64Reg) {
                // 如果栈大小被加载到寄存器，使用寄存器形式的add指令
                ArrayList<AArch64Operand> operands = new ArrayList<>();
                operands.add(AArch64CPUReg.getAArch64SpReg());
                operands.add(sizeOp);
                AArch64Binary addInst = new AArch64Binary(operands, AArch64CPUReg.getAArch64SpReg(), AArch64Binary.AArch64BinaryType.add);
                addInstr(addInst, insList, predefine);
            } else {
                // 使用立即数形式的add指令
                AArch64Binary addInst = new AArch64Binary(
                    AArch64CPUReg.getAArch64SpReg(), 
                    AArch64CPUReg.getAArch64SpReg(), 
                    (AArch64Imm)sizeOp, 
                    AArch64Binary.AArch64BinaryType.add
                );
                addInstr(addInst, insList, predefine);
            }
        }
        
        curAArch64Function.loadCallerRegs(curAArch64Block);

        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseConversionInst(ConversionInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取源值和目标类型
        Value source = ins.getSource();
        Type targetType = ins.getType();
        OpCode conversionType = ins.getConversionType();
        
        // 获取或分配源寄存器
        AArch64Reg srcReg;
        if (source instanceof ConstantInt) {
            // 对于常量，需要先加载到寄存器中
            long value = ((ConstantInt) source).getValue();
            AArch64Imm imm = new AArch64Imm(value);
            srcReg = new AArch64VirReg(false);
            
            // 创建一个移动指令将常量加载到寄存器
            AArch64Move moveInst = new AArch64Move(srcReg, imm, false);
            addInstr(moveInst, insList, predefine);
        } else if (source instanceof ConstantFloat) {
            double value = ((ConstantFloat) source).getValue();
            AArch64VirReg fReg = new AArch64VirReg(true);
            loadFloatConstant(fReg, value, insList, predefine);
            srcReg = fReg;
        } else if (RegList.containsKey(source)) {
            srcReg = RegList.get(source);
        } else {
            srcReg = null;
            System.out.println("error no virReg: " + source);
        }
        
        // 分配目标寄存器
        AArch64Reg destReg;
        if (targetType.isFloatType()) {
            // 目标是浮点类型，使用浮点寄存器
            destReg = new AArch64VirReg(true);
        } else {
            // 目标是整数类型，使用通用寄存器
            destReg = new AArch64VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 根据转换类型创建相应的转换指令
        AArch64Cvt.CvtType armCvtType;
        
        if (ins.isIntToFloat()) {
            // 整数到浮点转换
            if (conversionType == OpCode.SITOFP) {
                armCvtType = AArch64Cvt.CvtType.SCVTF;  // 有符号整数转浮点
            } else {
                armCvtType = AArch64Cvt.CvtType.UCVTF;  // 无符号整数转浮点
            }
        } else if (ins.isFloatToInt()) {
            // 浮点到整数转换
            if (conversionType == OpCode.FPTOSI) {
                armCvtType = AArch64Cvt.CvtType.FCVTZS; // 浮点转有符号整数，向零舍入
            } else {
                armCvtType = AArch64Cvt.CvtType.FCVTZU; // 浮点转无符号整数，向零舍入
            }
        } else if (ins.isTruncation()) {
            // 位截断操作
            // ARMv8没有直接的位截断指令，可能需要使用AND指令掩码来实现
            ArrayList<AArch64Operand> andOps = new ArrayList<>();
            andOps.add(srcReg);
            
            // 创建掩码，根据目标类型决定
            long mask;
            if (targetType instanceof IntegerType) {
                int bits = ((IntegerType) targetType).getBitWidth();
                mask = (1L << bits) - 1;
            } else {
                mask = 0xFFFFFFFFL; // 默认32位掩码
            }
            
            AArch64Imm maskImm = new AArch64Imm(mask);
            andOps.add(maskImm);
            
            AArch64Binary andInst = new AArch64Binary(andOps, destReg, AArch64Binary.AArch64BinaryType.and);
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
                AArch64Move sextInst = new AArch64Move(destReg, srcReg, false);
                addInstr(sextInst, insList, predefine);
            } else {
                // 零扩展
                // ARMv8可以通过UBFX或者位掩码实现，这里简单处理
                AArch64Move zextInst = new AArch64Move(destReg, srcReg, false); // 使用64位模式
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
            AArch64Move moveInst = new AArch64Move(destReg, srcReg, false);
            addInstr(moveInst, insList, predefine);
            
            // 记录指令列表并返回
            if (predefine) {
                predefines.put(ins, insList);
            }
            return;
        }
        
        // 创建转换指令
        AArch64Cvt cvtInst = new AArch64Cvt(srcReg, armCvtType, destReg);
        addInstr(cvtInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseLoad(LoadInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取要加载的指针
        Value pointer = ins.getPointer();
        // 获取加载的类型
        Type loadedType = ins.getLoadedType();
        // 判断是否是浮点数类型
        boolean isFloat = loadedType instanceof FloatType;
        
        // 为结果分配目标寄存器
        AArch64Reg destReg;
        if (isFloat) {
            // 浮点数应该使用浮点寄存器
            destReg = new AArch64VirReg(true);
        } else {
            // 整数使用通用寄存器
            destReg = new AArch64VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 处理指针操作数
        AArch64Reg baseReg = null;
        AArch64Operand offsetOp = new AArch64Imm(0); // 默认偏移量为0
        
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
            //     baseReg = AArch64CPUReg.getAArch64SpReg();
            //     offsetOp = new AArch64Imm(ptrList.get(pointer).intValue());
            // } 
            else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            // 对于全局变量，使用标签
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            AArch64Label label = new AArch64Label(globalName);
            
            // 为全局变量地址分配寄存器
            baseReg = new AArch64VirReg(false);
            
            // 创建加载地址指令
            loadGlobalAddress(baseReg, label, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果是局部变量分配指令
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction)pointer, true);
            }
            
            // 使用栈指针加上偏移量
            baseReg = AArch64CPUReg.getAArch64SpReg();
            long offset = ptrList.get(pointer);
            
            // 使用通用的立即数检查机制
            offsetOp = checkImmediate(offset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
        } else if (pointer instanceof Argument) {
            // 处理函数参数，为其分配寄存器
            Argument arg = (Argument) pointer;
            if (curAArch64Function.getRegArg(arg) != null) {
                baseReg = curAArch64Function.getRegArg(arg);
            } else if (curAArch64Function.getStackArg(arg) != null) {
                baseReg = new AArch64VirReg(false);
                long stackParamOffset = curAArch64Function.getStackArg(arg); // 前8个在寄存器中
                
                // 创建加载指令：从FP+偏移量加载到临时寄存器
                // 使用通用的立即数检查机制
                AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                
                // 根据偏移量类型创建适当的加载指令
                if (paramOffsetOp instanceof AArch64Reg) {
                    // 如果偏移量被加载到寄存器，需要先计算地址
                    AArch64VirReg addrReg = new AArch64VirReg(false);
                    ArrayList<AArch64Operand> operands = new ArrayList<>();
                    operands.add(AArch64CPUReg.getAArch64FPReg());
                    operands.add(paramOffsetOp);
                    AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                    addInstr(addInst, insList, predefine);
                    
                    // 然后从计算出的地址加载
                    AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), baseReg);
                    addInstr(loadParamInst, insList, predefine);
                } else {
                    // 如果偏移量是立即数，直接使用偏移量加载
                    AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                        paramOffsetOp, baseReg);
                    addInstr(loadParamInst, insList, predefine);
                }
            }
            
            RegList.put(pointer, baseReg);
        } else {
            // 其他类型的指针，尝试获取关联的寄存器
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                } else if (pointer.getType() instanceof PointerType) {
                    // 处理指针类型
                    AArch64Reg ptrReg = new AArch64VirReg(false);
                    RegList.put(pointer, ptrReg);
                } else if (pointer.getType() instanceof FloatType) {
                    // 处理浮点类型
                    AArch64Reg floatReg = new AArch64VirReg(true);
                    RegList.put(pointer, floatReg);
                } else if (pointer.getType() instanceof IntegerType) {
                    // 处理整数类型
                    AArch64Reg intReg = new AArch64VirReg(false);
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
        
        // 使用辅助方法处理加载操作
        if (offsetOp instanceof AArch64Imm) {
            // 立即数类型，可以使用辅助方法
            handleLargeStackOffset(baseReg, ((AArch64Imm)offsetOp).getValue(), destReg, true, isFloat, insList, predefine);
        } else {
            // 其他类型，直接创建加载指令
            AArch64Load loadInst = new AArch64Load(baseReg, offsetOp, destReg);
            addInstr(loadInst, insList, predefine);
        }
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parsePtrInst(GetElementPtrInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取基地址指针和索引
        Value pointer = ins.getPointer();
        List<Value> indices = ins.getIndices();
        
        // 为结果分配目标寄存器
        AArch64Reg destReg = new AArch64VirReg(false);
        RegList.put(ins, destReg);
        
        // 处理基地址指针
        AArch64Reg baseReg = null;

        if (pointer instanceof GlobalVariable) {
            // 如果基地址是全局变量
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            AArch64Label label = new AArch64Label(globalName);
            
            // 加载全局变量地址
            baseReg = new AArch64VirReg(false);
            loadGlobalAddress(baseReg, label, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果基地址是局部变量
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction) pointer, true);
            }
            
            // 计算局部变量地址：SP + 偏移
            baseReg = new AArch64VirReg(false);
            
            // 使用立即数检查来处理可能的大偏移量
            long offset = ptrList.get(pointer);
            AArch64Operand offsetOp = checkImmediate(offset, ImmediateRange.ADD_SUB, insList, predefine);
            
            if (offsetOp instanceof AArch64Reg) {
                // 如果偏移量被加载到寄存器，创建寄存器形式的add指令
                ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                addOperands.add(AArch64CPUReg.getAArch64SpReg());
                addOperands.add(offsetOp);
                AArch64Binary addInst = new AArch64Binary(addOperands, baseReg, AArch64Binary.AArch64BinaryType.add);
                addInstr(addInst, insList, predefine);
            } else {
                // 如果偏移量在范围内，创建立即数形式的add指令
                AArch64Binary addInst = new AArch64Binary(baseReg, AArch64CPUReg.getAArch64SpReg(), (AArch64Imm)offsetOp, AArch64Binary.AArch64BinaryType.add);
                addInstr(addInst, insList, predefine);
            }
        } else if (pointer instanceof Argument) {
            // 处理函数参数，为其分配寄存器
            Argument arg = (Argument) pointer;
            if (curAArch64Function.getRegArg(arg) != null) {
                baseReg = curAArch64Function.getRegArg(arg);
            } else if (curAArch64Function.getStackArg(arg) != null) {
                baseReg = new AArch64VirReg(false);
                long stackParamOffset = curAArch64Function.getStackArg(arg); // 前8个在寄存器中
                
                // 创建加载指令：从FP+偏移量加载到临时寄存器
                // 使用通用的立即数检查机制
                AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                
                // 根据偏移量类型创建适当的加载指令
                if (paramOffsetOp instanceof AArch64Reg) {
                    // 如果偏移量被加载到寄存器，需要先计算地址
                    AArch64VirReg addrReg = new AArch64VirReg(false);
                    ArrayList<AArch64Operand> operands = new ArrayList<>();
                    operands.add(AArch64CPUReg.getAArch64FPReg());
                    operands.add(paramOffsetOp);
                    AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                    addInstr(addInst, insList, predefine);
                    
                    // 然后从计算出的地址加载
                    AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), baseReg);
                    addInstr(loadParamInst, insList, predefine);
                } else {
                    // 如果偏移量是立即数，直接使用偏移量加载
                    AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                        paramOffsetOp, baseReg);
                    addInstr(loadParamInst, insList, predefine);
                }
            }
            RegList.put(pointer, baseReg);
        } else {
            // 其他类型的指针（如其他指令的结果）
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                } else if (pointer.getType() instanceof PointerType) {
                    // 处理指针类型
                    AArch64Reg ptrReg = new AArch64VirReg(false);
                    RegList.put(pointer, ptrReg);
                } else if (pointer.getType() instanceof FloatType) {
                    // 处理浮点类型
                    AArch64Reg floatReg = new AArch64VirReg(true);
                    RegList.put(pointer, floatReg);
                } else if (pointer.getType() instanceof IntegerType) {
                    // 处理整数类型
                    AArch64Reg intReg = new AArch64VirReg(false);
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
        AArch64Move moveInst = new AArch64Move(destReg, baseReg, false);
        addInstr(moveInst, insList, predefine);
        
        // 处理数组索引 - 对于数组访问，不应该跳过任何索引
        // 处理多维数组索引
        if (indices.size() > 0) {
            // 获取数组的类型信息
            // Type arrayType = ((PointerType)pointer.getType()).getElementType();
            
            // 遍历所有索引
            for (int i = 0; i < indices.size(); i++) {
                Value indexValue = indices.get(i);
                
                // 获取当前维度的元素大小
                int elementSize = 8;
                // if (arrayType instanceof PointerType) {
                //     // 如果是多层指针，元素大小是指针大小（通常为8字节）
                //     elementSize = 8;
                //     arrayType = ((PointerType) arrayType).getElementType();
                // } else {
                //     // 对于全局变量的整型数组，强制使用8字节元素大小
                //     if (pointer instanceof GlobalVariable && !(arrayType instanceof FloatType)) {
                //         elementSize = 8;  // 全局变量整型强制使用64位
                //     } else {
                //         // 基本类型元素使用原始大小
                //         elementSize = arrayType.getSize();
                //     }
                // }
                
                // 处理索引值
                if (indexValue instanceof ConstantInt) {
                    // 对于常量索引，直接计算偏移
                    long indexVal = ((ConstantInt) indexValue).getValue();
                    long offset = indexVal * elementSize;
                    
                    // 如果偏移非零，添加到当前地址
                    if (offset != 0) {
                        // 使用checkImmediate检查偏移量是否在ADD指令的立即数范围内
                        AArch64Operand offsetOp = checkImmediate(offset, ImmediateRange.ADD_SUB, insList, predefine);
                        
                        if (offsetOp instanceof AArch64Imm) {
                            // 偏移量在范围内，使用立即数形式
                            AArch64Binary addInst = new AArch64Binary(destReg, destReg, (AArch64Imm)offsetOp, AArch64Binary.AArch64BinaryType.add);
                            addInstr(addInst, insList, predefine);
                        } else {
                            // 偏移量被加载到寄存器，使用寄存器形式
                            ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                            addOperands.add(destReg);
                            addOperands.add(offsetOp);
                            AArch64Binary addInst = new AArch64Binary(addOperands, destReg, AArch64Binary.AArch64BinaryType.add);
                            addInstr(addInst, insList, predefine);
                        }
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
                    AArch64Reg indexReg = RegList.get(indexValue);
                    
                    // 为计算结果分配临时寄存器
                    AArch64Reg tempReg = new AArch64VirReg(false);
                    
                    if (elementSize != 1) {
                        // 计算 索引 * 元素大小
                        if (isPowerOfTwo(elementSize)) {
                            // 如果元素大小是2的幂，使用左移操作
                            int shiftAmount = (int)(Math.log(elementSize) / Math.log(2));
                            AArch64Binary lslInst = new AArch64Binary(tempReg, indexReg, new AArch64Imm(shiftAmount), AArch64Binary.AArch64BinaryType.lsl);
                            addInstr(lslInst, insList, predefine);
                        } else {
                            // 否则使用乘法操作
                            // 首先加载元素大小到临时寄存器，检查是否需要使用大立即数加载
                            if (elementSize > 65535 || elementSize < -65536) {
                                loadLargeImmediate(tempReg, elementSize, insList, predefine);
                            } else {
                                AArch64Move moveElemSizeInst = new AArch64Move(tempReg, new AArch64Imm(elementSize), false);
                                addInstr(moveElemSizeInst, insList, predefine);
                            }
                            
                            // 执行乘法计算
                            ArrayList<AArch64Operand> mulOperands = new ArrayList<>();
                            mulOperands.add(indexReg);
                            mulOperands.add(tempReg);
                            
                            AArch64Binary mulInst = new AArch64Binary(mulOperands, tempReg, AArch64Binary.AArch64BinaryType.mul);
                            addInstr(mulInst, insList, predefine);
                        }
                    } else {
                        // 如果元素大小为1，索引值就是偏移量
                        AArch64Move moveIndexInst = new AArch64Move(tempReg, indexReg, false);
                        addInstr(moveIndexInst, insList, predefine);
                    }
                    
                    // 将偏移量添加到基址
                    ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                    addOperands.add(destReg);
                    addOperands.add(tempReg);
                    
                    AArch64Binary addInst = new AArch64Binary(addOperands, destReg, AArch64Binary.AArch64BinaryType.add);
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
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 检查是否有返回值
        if (!ins.isVoidReturn()) {
            // 获取返回值
            Value returnValue = ins.getReturnValue();
            
            // 获取返回寄存器 - ARM调用规范中整数返回值必须在x0中，浮点返回值在v0中
            AArch64Reg returnReg;
            if (returnValue.getType() instanceof FloatType) {
                returnReg = AArch64FPUReg.getAArch64FPURetValueReg(); // v0
            } else {
                returnReg = AArch64CPUReg.getAArch64CPURetValueReg(); // x0
            }
            
            // 将值加载到返回寄存器中
            if (returnValue instanceof ConstantInt) {
                // 对于常量整数，移动到返回寄存器，处理大立即数
                long value = ((ConstantInt) returnValue).getValue();
                if (value > 65535 || value < -65536) {
                    loadLargeImmediate(returnReg, value, insList, predefine);
                } else {
                    addInstr(new AArch64Move(returnReg, new AArch64Imm(value), false), insList, predefine);
                }
            } else if (returnValue instanceof ConstantFloat) {
                // 对于常量浮点数，加载到返回寄存器
                float value = (float)((ConstantFloat) returnValue).getValue();
                loadFloatConstant(returnReg, value, insList, predefine);
            } else if (returnValue instanceof Argument) {
                // 处理参数作为返回值 - 直接使用辅助方法获取参数值寄存器
                Argument arg = (Argument) returnValue;
                AArch64Reg paramReg = ensureArgumentAArch64Reg(arg, insList, predefine);
                
                // 将参数值移动到返回寄存器（如果需要）
                if (paramReg != null && !paramReg.equals(returnReg)) {
                    addInstr(new AArch64Move(returnReg, paramReg, false), 
                        insList, predefine);
                }
                // 如果参数已经在返回寄存器中，无需操作
            } else {
                // 处理其他类型的返回值
                AArch64Operand operand = processOperand(returnValue, insList, predefine, 
                                                     returnValue.getType() instanceof FloatType, false);
                if (operand instanceof AArch64Reg) {
                    // 如果操作数是寄存器且不是返回寄存器，则需要移动
                    if (!operand.equals(returnReg)) {
                        addInstr(new AArch64Move(returnReg, operand, false), 
                            insList, predefine);
                    }
                } else if (operand instanceof AArch64Imm) {
                    // 如果是立即数，直接加载到返回寄存器
                    addInstr(new AArch64Move(returnReg, operand, false), insList, predefine);
                }
            }
        }
        
        //恢复维护的寄存器
        if (!curAArch64Function.getName().contains("main")) {
            curAArch64Function.loadCalleeRegs(curAArch64Block);
        }


        // 获取函数的栈大小
        Long localSize = curAArch64Function.getStackSize();
        ArrayList<AArch64Operand> operands = new ArrayList<>();
        operands.add(AArch64CPUReg.getAArch64SpReg());

        //栈溢出处理
        long value = curAArch64Function.getStackSpace().getOffset();
        if (ImmediateRange.ADD_SUB.isInRange(value)) {
            operands.add(curAArch64Function.getStackSpace());
        } else {
            // 超出范围，加载到寄存器
            AArch64VirReg tempReg = new AArch64VirReg(false);
            if (value > 65535 || value < -65536) {
                loadLargeImmediate(tempReg, value, insList, predefine);
            } else {
                AArch64Move moveInst = new AArch64Move(tempReg, curAArch64Function.getStackSpace(), true);
                addInstr(moveInst, insList, predefine);
            }
            operands.add(tempReg);
        }
        AArch64Binary addSpInst = new AArch64Binary(operands, AArch64CPUReg.getAArch64SpReg(), AArch64Binary.AArch64BinaryType.add);
        addInstr(addSpInst, insList, predefine);
        

        
        // 恢复FP(x29)和LR(x30)寄存器，并调整SP
        AArch64LoadPair ldpInst = new AArch64LoadPair(
            AArch64CPUReg.getAArch64SpReg(),
            new AArch64Imm(16), 
            AArch64CPUReg.getAArch64FPReg(),  // x29
            AArch64CPUReg.getAArch64RetReg(),  // x30/LR
            false,  // 不是32位寄存器
            true    // 后索引模式
        );
        addInstr(ldpInst, insList, predefine);
        
        // 添加返回指令
        AArch64Ret retInst = new AArch64Ret();
        addInstr(retInst, insList, predefine);
        
        // 标记当前基本块包含返回指令
        if (!predefine) {
            curAArch64Block.setHasReturnInstruction(true);
        }
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseStore(StoreInstruction ins, boolean predefine) {
        // 创建指令列表，如果是预定义阶段则为非空列表
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取要存储的值和目标指针
        Value valueToStore = ins.getValue();
        Value pointer = ins.getPointer();
        
        // 处理待存储值，获取或创建包含值的寄存器
        AArch64Reg valueReg;
        
        if (valueToStore instanceof ConstantInt) {
            // 对于整数常量，创建一个临时寄存器并加载值
            valueReg = new AArch64VirReg(false);
            long value = ((ConstantInt)valueToStore).getValue();
            // 检查是否需要使用大立即数加载
            if (value > 65535 || value < -65536) {
                loadLargeImmediate(valueReg, value, insList, predefine);
            } else {
                AArch64Move moveInst = new AArch64Move(valueReg, new AArch64Imm((int)value), false);
                addInstr(moveInst, insList, predefine);
            }
        } 
        else if (valueToStore instanceof ConstantFloat) {
            valueReg = new AArch64VirReg(true);
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
                           new AArch64VirReg(true) : new AArch64VirReg(false);
                
                // 检查参数是否在寄存器中
                if (curAArch64Function.getRegArg(arg) != null) {
                    // 参数在寄存器中，直接使用该寄存器
                    AArch64Reg argReg = curAArch64Function.getRegArg(arg);
                    AArch64Move moveInst = new AArch64Move(valueReg, argReg, valueReg instanceof AArch64FPUReg);
                    addInstr(moveInst, insList, predefine);
                } else if (curAArch64Function.getStackArg(arg) != null) {
                    // 参数在栈上，使用FP+偏移量加载
                    long stackParamOffset = curAArch64Function.getStackArg(arg);
                    
                    // 使用通用的立即数检查机制
                    AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                    
                    // 根据偏移量类型创建适当的加载指令
                    if (paramOffsetOp instanceof AArch64Reg) {
                        // 如果偏移量被加载到寄存器，需要先计算地址
                        AArch64VirReg addrReg = new AArch64VirReg(false);
                        ArrayList<AArch64Operand> operands = new ArrayList<>();
                        operands.add(AArch64CPUReg.getAArch64FPReg());
                        operands.add(paramOffsetOp);
                        AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                        addInstr(addInst, insList, predefine);
                        
                        // 然后从计算出的地址加载
                        AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), valueReg);
                        addInstr(loadParamInst, insList, predefine);
                    } else {
                        // 如果偏移量是立即数，直接使用偏移量加载
                        AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                            paramOffsetOp, valueReg);
                        addInstr(loadParamInst, insList, predefine);
                    }
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
                    AArch64Reg floatReg = new AArch64VirReg(true);
                    
                    RegList.put(valueToStore, floatReg);
                } else if (valueToStore.getType() instanceof IntegerType) {
                    // 处理整数变量
                    // 分配一个通用寄存器
                    AArch64Reg cpuReg = new AArch64VirReg(false);
                    RegList.put(valueToStore, cpuReg);
                } else {
                    throw new RuntimeException("未处理的存储值类型: " + valueToStore);
                }
            }
            valueReg = RegList.get(valueToStore);
        }
        
        // 处理指针操作数
        AArch64Reg baseReg = new AArch64VirReg(false);  // 默认分配一个寄存器
        AArch64Operand offsetOp = new AArch64Imm(0); // 默认偏移量为0
        
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
                baseReg = AArch64CPUReg.getAArch64SpReg();
                // 检查偏移量是否在内存操作的立即数范围内
                long offset = ptrList.get(pointer);
                offsetOp = checkImmediate(offset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
            } else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            // 对于全局变量，使用标签
            String globalName = removeLeadingAt(((GlobalVariable)pointer).getName());
            AArch64Label label = new AArch64Label(globalName);
            
            // 为全局变量地址分配寄存器
            baseReg = new AArch64VirReg(false);
            
            // 创建加载地址指令
            loadGlobalAddress(baseReg, label, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            // 如果是局部变量分配指令
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction)pointer, true);
            }
            
            // 使用栈指针加上偏移量
            baseReg = AArch64CPUReg.getAArch64SpReg();
            long offset = ptrList.get(pointer);
            
            // 使用通用的立即数检查机制
            offsetOp = checkImmediate(offset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
        } else if (pointer instanceof Argument) {
            // 处理函数参数，为其分配寄存器
            Argument arg = (Argument) pointer;
            if (curAArch64Function.getRegArg(arg) != null) {
                baseReg = curAArch64Function.getRegArg(arg);
            } else if (curAArch64Function.getStackArg(arg) != null) {
                baseReg = new AArch64VirReg(false);
                long stackParamOffset = curAArch64Function.getStackArg(arg); // 前8个在寄存器中
                
                // 创建加载指令：从FP+偏移量加载到临时寄存器
                // 使用通用的立即数检查机制
                AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                
                // 根据偏移量类型创建适当的加载指令
                if (paramOffsetOp instanceof AArch64Reg) {
                    // 如果偏移量被加载到寄存器，需要先计算地址
                    AArch64VirReg addrReg = new AArch64VirReg(false);
                    ArrayList<AArch64Operand> operands = new ArrayList<>();
                    operands.add(AArch64CPUReg.getAArch64FPReg());
                    operands.add(paramOffsetOp);
                    AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                    addInstr(addInst, insList, predefine);
                    
                    // 然后从计算出的地址加载
                    AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), baseReg);
                    addInstr(loadParamInst, insList, predefine);
                } else {
                    // 如果偏移量是立即数，直接使用偏移量加载
                    AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                        paramOffsetOp, baseReg);
                    addInstr(loadParamInst, insList, predefine);
                }
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
        
        // 使用辅助方法处理存储操作
        if (offsetOp instanceof AArch64Imm) {
            // 立即数类型，可以使用辅助方法
            boolean isFloat = valueToStore.getType() instanceof FloatType;
            handleLargeStackOffset(baseReg, ((AArch64Imm)offsetOp).getValue(), valueReg, false, isFloat, insList, predefine);
        } else {
            // 其他类型，直接创建存储指令
            AArch64Store storeInst = new AArch64Store(valueReg, baseReg, offsetOp);
            addInstr(storeInst, insList, predefine);
        }
        
        // 如果是预定义阶段，存储指令列表
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseCompareInst(CompareInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取比较指令的操作数和比较类型
        Value left = ins.getLeft();
        Value right = ins.getRight();
        OpCode predicate = ins.getPredicate();
        boolean isFloat = ins.isFloatCompare();
        
        // 为结果分配目标寄存器
        AArch64Reg destReg = new AArch64VirReg(false);
        RegList.put(ins, destReg);
        
        // 处理左操作数
        AArch64Operand leftOp;
        if (left instanceof ConstantInt) {
            // 对于整数常量，创建临时寄存器并加载立即数
            // ARM要求CMP的第一个操作数必须是寄存器
            long value = ((ConstantInt) left).getValue();
            AArch64Reg leftReg = new AArch64VirReg(false);
            // 使用loadLargeImmediate处理可能的大立即数
            if (value > 65535 || value < -65536) {
                loadLargeImmediate(leftReg, value, insList, predefine);
            } else {
                AArch64Move moveInst = new AArch64Move(leftReg, new AArch64Imm(value), true);
                addInstr(moveInst, insList, predefine);
            }
            leftOp = leftReg;
        } else if (left instanceof ConstantFloat) {
            // 处理浮点常量
            double floatValue = ((ConstantFloat) left).getValue();
            
            // 为浮点常量分配寄存器
            AArch64Reg fpuReg = new AArch64VirReg(true);
            // 加载浮点常量到寄存器
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            
            leftOp = fpuReg;
        } else {
            // 对于变量，使用寄存器
            if (!RegList.containsKey(left)) {
                if (left.getType() instanceof FloatType) {
                    // 浮点类型使用浮点寄存器
                    AArch64Reg leftReg = new AArch64VirReg(true);
                    
                    RegList.put(left, leftReg);
                    leftOp = leftReg;
                } else {
                    // 整数类型使用通用寄存器
                    AArch64Reg leftReg = new AArch64VirReg(false);
                    RegList.put(left, leftReg);
                    leftOp = leftReg;
                }
            } else {
                leftOp = RegList.get(left);
            }
        }
        
        // 处理右操作数
        AArch64Operand rightOp;
        if (right instanceof ConstantInt) {
            // 对于整数常量，检查是否超出ARMv8比较指令的立即数范围
            long value = ((ConstantInt) right).getValue();
            // ARMv8 cmp指令的立即数范围为0-4095
            if (value > 4095 || value < -4095) {
                // 如果超出范围，先加载到寄存器，处理大立即数
                AArch64Reg rightReg = new AArch64VirReg(false);
                if (value > 65535 || value < -65536) {
                    loadLargeImmediate(rightReg, value, insList, predefine);
                } else {
                    AArch64Move moveInst = new AArch64Move(rightReg, new AArch64Imm(value), true);
                    addInstr(moveInst, insList, predefine);
                }
                rightOp = rightReg;
            } else {
                // 在范围内，可以使用立即数
                rightOp = new AArch64Imm(value);
            }
        } else if (right instanceof ConstantFloat) {
            // 处理浮点常量
            double floatValue = ((ConstantFloat) right).getValue();
            
            // 为浮点常量分配寄存器
            AArch64Reg fpuReg = new AArch64VirReg(true);
            // 加载浮点常量到寄存器
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            
            rightOp = fpuReg;
        } else {
            // 对于变量，使用寄存器
            if (!RegList.containsKey(right)) {
                if (right.getType() instanceof FloatType) {
                    // 浮点类型使用浮点寄存器
                    AArch64Reg rightReg = new AArch64VirReg(true);
                    
                    RegList.put(right, rightReg);
                    rightOp = rightReg;
                } else {
                    // 整数类型使用通用寄存器
                    AArch64Reg rightReg = new AArch64VirReg(false);
                    RegList.put(right, rightReg);
                    rightOp = rightReg;
                }
            } else {
                rightOp = RegList.get(right);
            }
        }
        
        // 创建比较指令
        AArch64Compare.CmpType cmpType;
        
        // 检查是否可以使用CMN指令优化（当比较一个负数常量时）
        if (!isFloat && rightOp instanceof AArch64Imm) {
            long value = ((AArch64Imm) rightOp).getValue();
            if (value < 0) {
                // 使用CMN指令，比较负值（相当于CMP reg, -imm）
                cmpType = AArch64Compare.CmpType.cmn;
                // 将立即数改为正值
                rightOp = new AArch64Imm(-value);
            } else {
                cmpType = AArch64Compare.CmpType.cmp;
            }
        } else if (isFloat) {
            cmpType = AArch64Compare.CmpType.fcmp;
        } else {
            cmpType = AArch64Compare.CmpType.cmp;
        }
        
        AArch64Compare compareInst = new AArch64Compare(leftOp, rightOp, cmpType);
        addInstr(compareInst, insList, predefine);
        
        // 根据比较谓词设置条件寄存器
        AArch64Tools.CondType condType;
        switch (predicate) {
            case EQ:
                condType = AArch64Tools.CondType.eq;
                break;
            case NE:
                condType = AArch64Tools.CondType.ne;
                break;
            case SGT:
                condType = isFloat ? AArch64Tools.CondType.gt : AArch64Tools.CondType.gt;
                break;
            case SGE:
                condType = isFloat ? AArch64Tools.CondType.ge : AArch64Tools.CondType.ge;
                break;
            case SLT:
                condType = isFloat ? AArch64Tools.CondType.lt : AArch64Tools.CondType.lt;
                break;
            case SLE:
                condType = isFloat ? AArch64Tools.CondType.le : AArch64Tools.CondType.le;
                break;
            case UGT:
                condType = isFloat ? AArch64Tools.CondType.hi : AArch64Tools.CondType.hi; // 浮点无序大于
                break;
            case UGE:
                condType = isFloat ? AArch64Tools.CondType.cs : AArch64Tools.CondType.cs; // 浮点无序大于等于
                break;
            case ULT:
                condType = isFloat ? AArch64Tools.CondType.cc : AArch64Tools.CondType.cc; // 浮点无序小于
                break;
            case ULE:
                condType = isFloat ? AArch64Tools.CondType.ls : AArch64Tools.CondType.ls; // 浮点无序小于等于
                break;
            case UNE:
                // 浮点无序不等于 (ARM中的vs, overflow set)
                condType = AArch64Tools.CondType.vs;
                break;
            case ORD:
                // 浮点有序 (ARM中的vc, overflow clear)
                condType = AArch64Tools.CondType.vc;
                break;
            case UNO:
                // 浮点无序 (ARM中的vs, overflow set)
                condType = AArch64Tools.CondType.vs;
                break;
            default:
                System.err.println("不支持的比较谓词: " + predicate);
                condType = AArch64Tools.CondType.eq; // 默认为等于
                break;
        }
        
        // 直接使用一条指令设置结果
        // 使用指定的条件码将结果设置为1或0
        AArch64Cset csetInst = new AArch64Cset(destReg, condType);
        addInstr(csetInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parsePhiInst(PhiInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 为Phi结果分配目标寄存器
        AArch64Reg destReg;
        if (ins.getType() instanceof FloatType) {
            destReg = new AArch64VirReg(true);
        } else {
            destReg = new AArch64VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 获取Phi指令的所有输入块和对应值
        Map<BasicBlock, Value> incomingValues = ins.getIncomingValues();
        
        // 处理每个输入值
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            BasicBlock incomingBlock = entry.getKey();
            Value incomingValue = entry.getValue();
            
            // 获取对应的ARM块
            AArch64Block armv8IncomingBlock = (AArch64Block) LabelList.get(incomingBlock);
            if (armv8IncomingBlock == null) {
                System.err.println("警告: 找不到Phi指令输入块的对应ARM块: " + incomingBlock.getName());
                continue;
            }
            
            // 为输入值创建源操作数
            AArch64Operand srcOp;
            if (incomingValue instanceof ConstantInt) {
                // 常量值，创建立即数
                long value = ((ConstantInt) incomingValue).getValue();
                srcOp = new AArch64Imm(value);
            } else if (incomingValue instanceof ConstantFloat) {
                double floatValue = ((ConstantFloat) incomingValue).getValue();
                AArch64Reg fpuReg = new AArch64VirReg(true);
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
                        AArch64Reg valueReg = new AArch64VirReg(false);
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
            AArch64Move moveInst;
            if (srcOp instanceof AArch64Imm) {
                moveInst = new AArch64Move(destReg, srcOp, true);
            } else {
                moveInst = new AArch64Move(destReg, (AArch64Reg) srcOp, false);
            }
            
            // 为前驱块添加phi解析指令
            if (!predefine) {
                // 如果不是预定义阶段，将指令直接添加到前驱块的末尾(在跳转指令之前)
                AArch64Instruction lastInst = armv8IncomingBlock.getLastInstruction();
                if (lastInst != null && (lastInst instanceof AArch64Branch || lastInst instanceof AArch64Jump)) {
                    // 在跳转指令前插入移动指令
                    armv8IncomingBlock.insertBeforeInst(lastInst, moveInst);
                } else {
                    // 没有跳转指令，直接添加到块末尾
                    armv8IncomingBlock.addAArch64Instruction(moveInst);
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
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取操作码和操作数
        OpCode opCode = ins.getOpCode();
        Value operand = ins.getOperand();
        
        // 为结果分配目标寄存器
        AArch64Reg destReg;
        if (operand instanceof ConstantInt) {
            destReg = new AArch64VirReg(false);
        } else if (operand instanceof ConstantFloat) {
            destReg = new AArch64VirReg(true);
        } else {
            destReg = new AArch64VirReg(false);
        }
        RegList.put(ins, destReg);
        
        // 处理操作数
        AArch64Operand srcOp;
        if (operand instanceof ConstantInt) {
            // 对于常量，创建立即数操作数
            long value = ((ConstantInt) operand).getValue();
            srcOp = new AArch64Imm(value);  // 显式转换为int
        } else if (operand instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) operand).getValue();
            AArch64Reg fpuReg = new AArch64VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            srcOp = fpuReg;
        } else {
            // 对于变量，使用寄存器
            if (!RegList.containsKey(operand)) {
                if (operand instanceof Instruction) {
                    parseInstruction((Instruction) operand, true);
                } else {
                    AArch64Reg srcReg = new AArch64VirReg(false);
                    RegList.put(operand, srcReg);
                }
            }
            srcOp = RegList.get(operand);
        }
        
        
        switch (opCode) {
            case NEG: // 取负操作
                if (srcOp instanceof AArch64Imm) {
                    // 如果是立即数，可以直接计算负值
                    long value = ((AArch64Imm) srcOp).getValue();
                    AArch64Move moveInst = new AArch64Move(destReg, new AArch64Imm(-value), true);
                    addInstr(moveInst, insList, predefine);
                } else {
                    // 如果是寄存器，使用NEG指令
                    AArch64Reg srcReg = (AArch64Reg) srcOp;
                    AArch64Unary negInst = new AArch64Unary(srcReg, destReg, AArch64Unary.AArch64UnaryType.neg);
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
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        // 获取源值
        Value source = ins.getSource();
        
        // 为目标分配寄存器 - 关键修复：处理PHI消除后的重复名称
        AArch64Reg destReg;
        
        // 检查是否已经有同名的寄存器映射（来自PHI消除）
        // 如果有，使用现有的寄存器；否则创建新的
        String destName = ins.getName();
        
        // 查找是否已经有同名的Value映射到寄存器
        Value existingValue = null;
        for (Map.Entry<Value, AArch64Reg> entry : RegList.entrySet()) {
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
                destReg = new AArch64VirReg(true);
            } else {
                destReg = new AArch64VirReg(false);
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
        AArch64Operand srcOp;
        if (source instanceof ConstantInt) {
            long value = ((ConstantInt) source).getValue();
            srcOp = new AArch64Imm(value);
        } else if (source instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) source).getValue();
            AArch64Reg fpuReg = new AArch64VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            srcOp = fpuReg;
        } else {
            // 从RegList获取源寄存器
            if (!RegList.containsKey(source)) {
                if (source.getType() instanceof FloatType) {
                    AArch64Reg srcReg = new AArch64VirReg(true);
                    RegList.put(source, srcReg);
                    srcOp = srcReg;
                } else {
                    AArch64Reg srcReg = new AArch64VirReg(false);
                    RegList.put(source, srcReg);
                    srcOp = srcReg;
                }
            } else {
                srcOp = RegList.get(source);
            }
        }
        
        // 创建移动指令
        AArch64Move moveInst;
        if (srcOp instanceof AArch64Imm) {
            moveInst = new AArch64Move(destReg, srcOp, true);
        } else {
            moveInst = new AArch64Move(destReg, (AArch64Reg) srcOp, false);
        }
        
        addInstr(moveInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void addInstr(AArch64Instruction ins, ArrayList<AArch64Instruction> insList, boolean predefine) {
        if (predefine) {
            insList.add(ins);
        } else {
            curAArch64Block.addAArch64Instruction(ins);
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

    public AArch64Module getAArch64Module() {
        return armv8Module;
    }


    // 加载大立即数到寄存器的方法
    private void loadLargeImmediate(AArch64Reg destReg, long value, ArrayList<AArch64Instruction> insList, boolean predefine) {
        // 使用位运算加载64位大立即数
        // 首先加载低16位
        long bits = value;
        AArch64Move movzInst = new AArch64Move(destReg, new AArch64Imm(bits & 0xFFFF), true, AArch64Move.MoveType.MOVZ);
        addInstr(movzInst, insList, predefine);
        // 然后加载高位（如果需要）
        // 检查第二个16位（bits[31:16]）
        if (((bits >> 16) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 16) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(16);
            addInstr(movkInst, insList, predefine);
        }
        
        // 检查第三个16位（bits[47:32]）
        if (((bits >> 32) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 32) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(32);
            addInstr(movkInst, insList, predefine);
        }
        
        // 检查第四个16位（bits[63:48]）
        if (((bits >> 48) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 48) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(48);
            addInstr(movkInst, insList, predefine);
        }
    }
    
    /**
     * 生成全局变量地址访问指令序列（使用ADRP+ADD替代ADR）
     * @param destReg 目标寄存器
     * @param label 全局变量标签
     * @param insList 指令列表
     * @param predefine 是否是预定义阶段
     */
    private void loadGlobalAddress(AArch64Reg destReg, AArch64Label label, ArrayList<AArch64Instruction> insList, boolean predefine) {
        // 使用ADRP获取页地址
        AArch64Adrp adrpInst = new AArch64Adrp(destReg, label);
        addInstr(adrpInst, insList, predefine);
        
        // 使用ADD加上页内偏移，创建一个特殊的标签来表示:lo12:偏移
        ArrayList<AArch64Operand> addOperands = new ArrayList<>();
        addOperands.add(destReg);
        addOperands.add(new AArch64Label(":lo12:" + label.getLabelName()));
        AArch64Binary addInst = new AArch64Binary(addOperands, destReg, AArch64Binary.AArch64BinaryType.add);
        addInstr(addInst, insList, predefine);
    }
    
    /**
     * 加载浮点常量到寄存器
     * @param destReg 目标浮点寄存器
     * @param value 要加载的浮点值
     * @param insList 指令列表
     * @param predefine 是否是预定义阶段
     */
    private void loadFloatConstant(AArch64Reg destReg, double value, ArrayList<AArch64Instruction> insList, boolean predefine) {
        // 先将浮点数转换为原始二进制表示
        long bits = Double.doubleToRawLongBits(value);
        
        // 分配一个虚拟寄存器用于存放位模式 (整数类型)
        AArch64VirReg tempReg = new AArch64VirReg(false);
        
        // 使用MOVZ和MOVK指令加载64位浮点值的位模式到通用寄存器
        // 加载低16位
        AArch64Move movzInst = new AArch64Move(tempReg, new AArch64Imm(bits & 0xFFFF), true, AArch64Move.MoveType.MOVZ);
        addInstr(movzInst, insList, predefine);
        
        // 加载第二个16位块
        AArch64Move movk1Inst = new AArch64Move(tempReg, new AArch64Imm((bits >> 16) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
        movk1Inst.setShift(16);
        addInstr(movk1Inst, insList, predefine);
        
        // 加载第三个16位块
        AArch64Move movk2Inst = new AArch64Move(tempReg, new AArch64Imm((bits >> 32) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
        movk2Inst.setShift(32);
        addInstr(movk2Inst, insList, predefine);
        
        // 加载最高16位
        AArch64Move movk3Inst = new AArch64Move(tempReg, new AArch64Imm((bits >> 48) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
        movk3Inst.setShift(48);
        addInstr(movk3Inst, insList, predefine);
        
        // 使用FMOV指令将通用寄存器的位模式移动到浮点寄存器
        AArch64Fmov fmovInst = new AArch64Fmov(destReg, tempReg);
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
    private void createMaddInstruction(AArch64Reg destReg, AArch64Reg addReg, AArch64Reg mulReg1, AArch64Reg mulReg2, 
                                     ArrayList<AArch64Instruction> insList, boolean predefine) {
        ArrayList<AArch64Operand> operands = new ArrayList<>();
        operands.add(addReg);   // 第一个操作数是加数
        operands.add(mulReg1);  // 第二个操作数是被乘数1
        operands.add(mulReg2);  // 第三个操作数是被乘数2
        
        AArch64Binary maddInst = new AArch64Binary(operands, destReg, AArch64Binary.AArch64BinaryType.madd);
        addInstr(maddInst, insList, predefine);
    }

    /**
     * 确保正确获取函数参数
     * 在ARM调用约定中，前8个整型参数在x0-x7中，前8个浮点参数在v0-v7中
     */
    private AArch64Reg ensureArgumentAArch64Reg(Argument arg, ArrayList<AArch64Instruction> insList, boolean predefine) {
        if (RegList.containsKey(arg)) {
            return RegList.get(arg);
        }
        
        // 检查参数是否在寄存器中
        AArch64Reg argReg = curAArch64Function.getRegArg(arg);
        if (argReg != null) {
            // 参数在寄存器中，直接使用
            RegList.put(arg, argReg);
            return argReg;
        }
        
        // 检查参数是否在栈上
        Long stackOffset = curAArch64Function.getStackArg(arg);
        if (stackOffset != null) {
            // 参数在栈上，需要加载到寄存器
            AArch64VirReg tempReg = arg.getType() instanceof FloatType ? 
                new AArch64VirReg(true) : new AArch64VirReg(false);
                
            // 从FP相对位置加载参数值，检查偏移量是否超出范围
            AArch64Operand offsetOp = checkImmediate(stackOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
            
            if (offsetOp instanceof AArch64Reg) {
                // 偏移量被加载到寄存器，需要先计算地址
                AArch64VirReg addrReg = new AArch64VirReg(false);
                ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                addOperands.add(AArch64CPUReg.getAArch64FPReg());
                addOperands.add(offsetOp);
                AArch64Binary addInst = new AArch64Binary(addOperands, addrReg, AArch64Binary.AArch64BinaryType.add);
                addInstr(addInst, insList, predefine);
                
                // 使用计算的地址进行加载
                AArch64Load loadInst = new AArch64Load(addrReg, new AArch64Imm(0), tempReg);
                addInstr(loadInst, insList, predefine);
            } else {
                // 偏移量在范围内，直接使用
                AArch64Load loadInst = new AArch64Load(
                    AArch64CPUReg.getAArch64FPReg(), 
                    (AArch64Imm)offsetOp, 
                    tempReg
                );
                addInstr(loadInst, insList, predefine);
            }
            
            // 记录映射
            RegList.put(arg, tempReg);
            return tempReg;
        }
        
        // 无法找到参数位置，报错
        System.err.println("错误: 无法找到参数 " + arg + " 的位置");
        return null;
    }

    /**
     * 处理余数运算 (a % b = a - (a / b) * b)
     */
    private void handleRemOperation(AArch64Operand leftOp, AArch64Operand rightOp, AArch64Reg destReg,
                                  ArrayList<AArch64Instruction> insList, boolean predefine) {
        // 确保右操作数是寄存器
        AArch64Reg rightReg;
        if (rightOp instanceof AArch64Imm) {
            rightReg = new AArch64VirReg(false);
            AArch64Move moveInst = new AArch64Move(rightReg, rightOp, true);
            addInstr(moveInst, insList, predefine);
        } else {
            rightReg = (AArch64Reg) rightOp;
        }
        
        // 1. 除法运算: destReg = leftOp / rightReg
        ArrayList<AArch64Operand> divOperands = new ArrayList<>();
        divOperands.add(leftOp);
        divOperands.add(rightReg);
        AArch64Binary divInst = new AArch64Binary(divOperands, destReg, AArch64Binary.AArch64BinaryType.sdiv);
        addInstr(divInst, insList, predefine);
        
        // 2. 乘法运算: tempReg = destReg * rightReg
        AArch64VirReg tempReg = new AArch64VirReg(false);
        ArrayList<AArch64Operand> mulOperands = new ArrayList<>();
        mulOperands.add(destReg);
        mulOperands.add(rightReg);
        AArch64Binary mulInst = new AArch64Binary(mulOperands, tempReg, AArch64Binary.AArch64BinaryType.mul);
        addInstr(mulInst, insList, predefine);
        
        // 3. 减法运算: destReg = leftOp - tempReg
        ArrayList<AArch64Operand> subOperands = new ArrayList<>();
        subOperands.add(leftOp);
        subOperands.add(tempReg);
        AArch64Binary subInst = new AArch64Binary(subOperands, destReg, AArch64Binary.AArch64BinaryType.sub);
        addInstr(subInst, insList, predefine);
    }

    /**
     * 检查立即数是否在指定范围内，如果超出范围则自动加载到寄存器
     * @param value 立即数值
     * @param instrType 指令类型，决定立即数范围
     * @param insList 指令列表
     * @param predefine 是否为预定义阶段
     * @return 如果在范围内返回AArch64Imm，否则返回包含该值的寄存器
     */
    private AArch64Operand checkImmediate(long value, ImmediateRange instrType, ArrayList<AArch64Instruction> insList, boolean predefine) {
        // 检查立即数是否在范围内
        if (instrType.isInRange(value)) {
            // 在范围内，直接返回立即数
            return new AArch64Imm(value);
        } else {
            // 超出范围，加载到寄存器
            AArch64VirReg tempReg = new AArch64VirReg(false);
            
            // 如果是MOV指令且值很大，使用特殊的大立即数加载方法
            if (instrType == ImmediateRange.MOV && (value > 65535 || value < -65536)) {
                loadLargeImmediate(tempReg, value, insList, predefine);
            } else {
                // 其他指令或较小的值，使用普通的MOV指令
                // 注意：这里MOV指令的立即数可能也超过范围，所以需要特殊处理
                if (value > 65535 || value < -65536) {
                    loadLargeImmediate(tempReg, value, insList, predefine);
                } else {
                    AArch64Move moveInst = new AArch64Move(tempReg, new AArch64Imm(value), true);
                    addInstr(moveInst, insList, predefine);
                }
            }
            return tempReg;
        }
    }
    

    /**
     * 处理操作数，返回对应的ARM操作数
     */
    private enum ImmediateRange {
        DEFAULT(4095),            // 默认范围，适用于大多数指令
        CMP(4095),               // 比较指令的立即数范围 (12位无符号)
        MOV(65535),              // 移动指令的立即数范围 (16位无符号)
        ADD_SUB(4095),           // 加减指令的立即数范围 (12位无符号)
        LOGICAL(4095),           // 逻辑操作指令的立即数范围 (12位，但有特殊编码规则)
        MEMORY_OFFSET_SIGNED(255), // 内存操作有符号偏移 (9位有符号，-256到255)
        MEMORY_OFFSET_UNSIGNED(32760), // 内存操作无符号偏移 (12位，必须是8的倍数，0到32760)
        SHIFT_AMOUNT(63),        // 移位量范围 (6位，0到63)
        STACK(32760);            // 栈操作指令的立即数范围，使用无符号偏移

        private final long maxValue;
        private final long minValue;

        ImmediateRange(long maxValue) {
            this.maxValue = maxValue;
            this.minValue = 0; // 默认最小值为0
        }

        ImmediateRange(long minValue, long maxValue) {
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public boolean isInRange(long value) {
            switch (this) {
                case MEMORY_OFFSET_SIGNED:
                    // 有符号偏移：-256到255
                    return value >= -256 && value <= 255;
                case MEMORY_OFFSET_UNSIGNED:
                    // 无符号偏移：0到32760，且必须是8的倍数
                    return value >= 0 && value <= 32760 && (value % 8 == 0);
                case SHIFT_AMOUNT:
                    // 移位量：0到63
                    return value >= 0 && value <= 63;
                case STACK:
                    // 栈操作：0到32760，且必须是8的倍数
                    return value >= 0 && value <= 32760 && (value % 8 == 0);
                default:
                    // 其他指令：12位无符号立即数
                    return value >= 0 && value <= maxValue;
            }
        }

        public long getMaxValue() { return maxValue; }
        public long getMinValue() { return minValue; }
    }

    private AArch64Operand processOperand(Value operand, ArrayList<AArch64Instruction> insList, 
                                       boolean predefine, boolean isFloat, boolean requiresReg, ImmediateRange immRange) {
        // 处理常量
        if (operand instanceof ConstantInt) {
            long value = ((ConstantInt) operand).getValue();
            // 检查是否需要寄存器形式
            if (requiresReg) {
                // 如果需要寄存器，加载到寄存器，处理大立即数
                AArch64VirReg tempReg = new AArch64VirReg(false);
                if (value > 65535 || value < -65536) {
                    loadLargeImmediate(tempReg, value, insList, predefine);
                } else {
                    AArch64Move moveInst = new AArch64Move(tempReg, new AArch64Imm(value), true);
                    addInstr(moveInst, insList, predefine);
                }
                return tempReg;
            } else {
                // 否则检查是否在范围内，并适当处理
                return checkImmediate(value, immRange, insList, predefine);
            }
        } 
        else if (operand instanceof ConstantFloat) {
            // 浮点常量总是需要加载到寄存器
            double floatValue = ((ConstantFloat) operand).getValue();
            AArch64VirReg fReg = new AArch64VirReg(true);
            loadFloatConstant(fReg, floatValue, insList, predefine);
            return fReg;
        } 
        else if (operand instanceof Argument) {
            // 处理函数参数 - 使用辅助方法确保正确获取参数值
            Argument arg = (Argument) operand;
            AArch64Reg paramReg = ensureArgumentAArch64Reg(arg, insList, predefine);
            if (paramReg != null) {
                return paramReg;
            } else {
                System.err.println("警告: 无法获取参数 " + arg);
                return new AArch64Imm(0); // 返回默认值
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
                return new AArch64Imm(0);
            }
        } 
        else if (RegList.containsKey(operand)) {
            // 已经有对应的寄存器，直接返回
            return RegList.get(operand);
        } 
        else {
            // 其他情况，返回0
            System.err.println("警告: 无法处理的操作数: " + operand);
            return new AArch64Imm(0);
        }
    }


    private AArch64Operand processOperand(Value operand, ArrayList<AArch64Instruction> insList, 
                                       boolean predefine, boolean isFloat, boolean requiresReg) {
        // 使用默认的立即数范围
        return processOperand(operand, insList, predefine, isFloat, requiresReg, ImmediateRange.DEFAULT);
    }

    
    /**
     * 安全地创建内存操作数，如果偏移量超出范围则使用寄存器
     */
    private AArch64Operand createSafeMemoryOperand(long offset, ArrayList<AArch64Instruction> insList, boolean predefine) {
        // 检查是否在有符号偏移范围内（-256到255）
        if (ImmediateRange.MEMORY_OFFSET_SIGNED.isInRange(offset)) {
            return new AArch64Imm(offset);
        }
        
        // 检查是否在无符号偏移范围内（0到32760，且是8的倍数）
        if (ImmediateRange.MEMORY_OFFSET_UNSIGNED.isInRange(offset)) {
            return new AArch64Imm(offset);
        }
        
        // 超出范围，需要加载到寄存器
        AArch64VirReg tempReg = new AArch64VirReg(false);
        loadLargeImmediate(tempReg, offset, insList, predefine);
        return tempReg;
    }

    /**
     * 安全地创建内存访问指令，处理大偏移量
     */
    private void createSafeMemoryInstruction(AArch64Reg baseReg, long offset, AArch64Reg valueReg, 
                                           boolean isLoad, ArrayList<AArch64Instruction> insList, boolean predefine) {
        AArch64Operand offsetOp = createSafeMemoryOperand(offset, insList, predefine);
        
        if (offsetOp instanceof AArch64Reg) {
            // 如果偏移量被加载到寄存器，需要先计算地址
            AArch64VirReg addrReg = new AArch64VirReg(false);
            ArrayList<AArch64Operand> addOperands = new ArrayList<>();
            addOperands.add(baseReg);
            addOperands.add(offsetOp);
            AArch64Binary addInst = new AArch64Binary(addOperands, addrReg, AArch64Binary.AArch64BinaryType.add);
            addInstr(addInst, insList, predefine);
            
            // 然后使用地址寄存器加零偏移量进行加载或存储
            if (isLoad) {
                AArch64Load loadInst = new AArch64Load(addrReg, new AArch64Imm(0), valueReg);
                addInstr(loadInst, insList, predefine);
            } else {
                AArch64Store storeInst = new AArch64Store(valueReg, addrReg, new AArch64Imm(0));
                addInstr(storeInst, insList, predefine);
            }
        } else {
            // 在范围内直接使用偏移量
            if (isLoad) {
                AArch64Load loadInst = new AArch64Load(baseReg, (AArch64Imm)offsetOp, valueReg);
                addInstr(loadInst, insList, predefine);
            } else {
                AArch64Store storeInst = new AArch64Store(valueReg, baseReg, (AArch64Imm)offsetOp);
                addInstr(storeInst, insList, predefine);
            }
        }
    }

    private void handleLargeStackOffset(AArch64Reg baseReg, long offset, AArch64Reg valueReg, 
                                      boolean isLoad, boolean isFloat, 
                                      ArrayList<AArch64Instruction> insList, boolean predefine) {
        createSafeMemoryInstruction(baseReg, offset, valueReg, isLoad, insList, predefine);
    }
} 
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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class AArch64Visitor {
    public Module irModule;
    public AArch64Module armv8Module = new AArch64Module();
    private static final LinkedHashMap<Value, AArch64Label> LabelList = new LinkedHashMap<>();
    private static final LinkedHashMap<Value, AArch64Reg> RegList = new LinkedHashMap<>();
    private static final java.util.Map<String, AArch64Reg> NameToReg = new java.util.HashMap<>();
    private static final LinkedHashMap<Value, Long> ptrList = new LinkedHashMap<>();
    private AArch64Block curAArch64Block = null;
    private AArch64Function curAArch64Function = null;
    private final LinkedHashMap<Instruction, ArrayList<AArch64Instruction>> predefines = new LinkedHashMap<>();

    public AArch64Visitor(MiddleEnd.IR.Module irModule) {
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
    
    
    private AArch64Reg getOrCreateRegister(Value operand, boolean isFloat) {
        // 1. 首先检查是否已经在RegList中
        if (RegList.containsKey(operand)) {
            return RegList.get(operand);
        }
        
        // 2. 通过变量名在NameToReg中查找（处理phi变量等同名变量）
        String operandName = operand.getName();
        if (NameToReg.containsKey(operandName)) {
            AArch64Reg existingReg = NameToReg.get(operandName);
            RegList.put(operand, existingReg);
            return existingReg;
        }
        
        // 3. 创建新的寄存器
        AArch64Reg newReg = isFloat ? new AArch64VirReg(true) : new AArch64VirReg(false);
        RegList.put(operand, newReg);
        NameToReg.put(operandName, newReg);
        return newReg;
    }
    
    public void run() {
        for (GlobalVariable globalVariable : irModule.globalVars()) {
            generateGlobalVariable(globalVariable);
        }

        for (Function function : irModule.functions()) {
            generateFunction(function);
        }
    }

    private void generateGlobalVariable(GlobalVariable globalVariable) {
        String varName = removeLeadingAt(globalVariable.getName());
        ArrayList<Number> initialValues = new ArrayList<>();
        
        Type elementType = globalVariable.getElementType();
        int byteSize;
        boolean isFloat = elementType instanceof FloatType;
        
        if (isFloat) {
            byteSize = elementType.getSize();
        } else {
            byteSize = 8;
        }
        
        if (globalVariable.isArray()) {
            if (isFloat) {
                byteSize = globalVariable.getArraySize() * elementType.getSize();
            } else {
                byteSize = globalVariable.getArraySize() * 8;
            }
            if (globalVariable.isZeroInitialized()) {
                initialValues = null;
            } else if (globalVariable.hasInitializer() && globalVariable.getArrayValues() != null) {
                List<Value> arrayValues = globalVariable.getArrayValues();
                for (Value val : arrayValues) {
                    if (val instanceof ConstantInt) {
                        initialValues.add(((ConstantInt) val).getValue());
                    } else if (val instanceof ConstantFloat) {
                        initialValues.add(((ConstantFloat) val).getValue());
                    }
                }
            }
        } else {
            if (globalVariable.hasInitializer()) {
                Value initializer = globalVariable.getInitializer();
                if (initializer instanceof ConstantInt) {
                    initialValues.add(((ConstantInt) initializer).getValue());
                } else if (initializer instanceof ConstantFloat) {
                    initialValues.add(((ConstantFloat) initializer).getValue());
                }
            } else {
                initialValues = null;
            }
        }
        
        AArch64GlobalVariable armv8GlobalVar = new AArch64GlobalVariable(varName, initialValues, byteSize, isFloat, elementType);
        
        if (initialValues == null || initialValues.isEmpty()) {
            armv8Module.addBssVar(armv8GlobalVar); // 未初始化的变量放在bss段
        } else {
            armv8Module.addDataVar(armv8GlobalVar); // 初始化的变量放在data段
        }
        
        LabelList.put(globalVariable, armv8GlobalVar);
    }

    private void generateFunction(Function function) {
        if (function.isExternal()) {
            return;
        }

        String functionName = removeLeadingAt(function.getName());
        curAArch64Function = new AArch64Function(functionName, function);
        armv8Module.addFunction(functionName, curAArch64Function);
        AArch64VirReg.resetCounter();

        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            String blockName = removeLeadingAt(functionName) + "_" + basicBlock.getName();
            AArch64Block armv8Block = new AArch64Block(blockName);
            LabelList.put(basicBlock, armv8Block);
            curAArch64Function.addBlock(armv8Block);
        }
        
        List<Argument> arguments = function.getArguments();
        for (Argument arg : arguments) {
            if (!RegList.containsKey(arg)) {
                AArch64Reg argReg = curAArch64Function.getRegArg(arg);
                if (argReg != null) {
                    RegList.put(arg, argReg);
                }
            }
        }
        
        boolean first = curAArch64Function.getName().contains("main") ? false : true;
        for (BasicBlock basicBlock : function.getBasicBlocks()) {
            curAArch64Block = (AArch64Block) LabelList.get(basicBlock);
            if (first) {
                curAArch64Function.saveCalleeRegs(curAArch64Block);

                // 在函数入口处为位于参数寄存器中的形参分配新的虚拟寄存器
                for (Argument arg : arguments) {
                    AArch64Reg src = curAArch64Function.getRegArg(arg);
                    if (src != null) {
                        boolean isFloat = arg.getType() instanceof FloatType;
                        AArch64VirReg dest = new AArch64VirReg(isFloat);
                        AArch64Move mv = new AArch64Move(dest, src, false);
                        curAArch64Block.addAArch64Instruction(mv);
                        RegList.put(arg, dest);
                    }
                }

                first = false;
            }
            generateBasicBlock(basicBlock);
        }
        
        // System.out.println("\n===== 开始对函数 " + functionName + " 进行寄存器分配 =====");
        RegisterAllocator allocator = new RegisterAllocator(curAArch64Function);
        allocator.allocateRegisters();
        
        PostRegisterOptimizer optimizer = new PostRegisterOptimizer(curAArch64Function);
        optimizer.optimize();
        
        // System.out.println("===== 函数 " + functionName + " 寄存器分配完成 =====\n");
    }

    private void generateBasicBlock(BasicBlock basicBlock) {
        if (basicBlock.getInstructions().isEmpty()) {
            return;
        }
        
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

    private void parseAlloc(AllocaInstruction ins, boolean predefine) {
        if (ins.getType() == null) {
            System.err.println("警告: AllocaInstruction 没有类型信息: " + ins);
            return;
        }
        
        long size;
        if (ins.isArrayAllocation()) {
            size = 8 * ins.getArraySize();
        } else {
            size = 8;
        }
        
        long currentStackPos = curAArch64Function.getStackSize();
        
        curAArch64Function.addStack(ins, size);
        
        ptrList.put(ins, currentStackPos);
        
        System.err.println("!!! STACK ALLOCATION DEBUG !!! 为变量 " + ins + " 分配栈位置 [sp, #" + currentStackPos + "], 大小: " + size);
        System.err.flush();
        
        if (!predefine) {
            AArch64VirReg allocaReg = new AArch64VirReg(false);
            RegList.put(ins, allocaReg);
            
            AArch64Operand offsetOp = checkImmediate(currentStackPos, ImmediateRange.ADD_SUB, null, predefine);
            
            if (offsetOp instanceof AArch64Reg) {
                ArrayList<AArch64Operand> operands = new ArrayList<>();
                operands.add(AArch64CPUReg.getAArch64SpReg());
                operands.add(offsetOp);
                AArch64Binary addInst = new AArch64Binary(operands, allocaReg, AArch64Binary.AArch64BinaryType.add);
                addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                addInstr(addInst, null, predefine);
            } else if (offsetOp instanceof AArch64Imm) {
                AArch64Binary addInst = new AArch64Binary(
                    allocaReg,
                    AArch64CPUReg.getAArch64SpReg(), 
                    (AArch64Imm)offsetOp, 
                    AArch64Binary.AArch64BinaryType.add
                );
                addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                addInstr(addInst, null, predefine);
            }
        }
    }

    private void parseBinaryInst(BinaryInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        OpCode opCode = ins.getOpCode();
        Value leftOperand = ins.getLeft();
        Value rightOperand = ins.getRight();
        
        boolean isFloatOperation = leftOperand.getType() instanceof FloatType || 
                                 rightOperand.getType() instanceof FloatType;
 
        // 使用通用方法获取目标寄存器
        AArch64Reg destReg = getOrCreateRegister(ins, isFloatOperation);
        
        RegList.put(ins, destReg);
        
        boolean leftRequiresReg = opCode == OpCode.MUL || opCode == OpCode.DIV || 
                                 opCode == OpCode.ADD || opCode == OpCode.SUB ||
                                 opCode == OpCode.AND || opCode == OpCode.OR || opCode == OpCode.XOR ||
                                 opCode == OpCode.SHL || opCode == OpCode.LSHR || opCode == OpCode.ASHR ||
                                 opCode == OpCode.REM;
        
        ImmediateRange leftRange = ImmediateRange.DEFAULT;
        ImmediateRange rightRange = ImmediateRange.DEFAULT;
        
        if (opCode == OpCode.ADD || opCode == OpCode.SUB) {
            leftRange = ImmediateRange.ADD_SUB;
            rightRange = ImmediateRange.ADD_SUB;
        }
        
        AArch64Operand leftOp = processOperand(leftOperand, insList, predefine, isFloatOperation, leftRequiresReg, leftRange);
        
        boolean rightRequiresReg = opCode == OpCode.MUL || opCode == OpCode.DIV || opCode == OpCode.REM;
        AArch64Operand rightOp = processOperand(rightOperand, insList, predefine, isFloatOperation, rightRequiresReg, rightRange);
        
        ArrayList<AArch64Operand> operands = new ArrayList<>();
        operands.add(leftOp);
        operands.add(rightOp);

        if (isFloatOperation) {
            switch (opCode) {
                case FADD:
                    AArch64FBinary faddInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fadd);
                    addInstr(faddInst, insList, predefine);
                    break;
                case FSUB:
                    AArch64FBinary fsubInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fsub);
                    addInstr(fsubInst, insList, predefine);
                    break;
                case FMUL:
                    AArch64FBinary fmulInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fmul);
                    addInstr(fmulInst, insList, predefine);
                    break;
                case FDIV:
                    AArch64FBinary fdivInst = new AArch64FBinary(operands, destReg, AArch64FBinary.AArch64FBinaryType.fdiv);
                    addInstr(fdivInst, insList, predefine);
                    break;
                default:
                    System.err.println("不支持的浮点二元操作: " + opCode);
                    break;
            }
        } else {
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
                    handleRemOperation(leftOp, rightOp, destReg, insList, predefine);
                    if (predefine) {
                        predefines.put(ins, insList);
                    }
                    return;
                default:
                    System.err.println("不支持的整数二元操作: " + opCode);
                    if (predefine) {
                        predefines.put(ins, insList);
                    }
                    return;
            }
            
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
            BasicBlock targetBlock = ins.getTrueBlock();
            AArch64Block armTarget = (AArch64Block) LabelList.get(targetBlock);
            
            AArch64Jump jumpInst = new AArch64Jump(armTarget, curAArch64Block);
            addInstr(jumpInst, insList, predefine);
        } else {
            Value condition = ins.getCondition();
            BasicBlock trueBlock = ins.getTrueBlock();
            BasicBlock falseBlock = ins.getFalseBlock();
            
            AArch64Block armTrueBlock = (AArch64Block) LabelList.get(trueBlock);
            AArch64Block armFalseBlock = (AArch64Block) LabelList.get(falseBlock);
            
            if (condition instanceof CompareInstruction) {
                CompareInstruction cmpIns = (CompareInstruction) condition;
                OpCode cmpOp = cmpIns.getCompareType();
                Value left = cmpIns.getLeft();
                Value right = cmpIns.getRight();
                OpCode predicate = cmpIns.getPredicate();
                
                boolean isFloat = cmpIns.isFloatCompare();
                
                AArch64Operand leftOp, rightOp;
                
                if (left instanceof ConstantInt) {
                    long value = ((ConstantInt) left).getValue();
                    AArch64Reg leftReg = new AArch64VirReg(false);
                    // 防止immediate out of range
                    if (value > 65535 || value < -65536) {
                        loadLargeImmediate(leftReg, value, insList, predefine);
                    } else {
                        AArch64Move moveInst = new AArch64Move(leftReg, new AArch64Imm(value), true);
                        addInstr(moveInst, insList, predefine);
                    }
                    leftOp = leftReg;
                } else if (left instanceof ConstantFloat) {
                    double value = ((ConstantFloat) left).getValue();
                    AArch64Reg leftReg = new AArch64VirReg(true);
                    loadFloatConstant(leftReg, value, insList, predefine);
                    leftOp = leftReg;
                } else {
                    leftOp = RegList.get(left);
                }
                
                if (right instanceof ConstantInt) {
                    long value = ((ConstantInt) right).getValue();
                    if (isFloat) {
                        AArch64Reg rightReg = new AArch64VirReg(true);
                        loadFloatConstant(rightReg, (double) value, insList, predefine);
                        rightOp = rightReg;
                    } else {
                        boolean useCmn = false;
                        long abs = value;
                        if (value < 0) { useCmn = true; abs = -value; }
                        if (!isAddSubImmEncodable(abs)) {
                            AArch64Reg rightReg = new AArch64VirReg(false);
                            loadLargeImmediate(rightReg, value, insList, predefine);
                            rightOp = rightReg;
                        } else {
                            rightOp = new AArch64Imm(abs);
                        }
                    }
                } else if (right instanceof ConstantFloat) {
                    double value = ((ConstantFloat) right).getValue();
                    AArch64Reg rightReg = new AArch64VirReg(true);
                    loadFloatConstant(rightReg, value, insList, predefine);
                    rightOp = rightReg;
                } else {
                    rightOp = RegList.get(right);
                }
                

                
                AArch64Compare.CmpType cmpType;
                if (isFloat) {
                    cmpType = AArch64Compare.CmpType.fcmp;
                } else {
                    cmpType = AArch64Compare.CmpType.cmp;
                    if (rightOp instanceof AArch64Imm) {
                        long imm = ((AArch64Imm) rightOp).getValue();
                        if (((ConstantInt) right).getValue() < 0) {
                            cmpType = AArch64Compare.CmpType.cmn;
                        }
                    }
                }
                
                AArch64Compare compareInst = new AArch64Compare(leftOp, rightOp, cmpType);
                addInstr(compareInst, insList, predefine);
                
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
                    case ULT:
                        condType = AArch64Tools.CondType.cc;  // 无符号小于 (C==0)
                        break;
                    case ULE:
                        condType = AArch64Tools.CondType.ls;  // 无符号小于等于 (C==0||Z==1)
                        break;
                    case UGT:
                        condType = AArch64Tools.CondType.hi;  // 无符号大于 (C==1&&Z==0)
                        break;
                    case UGE:
                        condType = AArch64Tools.CondType.cs;  // 无符号大于等于 (C==1)
                        break;
                    case UNE:
                        condType = AArch64Tools.CondType.ne;  // 不等于
                        break;
                    case ORD:
                        condType = AArch64Tools.CondType.vc;  // 有序 (V==0)
                        break;
                    case UNO:
                        condType = AArch64Tools.CondType.vs;  // 无序 (V==1)
                        break;
                    default:
                        System.err.println("不支持的比较谓词: " + predicate);
                        condType = AArch64Tools.CondType.eq;  // 默认为等于
                        break;
                }
                
                AArch64Branch branchTrueInst = new AArch64Branch(armTrueBlock, condType);
                branchTrueInst.setPredSucc(curAArch64Block);
                addInstr(branchTrueInst, insList, predefine);
                
                AArch64Jump jumpFalseInst = new AArch64Jump(armFalseBlock, curAArch64Block);
                addInstr(jumpFalseInst, insList, predefine);
                
            } else {
                AArch64Operand condOp;
                
                if (condition instanceof ConstantInt) {
                    long value = ((ConstantInt) condition).getValue();
                    if (value != 0) {
                        AArch64Jump jumpTrueInst = new AArch64Jump(armTrueBlock, curAArch64Block);
                        addInstr(jumpTrueInst, insList, predefine);
                    } else {
                        AArch64Jump jumpFalseInst = new AArch64Jump(armFalseBlock, curAArch64Block);
                        addInstr(jumpFalseInst, insList, predefine);
                    }
                    return;
                } else {
                    condOp = RegList.get(condition);
                    
                    if (condOp == null) {
                        String condName = condition.getName();
                        for (Map.Entry<Value, AArch64Reg> entry : RegList.entrySet()) {
                            if (entry.getKey().getName().equals(condName)) {
                                condOp = entry.getValue();
                                break;
                            }
                        }
                    }
                    
                    if (condOp == null) {
                        System.err.println("错误: 无法找到条件变量的寄存器映射: " + condition.getName());
                        System.err.println("条件对象类型: " + condition.getClass().getName());
                        System.err.println("可用的寄存器映射:");
                        for (Map.Entry<Value, AArch64Reg> entry : RegList.entrySet()) {
                            System.err.println("  " + entry.getKey().getName() + " -> " + entry.getValue());
                        }
                        condOp = new AArch64VirReg(false);
                        System.err.println("使用临时寄存器作为fallback");
                    }
                    
                    AArch64Cbz cbzInst = new AArch64Cbz((AArch64Reg) condOp, armFalseBlock);
                    cbzInst.setPredSucc(curAArch64Block);
                    addInstr(cbzInst, insList, predefine);
                    
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
        Function callee = ins.getCallee();
        String functionName = removeLeadingAt(callee.getName());
        
        List<Value> arguments = ins.getArguments();
        int argCount = arguments.size();

        // 在开始装载参数之前，将目前仍驻留在参数寄存器(x0-x7 / v0-v7)中的值拷贝到新的虚拟寄存器，
        // 以免后续对这些寄存器的写入覆盖还会被用作实参的旧值。
        for (Map.Entry<MiddleEnd.IR.Value.Value, Backend.Value.Operand.Register.AArch64Reg> entry :
                new ArrayList<>(RegList.entrySet())) {
            Backend.Value.Operand.Register.AArch64Reg reg = entry.getValue();
            boolean needSpill = false;
            if (reg instanceof Backend.Value.Operand.Register.AArch64CPUReg) {
                int idx = ((Backend.Value.Operand.Register.AArch64CPUReg) reg).getIndex();
                needSpill = idx >= 0 && idx < 8; // x0-x7
            } else if (reg instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
                int idx = ((Backend.Value.Operand.Register.AArch64FPUReg) reg).getIndex();
                needSpill = idx >= 0 && idx < 8; // v0-v7
            }
            if (needSpill) {
                Backend.Value.Operand.Register.AArch64VirReg tmp =
                        new Backend.Value.Operand.Register.AArch64VirReg(reg instanceof Backend.Value.Operand.Register.AArch64FPUReg);
                AArch64Move spillMove = new AArch64Move(tmp, reg, false);
                addInstr(spillMove, insList, predefine);
                RegList.put(entry.getKey(), tmp);
            }
        }
        
        // ARMv8调用约定：前8个整型参数用x0-x7，前8个浮点参数用v0-v7，其余参数通过栈传递
        int intArgCount = 0;
        int floatArgCount = 0;
        long stackOffset = 0;
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
            
                    boolean useStack = false;
            if (isFloat) {
                useStack = floatArgCount >= 8;
                floatArgCount++;
            } else {
                useStack = intArgCount >= 8;
                intArgCount++;
            }
            
            if (!useStack) {           
                AArch64Reg argReg;
                if (isFloat) {
                    argReg = AArch64FPUReg.getAArch64FArgReg(floatArgCount - 1);
                } else {
                    argReg = AArch64CPUReg.getAArch64ArgReg(intArgCount - 1);
                }
                if (arg instanceof ConstantInt) {
                    long value = ((ConstantInt) arg).getValue();

                    if (value > 65535 || value < -65536) {
                        loadLargeImmediate(argReg, value, insList, predefine);
                    } else {
                        AArch64Move moveInst = new AArch64Move(argReg, new AArch64Imm(value), true);
                        addInstr(moveInst, insList, predefine);
                    }
                } else if (arg instanceof ConstantFloat) {
                    double floatValue = ((ConstantFloat) arg).getValue();
                    
                    AArch64VirReg fReg = new AArch64VirReg(true);
                    
                    loadFloatConstant(fReg, floatValue, insList, predefine);
                    
                    AArch64Fmov fmovInst = new AArch64Fmov(argReg, fReg);
                    addInstr(fmovInst, insList, predefine);
                } else {
                                        if (!RegList.containsKey(arg)) {
                        if (arg instanceof Instruction) {
                            parseInstruction((Instruction) arg, false);
                        } else if (arg instanceof GlobalVariable) {
                            GlobalVariable globalVar = (GlobalVariable) arg;
                            String globalName = removeLeadingAt(globalVar.getName());
                            AArch64Label label = new AArch64Label(globalName);
                            
                            AArch64VirReg tempReg = new AArch64VirReg(false);
                            loadGlobalAddress(tempReg, label, insList, predefine);
                            
                            if (arg.getType() instanceof PointerType) {
                                RegList.put(arg, tempReg);
                            } else {
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
                            AArch64VirReg floatReg = new AArch64VirReg(true);
                            RegList.put(arg, floatReg);
                        } else {
                            AArch64VirReg intReg = new AArch64VirReg(false);
                            RegList.put(arg, intReg);
                        }
                    }
                    
                    AArch64Reg argValueReg = RegList.get(arg);
                    if (argValueReg != null) {
                        if (functionName.equals("putfloat") && isFloat) {
                            AArch64Move moveInst = new AArch64Move(argReg, argValueReg, false);
                            addInstr(moveInst, insList, predefine);
                        } else {
                            AArch64Move moveInst = new AArch64Move(argReg, argValueReg, false);
                            addInstr(moveInst, insList, predefine);
                        }
                    } else {
                        System.err.println("警告: 参数 " + arg + " 没有关联的寄存器，使用零寄存器");
                        AArch64Move moveInst = new AArch64Move(argReg, AArch64CPUReg.getZeroReg(), false);
                        addInstr(moveInst, insList, predefine);
                    }
                }

            } else {
                stackOffset += 8;
                stackArgList.add(arg);

            }
        }
        
        stackOffset = ((stackOffset + 15) & ~15);


        //栈自减，为溢出形参提供空间
        if (stackOffset > 0) {
            AArch64Operand sizeOp = checkImmediate(stackOffset, ImmediateRange.ADD_SUB, insList, predefine);
            
            if (sizeOp instanceof AArch64Reg) {
                ArrayList<AArch64Operand> operands = new ArrayList<>();
                operands.add(AArch64CPUReg.getAArch64SpReg());
                operands.add(sizeOp);
                AArch64Binary addInst = new AArch64Binary(operands, AArch64CPUReg.getAArch64SpReg(), AArch64Binary.AArch64BinaryType.sub);
                addInst.setUse32BitMode(false); // 栈指针操作使用64位寄存器
                addInstr(addInst, insList, predefine);
            } else {
                AArch64Binary addInst = new AArch64Binary(
                    AArch64CPUReg.getAArch64SpReg(), 
                    AArch64CPUReg.getAArch64SpReg(), 
                    (AArch64Imm)sizeOp, 
                    AArch64Binary.AArch64BinaryType.sub
                );
                addInst.setUse32BitMode(false); // 栈指针操作使用64位寄存器
                addInstr(addInst, insList, predefine);
            }
        }


        if (stackArgList.size() > 0) {
            int tempStackOffset = 0;
            for (Value arg : stackArgList) {
                if (arg instanceof ConstantInt) {
                    long value = ((ConstantInt) arg).getValue();
                    AArch64VirReg tempReg = new AArch64VirReg(false);
                    AArch64Imm imm = new AArch64Imm(value);
                    
                    AArch64Move moveInst = new AArch64Move(tempReg, imm, true);
                    addInstr(moveInst, insList, predefine);
                    
                    AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                          
                    handleLargeStackOffset(spReg, tempStackOffset, tempReg, false, false, insList, predefine);
                } else if (arg instanceof ConstantFloat) {
                    double floatValue = ((ConstantFloat) arg).getValue();
                    
                    AArch64VirReg fReg = new AArch64VirReg(true);
                    
                    loadFloatConstant(fReg, floatValue, insList, predefine);
                    
                    AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                      
                    handleLargeStackOffset(spReg, tempStackOffset, fReg, false, true, insList, predefine);
                } else {
                    if (!RegList.containsKey(arg)) {
                        if (arg instanceof Instruction) {
                            parseInstruction((Instruction) arg, false);
                        } else if (arg instanceof GlobalVariable) {
                            GlobalVariable globalVar = (GlobalVariable) arg;
                            String globalName = removeLeadingAt(globalVar.getName());
                            AArch64Label label = new AArch64Label(globalName);
                            
                            AArch64VirReg tempReg = new AArch64VirReg(false);
                            loadGlobalAddress(tempReg, label, insList, predefine);
                            
                            AArch64VirReg valueReg;
                            if (arg.getType() instanceof FloatType) {
                                valueReg = new AArch64VirReg(true);
                            } else {
                                valueReg = new AArch64VirReg(false);
                            }
                            
                            AArch64Load loadInst = new AArch64Load(tempReg, new AArch64Imm(0), valueReg);
                            addInstr(loadInst, insList, predefine);
                            
                            RegList.put(arg, valueReg);
                        } else if (arg.getType() instanceof FloatType) {
                            AArch64VirReg floatReg = new AArch64VirReg(true);
                            RegList.put(arg, floatReg);
                        } else {
                            AArch64VirReg intReg = new AArch64VirReg(false);
                            RegList.put(arg, intReg);
                        }
                    }
                    
                    AArch64Reg argValueReg = RegList.get(arg);
                    AArch64Instruction memInst = null;
                    if (argValueReg != null) {
                        AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                        // System.out.println(tempStackOffset + " " + argValueReg + " " + arg);

                        memInst = handleLargeStackOffset(spReg, tempStackOffset, argValueReg, false, arg.getType() instanceof FloatType, insList, predefine);
                    } else {
                        System.err.println("警告: 栈参数 " + arg + " 没有关联的寄存器，使用零寄存器");
                        AArch64VirReg tempReg = new AArch64VirReg(false);
                        AArch64Move moveInst = new AArch64Move(tempReg, AArch64CPUReg.getZeroReg(), false);
                        addInstr(moveInst, insList, predefine);
                        
                        AArch64CPUReg spReg = AArch64CPUReg.getAArch64SpReg();
                        
                        memInst = handleLargeStackOffset(spReg, tempStackOffset, tempReg, false, false, insList, predefine);
                    }

                    memInst.setCalleeParamOffset(stackOffset);
                }
                tempStackOffset += 8;
            }
        }

        if (AArch64MyLib.has64BitVersion(functionName)) {
            AArch64MyLib.markFunction64Used(functionName);
            functionName = AArch64MyLib.get64BitFunctionName(functionName);
        }
        
        AArch64Label functionLabel = new AArch64Label(functionName);
        
        AArch64Call callInst = new AArch64Call(functionLabel);
        
        // for (Value value : RegList.keySet()) {
        //     AArch64Reg reg = RegList.get(value);
        //     if (reg instanceof AArch64CPUReg && ((AArch64CPUReg) reg).canBeReorder()) {
        //         callInst.addUsedReg(reg);
        //     }
        // }
        
        addInstr(callInst, insList, predefine);
        
        if (!ins.isVoidCall()) {
            AArch64Reg returnReg, resultReg;
            if (ins.getCallee().getReturnType().isIntegerType()) {
                returnReg = AArch64CPUReg.getAArch64CPURetValueReg();
                resultReg = getOrCreateRegister(ins, false);
            } else {
                returnReg = AArch64FPUReg.getAArch64FPURetValueReg();
                resultReg = getOrCreateRegister(ins, true);
            }
            
            if (returnReg != null) {
                if (functionName.equals("getfloat") && ins.getCallee().getReturnType() instanceof FloatType) {
                    AArch64Move moveInst = new AArch64Move(resultReg, returnReg, false);
                    addInstr(moveInst, insList, predefine);
                } else {
                    AArch64Move moveReturnInst = new AArch64Move(resultReg, returnReg, false);
                    addInstr(moveReturnInst, insList, predefine);
                    
                        
                }
            } else {
                System.err.println("错误: 返回寄存器为空，跳过移动返回值");
            }
        }

        if (stackOffset > 0) {
            AArch64Operand sizeOp = checkImmediate(stackOffset, ImmediateRange.ADD_SUB, insList, predefine);
            
            if (sizeOp instanceof AArch64Reg) {
                ArrayList<AArch64Operand> operands = new ArrayList<>();
                operands.add(AArch64CPUReg.getAArch64SpReg());
                operands.add(sizeOp);
                AArch64Binary addInst = new AArch64Binary(operands, AArch64CPUReg.getAArch64SpReg(), AArch64Binary.AArch64BinaryType.add);
                addInst.setUse32BitMode(false); // 栈指针操作使用64位寄存器
                addInstr(addInst, insList, predefine);
            } else {
                AArch64Binary addInst = new AArch64Binary(
                    AArch64CPUReg.getAArch64SpReg(), 
                    AArch64CPUReg.getAArch64SpReg(), 
                    (AArch64Imm)sizeOp, 
                    AArch64Binary.AArch64BinaryType.add
                );
                addInst.setUse32BitMode(false); // 栈指针操作使用64位寄存器
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
        
        Value source = ins.getSource();
        Type targetType = ins.getType();
        OpCode conversionType = ins.getConversionType();
        
        AArch64Reg srcReg;
        if (source instanceof ConstantInt) {
            long value = ((ConstantInt) source).getValue();
            srcReg = new AArch64VirReg(false);

            if (value > 65535 || value < -65536) {
                loadLargeImmediate(srcReg, value, insList, predefine);
            } else {
                AArch64Move moveInst = new AArch64Move(srcReg, new AArch64Imm(value), true);
                addInstr(moveInst, insList, predefine);
            }
        } else if (source instanceof ConstantFloat) {
            double value = ((ConstantFloat) source).getValue();
            AArch64VirReg fReg = new AArch64VirReg(true);
            loadFloatConstant(fReg, value, insList, predefine);
            srcReg = fReg;
        } else if (RegList.containsKey(source)) {
            srcReg = RegList.get(source);
        } else {
            srcReg = null;
            // System.out.println("error no virReg: " + source);
        }
        
        boolean isFloat = targetType.isFloatType();
        AArch64Reg destReg = getOrCreateRegister(ins, isFloat);
        
        AArch64Cvt.CvtType armCvtType;
        
        if (ins.isIntToFloat()) {
            if (conversionType == OpCode.SITOFP) {
                armCvtType = AArch64Cvt.CvtType.SCVTF;
            } else {
                armCvtType = AArch64Cvt.CvtType.UCVTF;  // 无符号整数转浮点
            }
        } else if (ins.isFloatToInt()) {
            if (conversionType == OpCode.FPTOSI) {
                armCvtType = AArch64Cvt.CvtType.FCVTZS; // 浮点转有符号整数，向零舍入
            } else {
                armCvtType = AArch64Cvt.CvtType.FCVTZU; // 浮点转无符号整数，向零舍入
            }
        } else if (ins.isTruncation()) {
            ArrayList<AArch64Operand> andOps = new ArrayList<>();
            andOps.add(srcReg);
            
            long mask;
            if (targetType instanceof IntegerType) {
                int bits = ((IntegerType) targetType).getBitWidth();
                mask = (1L << bits) - 1;
            } else {
                mask = 0xFFFFFFFFL;
            }
            
            AArch64Imm maskImm = new AArch64Imm(mask);
            andOps.add(maskImm);
            
            AArch64Binary andInst = new AArch64Binary(andOps, destReg, AArch64Binary.AArch64BinaryType.and);
            addInstr(andInst, insList, predefine);
            
            if (predefine) {
                predefines.put(ins, insList);
            }
            return;
        } else if (ins.isExtension()) {
            if (conversionType == OpCode.SEXT) {
                AArch64Move sextInst = new AArch64Move(destReg, srcReg, false);
                addInstr(sextInst, insList, predefine);
            } else {
                AArch64Move zextInst = new AArch64Move(destReg, srcReg, false);
                addInstr(zextInst, insList, predefine);
            }
            
            if (predefine) {
                predefines.put(ins, insList);
            }
            return;
        } else {
            AArch64Move moveInst = new AArch64Move(destReg, srcReg, false);
            addInstr(moveInst, insList, predefine);
            
            if (predefine) {
                predefines.put(ins, insList);
            }
            return;
        }
        AArch64Cvt cvtInst = new AArch64Cvt(srcReg, armCvtType, destReg);
        addInstr(cvtInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseLoad(LoadInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        Value pointer = ins.getPointer();
        Type loadedType = ins.getLoadedType();
        boolean isFloat = loadedType instanceof FloatType;
        
        AArch64Reg destReg = getOrCreateRegister(ins, isFloat);
        
        AArch64Reg baseReg = null;
        AArch64Operand offsetOp = new AArch64Imm(0);
        
        if (pointer instanceof GetElementPtrInstruction) {
            if (!RegList.containsKey(pointer)) {
                // System.out.println("error: GEPinst not parse!");
                parsePtrInst((GetElementPtrInstruction) pointer, true);
            }
            
            if (RegList.containsKey(pointer)) {
                baseReg = RegList.get(pointer);
            } 
            else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            AArch64Label label = new AArch64Label(globalName);
            
            baseReg = new AArch64VirReg(false);
            
            loadGlobalAddress(baseReg, label, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction)pointer, true);
            }

            baseReg = AArch64CPUReg.getAArch64SpReg();
            long offset = ptrList.get(pointer);
            
            offsetOp = checkImmediate(offset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
        } else if (pointer instanceof Argument) {
            Argument arg = (Argument) pointer;
            if (RegList.containsKey(arg)) {
                baseReg = RegList.get(arg);
            } else if (curAArch64Function.getRegArg(arg) != null) {
                baseReg = curAArch64Function.getRegArg(arg);
            } else if (curAArch64Function.getStackArg(arg) != null) {
                baseReg = new AArch64VirReg(false);
                long stackParamOffset = curAArch64Function.getStackArg(arg); // 前8个在寄存器中
                
                AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                
                if (paramOffsetOp instanceof AArch64Reg) {
                    AArch64VirReg addrReg = new AArch64VirReg(false);
                    ArrayList<AArch64Operand> operands = new ArrayList<>();
                    operands.add(AArch64CPUReg.getAArch64FPReg());
                    operands.add(paramOffsetOp);
                    AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                    addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                    addInstr(addInst, insList, predefine);
                    
                    AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), baseReg);
                    addInstr(loadParamInst, insList, predefine);
                } else {
                    AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                        paramOffsetOp, baseReg);
                    addInstr(loadParamInst, insList, predefine);
                }
            }
            
            RegList.put(pointer, baseReg);
        } else {
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                    baseReg = RegList.get(pointer);
                } else {
                    // 使用通用方法获取指针寄存器
                    boolean ptrIsFloat = pointer.getType() instanceof FloatType;
                    baseReg = getOrCreateRegister(pointer, ptrIsFloat);
                }
            } else {
                baseReg = RegList.get(pointer);
            }
        }
        
        if (baseReg == null) {
            throw new RuntimeException("无法确定指针的基地址寄存器: " + pointer);
        }
        
        if (offsetOp instanceof AArch64Imm) {
            handleLargeStackOffset(baseReg, ((AArch64Imm)offsetOp).getValue(), destReg, true, isFloat, insList, predefine);
        } else {
            AArch64Load loadInst = new AArch64Load(baseReg, offsetOp, destReg);
            addInstr(loadInst, insList, predefine);
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parsePtrInst(GetElementPtrInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        Value pointer = ins.getPointer();
        List<Value> indices = ins.getIndices();
        
        AArch64Reg destReg = getOrCreateRegister(ins, false);
        
        AArch64Reg baseReg = null;

        if (pointer instanceof GlobalVariable) {
            String globalName = removeLeadingAt(((GlobalVariable) pointer).getName());
            AArch64Label label = new AArch64Label(globalName);
            
            baseReg = new AArch64VirReg(false);
            loadGlobalAddress(baseReg, label, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction) pointer, true);
            }
            
            baseReg = new AArch64VirReg(false);
            
            long offset = ptrList.get(pointer);
            AArch64Operand offsetOp = checkImmediate(offset, ImmediateRange.ADD_SUB, insList, predefine);
            
            if (offsetOp instanceof AArch64Reg) {
                ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                addOperands.add(AArch64CPUReg.getAArch64SpReg());
                addOperands.add(offsetOp);
                AArch64Binary addInst = new AArch64Binary(addOperands, baseReg, AArch64Binary.AArch64BinaryType.add);
                addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                addInstr(addInst, insList, predefine);
            } else {
                AArch64Binary addInst = new AArch64Binary(baseReg, AArch64CPUReg.getAArch64SpReg(), (AArch64Imm)offsetOp, AArch64Binary.AArch64BinaryType.add);
                addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                addInstr(addInst, insList, predefine);
            }
        } else if (pointer instanceof Argument) {
            Argument arg = (Argument) pointer;
            if (RegList.containsKey(arg)) {
                baseReg = RegList.get(arg);
            } else if (curAArch64Function.getRegArg(arg) != null) {
                baseReg = curAArch64Function.getRegArg(arg);
            } else if (curAArch64Function.getStackArg(arg) != null) {
                baseReg = new AArch64VirReg(false);
                long stackParamOffset = curAArch64Function.getStackArg(arg); // 前8个在寄存器中
                
                AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                
                if (paramOffsetOp instanceof AArch64Reg) {
                    AArch64VirReg addrReg = new AArch64VirReg(false);
                    ArrayList<AArch64Operand> operands = new ArrayList<>();
                    operands.add(AArch64CPUReg.getAArch64FPReg());
                    operands.add(paramOffsetOp);
                    AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                    addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                    addInstr(addInst, insList, predefine);
                    
                    AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), baseReg);
                    addInstr(loadParamInst, insList, predefine);
                } else {
                    AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                        paramOffsetOp, baseReg);
                    addInstr(loadParamInst, insList, predefine);
                }
            }
            RegList.put(pointer, baseReg);
        } else {
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction) pointer, true);
                } else if (pointer.getType() instanceof PointerType) {
                    AArch64Reg ptrReg = new AArch64VirReg(false);
                    RegList.put(pointer, ptrReg);
                } else if (pointer.getType() instanceof FloatType) {
                    AArch64Reg floatReg = new AArch64VirReg(true);
                    RegList.put(pointer, floatReg);
                } else if (pointer.getType() instanceof IntegerType) {
                    AArch64Reg intReg = new AArch64VirReg(false);
                    RegList.put(pointer, intReg);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = RegList.get(pointer);
        }
        
        if (baseReg == null) {
            throw new RuntimeException("无法确定指针的基地址寄存器: " + pointer);
        }
        
        AArch64Move moveInst = new AArch64Move(destReg, baseReg, false);
        addInstr(moveInst, insList, predefine);
        
        if (indices.size() > 0) {
                    for (int i = 0; i < indices.size(); i++) {
            Value indexValue = indices.get(i);
            
            int elementSize = 8;
                
                if (indexValue instanceof ConstantInt) {
                    long indexVal = ((ConstantInt) indexValue).getValue();
                    long offset = indexVal * elementSize;
                    
                    if (offset != 0) {
                        AArch64Operand offsetOp = checkImmediate(offset, ImmediateRange.ADD_SUB, insList, predefine);
                        
                        if (offsetOp instanceof AArch64Imm) {
                            AArch64Binary addInst = new AArch64Binary(destReg, destReg, (AArch64Imm)offsetOp, AArch64Binary.AArch64BinaryType.add);
                            addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                            addInstr(addInst, insList, predefine);
                        } else {
                            ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                            addOperands.add(destReg);
                            addOperands.add(offsetOp);
                            AArch64Binary addInst = new AArch64Binary(addOperands, destReg, AArch64Binary.AArch64BinaryType.add);
                            addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                            addInstr(addInst, insList, predefine);
                        }
                    }
                } else {
                    if (!RegList.containsKey(indexValue)) {
                        if (indexValue instanceof Instruction) {
                            parseInstruction((Instruction) indexValue, true);
                        } else {
                            throw new RuntimeException("未处理的索引类型: " + indexValue);
                        }
                    }
                    
                    AArch64Reg indexReg = RegList.get(indexValue);
                    
                    AArch64Reg tempReg = new AArch64VirReg(false);
                    
                    if (elementSize != 1) {
                        if (isPowerOfTwo(elementSize)) {
                            int shiftAmount = (int)(Math.log(elementSize) / Math.log(2));
                            AArch64Binary lslInst = new AArch64Binary(tempReg, indexReg, new AArch64Imm(shiftAmount), AArch64Binary.AArch64BinaryType.lsl);
                            lslInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                            addInstr(lslInst, insList, predefine);
                        } else {
                            if (elementSize > 65535 || elementSize < -65536) {
                                loadLargeImmediate(tempReg, elementSize, insList, predefine);
                            } else {
                                AArch64Move moveElemSizeInst = new AArch64Move(tempReg, new AArch64Imm(elementSize), false);
                                addInstr(moveElemSizeInst, insList, predefine);
                            }
                            
                            ArrayList<AArch64Operand> mulOperands = new ArrayList<>();
                            mulOperands.add(indexReg);
                            mulOperands.add(tempReg);
                            
                            AArch64Binary mulInst = new AArch64Binary(mulOperands, tempReg, AArch64Binary.AArch64BinaryType.mul);
                            mulInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                            addInstr(mulInst, insList, predefine);
                        }
                    } else {
                        AArch64Move moveIndexInst = new AArch64Move(tempReg, indexReg, false);
                        addInstr(moveIndexInst, insList, predefine);
                    }

                    ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                    addOperands.add(destReg);
                    addOperands.add(tempReg);
                    
                    AArch64Binary addInst = new AArch64Binary(addOperands, destReg, AArch64Binary.AArch64BinaryType.add);
                    addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                    addInstr(addInst, insList, predefine);
                }
            }
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private boolean isPowerOfTwo(long n) {
        if (n <= 0) return false;
        return (n & (n - 1)) == 0;
    }

    private void parseRetInst(ReturnInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        if (!ins.isVoidReturn()) {
            Value returnValue = ins.getReturnValue();
            
            AArch64Reg returnReg;
            if (returnValue.getType() instanceof FloatType) {
                returnReg = AArch64FPUReg.getAArch64FPURetValueReg();
            } else {
                returnReg = AArch64CPUReg.getAArch64CPURetValueReg();
            }
            
            if (returnValue instanceof ConstantInt) {
                long value = ((ConstantInt) returnValue).getValue();
                if (value > 65535 || value < -65536) {
                    loadLargeImmediate(returnReg, value, insList, predefine);
                } else {
                    addInstr(new AArch64Move(returnReg, new AArch64Imm(value), false), insList, predefine);
                }
            } else if (returnValue instanceof ConstantFloat) {
                float value = (float)((ConstantFloat) returnValue).getValue();
                loadFloatConstant(returnReg, value, insList, predefine);
            } else if (returnValue instanceof Argument) {
                Argument arg = (Argument) returnValue;
                AArch64Reg paramReg = ensureArgumentAArch64Reg(arg, insList, predefine);
                
                if (paramReg != null && !paramReg.equals(returnReg)) {
                    addInstr(new AArch64Move(returnReg, paramReg, false), 
                        insList, predefine);
                }
            } else {
                AArch64Operand operand = processOperand(returnValue, insList, predefine, 
                                                     returnValue.getType() instanceof FloatType, false);
                if (operand instanceof AArch64Reg) {
                    if (!operand.equals(returnReg)) {
                        addInstr(new AArch64Move(returnReg, operand, false), 
                            insList, predefine);
                    }
                } else if (operand instanceof AArch64Imm) {
                    addInstr(new AArch64Move(returnReg, operand, false), insList, predefine);
                }
            }
        }
        
        if (!curAArch64Function.getName().contains("main")) {
            curAArch64Function.loadCalleeRegs(curAArch64Block);
        }



        AArch64Move movSpFp = new AArch64Move(AArch64CPUReg.getAArch64SpReg(), AArch64CPUReg.getAArch64FPReg(), false);
        addInstr(movSpFp, insList, predefine);
        

        AArch64LoadPair ldpInst = new AArch64LoadPair(
            AArch64CPUReg.getAArch64SpReg(),
            new AArch64Imm(16), 
            AArch64CPUReg.getAArch64FPReg(),
            AArch64CPUReg.getAArch64RetReg(),
            false,
            true
        );
        addInstr(ldpInst, insList, predefine);
        
        AArch64Ret retInst = new AArch64Ret();
        addInstr(retInst, insList, predefine);
        
        if (!predefine) {
            curAArch64Block.setHasReturnInstruction(true);
        }
        

        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseStore(StoreInstruction ins, boolean predefine) {

        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        

        Value valueToStore = ins.getValue();
        Value pointer = ins.getPointer();
        

        AArch64Reg valueReg;
        
        if (valueToStore instanceof ConstantInt) {
    
            valueReg = new AArch64VirReg(false);
            long value = ((ConstantInt)valueToStore).getValue();
    
            if (value > 65535 || value < -65536) {
                loadLargeImmediate(valueReg, value, insList, predefine);
            } else {
                AArch64Move moveInst = new AArch64Move(valueReg, new AArch64Imm((int)value), false);
                addInstr(moveInst, insList, predefine);
            }
        } 
        else if (valueToStore instanceof ConstantFloat) {
            valueReg = new AArch64VirReg(true);
    
            double floatValue = ((ConstantFloat) valueToStore).getValue();
            loadFloatConstant(valueReg, floatValue, insList, predefine);
        }
        else if (valueToStore instanceof Argument) {
    
            Argument arg = (Argument) valueToStore;
            
    
            if (!RegList.containsKey(valueToStore)) {
                valueReg = valueToStore.getType() instanceof FloatType ? 
                           new AArch64VirReg(true) : new AArch64VirReg(false);
                
        
                if (curAArch64Function.getRegArg(arg) != null) {
            
                    AArch64Reg argReg = curAArch64Function.getRegArg(arg);
                    AArch64Move moveInst = new AArch64Move(valueReg, argReg, valueReg instanceof AArch64FPUReg);
                    addInstr(moveInst, insList, predefine);
                } else if (curAArch64Function.getStackArg(arg) != null) {
                    long stackParamOffset = curAArch64Function.getStackArg(arg);
                    
                    AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                    
                    if (paramOffsetOp instanceof AArch64Reg) {
                        AArch64VirReg addrReg = new AArch64VirReg(false);
                        ArrayList<AArch64Operand> operands = new ArrayList<>();
                        operands.add(AArch64CPUReg.getAArch64FPReg());
                        operands.add(paramOffsetOp);
                        AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                        addInstr(addInst, insList, predefine);
                        
                        AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), valueReg);
                        addInstr(loadParamInst, insList, predefine);
                    } else {
                        AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                            paramOffsetOp, valueReg);
                        addInstr(loadParamInst, insList, predefine);
                    }
                }
                RegList.put(valueToStore, valueReg);
            } else {
                valueReg = RegList.get(valueToStore);
            }
        }
        else {

            if (!RegList.containsKey(valueToStore)) {
                if (valueToStore instanceof Instruction) {
                    parseInstruction((Instruction)valueToStore, true);
                } else if (valueToStore.getType() instanceof FloatType) {
                    
                    AArch64Reg floatReg = new AArch64VirReg(true);
                    
                    RegList.put(valueToStore, floatReg);
                } else if (valueToStore.getType() instanceof IntegerType) {
                    
                    AArch64Reg cpuReg = new AArch64VirReg(false);
                    RegList.put(valueToStore, cpuReg);
                } else {
                    throw new RuntimeException("未处理的存储值类型: " + valueToStore);
                }
            }
            valueReg = RegList.get(valueToStore);
        }
        

        AArch64Reg baseReg = new AArch64VirReg(false);
        AArch64Operand offsetOp = new AArch64Imm(0);
        

        if (pointer instanceof GetElementPtrInstruction) {
            if (!RegList.containsKey(pointer)) {
                parsePtrInst((GetElementPtrInstruction)pointer, true);
            }
            
            if (RegList.containsKey(pointer)) {
                baseReg = RegList.get(pointer);
            } else if (ptrList.containsKey(pointer)) {
                baseReg = AArch64CPUReg.getAArch64SpReg();
                long offset = ptrList.get(pointer);
                offsetOp = checkImmediate(offset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
            } else {
                throw new RuntimeException("无法获取GEP指令的地址: " + pointer);
            }
        } else if (pointer instanceof GlobalVariable) {
            String globalName = removeLeadingAt(((GlobalVariable)pointer).getName());
            AArch64Label label = new AArch64Label(globalName);
            
            baseReg = new AArch64VirReg(false);
            
            loadGlobalAddress(baseReg, label, insList, predefine);
        } else if (pointer instanceof AllocaInstruction) {
            if (!ptrList.containsKey(pointer)) {
                parseAlloc((AllocaInstruction)pointer, true);
            }
            
            baseReg = AArch64CPUReg.getAArch64SpReg();
            long offset = ptrList.get(pointer);
            
            offsetOp = checkImmediate(offset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
        } else if (pointer instanceof Argument) {
            Argument arg = (Argument) pointer;
            if (RegList.containsKey(arg)) {
                baseReg = RegList.get(arg);
            } else if (curAArch64Function.getRegArg(arg) != null) {
                baseReg = curAArch64Function.getRegArg(arg);
            } else if (curAArch64Function.getStackArg(arg) != null) {
                baseReg = new AArch64VirReg(false);
                long stackParamOffset = curAArch64Function.getStackArg(arg);
                
                AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                
                if (paramOffsetOp instanceof AArch64Reg) {
                    AArch64VirReg addrReg = new AArch64VirReg(false);
                    ArrayList<AArch64Operand> operands = new ArrayList<>();
                    operands.add(AArch64CPUReg.getAArch64FPReg());
                    operands.add(paramOffsetOp);
                    AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                    addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
                    addInstr(addInst, insList, predefine);
                    
                    AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), baseReg);
                    addInstr(loadParamInst, insList, predefine);
                } else {
                    AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                        paramOffsetOp, baseReg);
                    addInstr(loadParamInst, insList, predefine);
                }
            }
            RegList.put(pointer, baseReg);
        } else {
            if (!RegList.containsKey(pointer)) {
                if (pointer instanceof Instruction) {
                    parseInstruction((Instruction)pointer, true);
                } else {
                    throw new RuntimeException("未处理的指针类型: " + pointer);
                }
            }
            baseReg = RegList.get(pointer);
        }
        

        if (baseReg == null) {
            throw new RuntimeException("无法确定指针的基地址寄存器: " + pointer);
        }
        

        if (offsetOp instanceof AArch64Imm) {
    
            boolean isFloat = valueToStore.getType() instanceof FloatType;
            handleLargeStackOffset(baseReg, ((AArch64Imm)offsetOp).getValue(), valueReg, false, isFloat, insList, predefine);
        } else {
    
            AArch64Store storeInst = new AArch64Store(valueReg, baseReg, offsetOp);
            addInstr(storeInst, insList, predefine);
        }
        

        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseCompareInst(CompareInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        

        Value left = ins.getLeft();
        Value right = ins.getRight();
        OpCode predicate = ins.getPredicate();
        boolean isFloat = ins.isFloatCompare();
        

        AArch64Reg destReg = getOrCreateRegister(ins, false);
        

        AArch64Operand leftOp;
        if (left instanceof ConstantInt) {
            long value = ((ConstantInt) left).getValue();
            AArch64Reg leftReg = new AArch64VirReg(false);

            if (value > 65535 || value < -65536) {
                loadLargeImmediate(leftReg, value, insList, predefine);
            } else {
                AArch64Move moveInst = new AArch64Move(leftReg, new AArch64Imm(value), true);
                addInstr(moveInst, insList, predefine);
            }
            leftOp = leftReg;
        } else if (left instanceof ConstantFloat) {
    
            double floatValue = ((ConstantFloat) left).getValue();
            
    
            AArch64Reg fpuReg = new AArch64VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            
            leftOp = fpuReg;
        } else {
            // 使用通用方法获取左操作数寄存器
            boolean leftIsFloat = left.getType() instanceof FloatType;
            AArch64Reg leftReg = getOrCreateRegister(left, leftIsFloat);
            leftOp = leftReg;
                

        }
        
        AArch64Operand rightOp;
        if (right instanceof ConstantInt) {
            long value = ((ConstantInt) right).getValue();
            long abs = value < 0 ? -value : value;
            if (!isAddSubImmEncodable(abs)) {
                AArch64Reg rightReg = new AArch64VirReg(false);
                loadLargeImmediate(rightReg, value, insList, predefine);
                rightOp = rightReg;
            } else {
                rightOp = new AArch64Imm(abs);
            }
        } else if (right instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) right).getValue();
            AArch64Reg fpuReg = new AArch64VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            rightOp = fpuReg;
        } else {
            boolean rightIsFloat = right.getType() instanceof FloatType;
            AArch64Reg rightReg = getOrCreateRegister(right, rightIsFloat);
            rightOp = rightReg;
                

        }
        
        AArch64Compare.CmpType cmpType;
        
        if (!isFloat && right instanceof ConstantInt && rightOp instanceof AArch64Imm) {
            long k = ((ConstantInt) right).getValue();
            if (k < 0) {
                cmpType = AArch64Compare.CmpType.cmn;
                rightOp = new AArch64Imm(-k);
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
                condType = isFloat ? AArch64Tools.CondType.hi : AArch64Tools.CondType.hi;
                break;
            case UGE:
                condType = isFloat ? AArch64Tools.CondType.cs : AArch64Tools.CondType.cs;
                break;
            case ULT:
                condType = isFloat ? AArch64Tools.CondType.cc : AArch64Tools.CondType.cc;
                break;
            case ULE:
                condType = isFloat ? AArch64Tools.CondType.ls : AArch64Tools.CondType.ls;
                break;
            case UNE:
                condType = AArch64Tools.CondType.ne;
                break;
            case ORD:
                condType = AArch64Tools.CondType.vc;
                break;
            case UNO:
                condType = AArch64Tools.CondType.vs;
                break;
            default:
                System.err.println("不支持的比较谓词: " + predicate);
                condType = AArch64Tools.CondType.eq;
                break;
        }
        
        AArch64Cset csetInst = new AArch64Cset(destReg, condType);
        addInstr(csetInst, insList, predefine);
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parsePhiInst(PhiInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        String destName = ins.getName();
        AArch64Reg destReg;
        if (NameToReg.containsKey(destName)) {
            destReg = NameToReg.get(destName);
        } else {
            if (ins.getType() instanceof FloatType) {
                destReg = new AArch64VirReg(true);
            } else {
                destReg = new AArch64VirReg(false);
            }
            NameToReg.put(destName, destReg);
        }
        RegList.put(ins, destReg);
        
        Map<BasicBlock, Value> incomingValues = ins.getIncomingValues();
        
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            BasicBlock incomingBlock = entry.getKey();
            Value incomingValue = entry.getValue();
            
            AArch64Block armv8IncomingBlock = (AArch64Block) LabelList.get(incomingBlock);
            if (armv8IncomingBlock == null) {
                System.err.println("警告: 找不到Phi指令输入块的对应ARM块: " + incomingBlock.getName());
                continue;
            }
            
            AArch64Operand srcOp;
            if (incomingValue instanceof ConstantInt) {
                long value = ((ConstantInt) incomingValue).getValue();
                // 检查是否需要使用loadLargeImmediate
                if (value > 65535 || value < -65536) {
                    AArch64VirReg tempReg = new AArch64VirReg(false);
                    loadLargeImmediate(tempReg, value, insList, predefine);
                    srcOp = tempReg;
                } else {
                    srcOp = new AArch64Imm(value);
                }
            } else if (incomingValue instanceof ConstantFloat) {
                double floatValue = ((ConstantFloat) incomingValue).getValue();
                AArch64Reg fpuReg = new AArch64VirReg(true);
                loadFloatConstant(fpuReg, floatValue, insList, predefine);
                srcOp = fpuReg;
            }
            else {
                if (!RegList.containsKey(incomingValue)) {
                    if (incomingValue instanceof Instruction) {
                        parseInstruction((Instruction) incomingValue, true);
                    } else {
                        // 使用通用方法获取寄存器
                        boolean valueIsFloat = incomingValue.getType() instanceof FloatType;
                        AArch64Reg valueReg = getOrCreateRegister(incomingValue, valueIsFloat);
                        srcOp = valueReg;
                        continue;
                    }
                }
                
                if (RegList.containsKey(incomingValue)) {
                    srcOp = RegList.get(incomingValue);
                } else {
                    // 最后尝试通过变量名获取寄存器
                    boolean valueIsFloat = incomingValue.getType() instanceof FloatType;
                    srcOp = getOrCreateRegister(incomingValue, valueIsFloat);
                }
            }
            
            AArch64Move moveInst;
            if (srcOp instanceof AArch64Imm) {
                moveInst = new AArch64Move(destReg, srcOp, true);
            } else {
                moveInst = new AArch64Move(destReg, (AArch64Reg) srcOp, false);
            }
            
            if (!predefine) {
                AArch64Instruction lastInst = armv8IncomingBlock.getLastInstruction();
                if (lastInst != null && (lastInst instanceof AArch64Branch || lastInst instanceof AArch64Jump)) {
                    armv8IncomingBlock.insertBeforeInst(lastInst, moveInst);
                } else {
                    armv8IncomingBlock.addAArch64Instruction(moveInst);
                }
            } else {
                insList.add(moveInst);
            }
        }
        
        if (predefine) {
            predefines.put(ins, insList);
        }
    }

    private void parseUnaryInst(UnaryInstruction ins, boolean predefine) {
        ArrayList<AArch64Instruction> insList = predefine ? new ArrayList<>() : null;
        
        OpCode opCode = ins.getOpCode();
        Value operand = ins.getOperand();
        

        boolean isFloat = operand instanceof ConstantFloat || operand.getType() instanceof FloatType;
        AArch64Reg destReg = getOrCreateRegister(ins, isFloat);
        

        AArch64Operand srcOp;
        if (operand instanceof ConstantInt) {
            long value = ((ConstantInt) operand).getValue();
            // 检查是否需要使用loadLargeImmediate
            if (value > 65535 || value < -65536) {
                AArch64VirReg tempReg = new AArch64VirReg(false);
                loadLargeImmediate(tempReg, value, insList, predefine);
                srcOp = tempReg;
            } else {
                srcOp = new AArch64Imm(value);
            }
        } else if (operand instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) operand).getValue();
            AArch64Reg fpuReg = new AArch64VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            srcOp = fpuReg;
        } else {
            // 使用通用方法获取操作数寄存器
            boolean operandIsFloat = operand.getType() instanceof FloatType;
            AArch64Reg srcReg = getOrCreateRegister(operand, operandIsFloat);
            srcOp = srcReg;
        }
        
        
        switch (opCode) {
            case NEG:
                if (srcOp instanceof AArch64Imm) {
                    long value = ((AArch64Imm) srcOp).getValue();
                    long negValue = -value;
                    // 检查否定后的值是否仍在MOV指令范围内
                    if (negValue > 65535 || negValue < -65536) {
                        loadLargeImmediate(destReg, negValue, insList, predefine);
                    } else {
                        AArch64Move moveInst = new AArch64Move(destReg, new AArch64Imm(negValue), true);
                        addInstr(moveInst, insList, predefine);
                    }
                } else {
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
        
        Value source = ins.getSource();
        
        // 使用通用方法获取目标寄存器
        boolean isFloat = ins.getType() instanceof FloatType;
        AArch64Reg destReg = getOrCreateRegister(ins, isFloat);
        

        AArch64Operand srcOp;
        if (source instanceof ConstantInt) {
            long value = ((ConstantInt) source).getValue();
            // 检查是否需要使用loadLargeImmediate
            if (value > 65535 || value < -65536) {
                AArch64VirReg tempReg = new AArch64VirReg(false);
                loadLargeImmediate(tempReg, value, insList, predefine);
                srcOp = tempReg;
            } else {
                srcOp = new AArch64Imm(value);
            }
        } else if (source instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) source).getValue();
            AArch64Reg fpuReg = new AArch64VirReg(true);
            loadFloatConstant(fpuReg, floatValue, insList, predefine);
            srcOp = fpuReg;
        } else {
            if (!RegList.containsKey(source)) {
                if (source instanceof Argument) {
                    Argument arg = (Argument) source;
                    
                    // 检查是否是栈参数
                    if (curAArch64Function.getStackArg(arg) != null) {
                        // 栈参数，需要从栈中加载
                        AArch64Reg srcReg = source.getType() instanceof FloatType ? 
                                           new AArch64VirReg(true) : new AArch64VirReg(false);
                        
                        long stackParamOffset = curAArch64Function.getStackArg(arg);
                        AArch64Operand paramOffsetOp = checkImmediate(stackParamOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
                        
                        if (paramOffsetOp instanceof AArch64Reg) {
                            AArch64VirReg addrReg = new AArch64VirReg(false);
                            ArrayList<AArch64Operand> operands = new ArrayList<>();
                            operands.add(AArch64CPUReg.getAArch64FPReg());
                            operands.add(paramOffsetOp);
                            AArch64Binary addInst = new AArch64Binary(operands, addrReg, AArch64Binary.AArch64BinaryType.add);
                            addInstr(addInst, insList, predefine);
                            
                            AArch64Load loadParamInst = new AArch64Load(addrReg, new AArch64Imm(0), srcReg);
                            addInstr(loadParamInst, insList, predefine);
                        } else {
                            AArch64Load loadParamInst = new AArch64Load(AArch64CPUReg.getAArch64FPReg(), 
                                paramOffsetOp, srcReg);
                            addInstr(loadParamInst, insList, predefine);
                        }
                        
                        RegList.put(source, srcReg);
                        srcOp = srcReg;
                    } else {
                        // 寄存器参数，应该已经在RegList中了
                        AArch64Reg srcReg = curAArch64Function.getRegArg(arg);
                        if (srcReg != null) {
                            RegList.put(source, srcReg);
                            srcOp = srcReg;
                        } else {
                            // fallback
                            srcReg = source.getType() instanceof FloatType ? 
                                    new AArch64VirReg(true) : new AArch64VirReg(false);
                            RegList.put(source, srcReg);
                            srcOp = srcReg;
                        }
                    }
                } else {
                    // 尝试按名字复用
                    String sourceName = source.getName();
                    if (NameToReg.containsKey(sourceName)) {
                        AArch64Reg reusedReg = NameToReg.get(sourceName);
                        RegList.put(source, reusedReg);
                        srcOp = reusedReg;
                    } else {
                        if (source.getType() instanceof FloatType) {
                            AArch64Reg srcReg = new AArch64VirReg(true);
                            RegList.put(source, srcReg);
                            NameToReg.put(sourceName, srcReg);
                            srcOp = srcReg;
                        } else {
                            AArch64Reg srcReg = new AArch64VirReg(false);
                            RegList.put(source, srcReg);
                            NameToReg.put(sourceName, srcReg);
                            srcOp = srcReg;
                        }
                    }
                }
            } else {
                srcOp = RegList.get(source);
            }
        }
        
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


    private void loadLargeImmediate(AArch64Reg destReg, long value, ArrayList<AArch64Instruction> insList, boolean predefine) {
        long bits = value;
        AArch64Move movzInst = new AArch64Move(destReg, new AArch64Imm(bits & 0xFFFF), true, AArch64Move.MoveType.MOVZ);
        addInstr(movzInst, insList, predefine);
        if (((bits >> 16) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 16) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(16);
            addInstr(movkInst, insList, predefine);
        }
        
        if (((bits >> 32) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 32) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(32);
            addInstr(movkInst, insList, predefine);
        }
        
        if (((bits >> 48) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 48) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(48);
            addInstr(movkInst, insList, predefine);
        }
    }
    
    private void loadGlobalAddress(AArch64Reg destReg, AArch64Label label, ArrayList<AArch64Instruction> insList, boolean predefine) {
        AArch64Adrp adrpInst = new AArch64Adrp(destReg, label);
        addInstr(adrpInst, insList, predefine);
        
        ArrayList<AArch64Operand> addOperands = new ArrayList<>();
        addOperands.add(destReg);
        addOperands.add(new AArch64Label(":lo12:" + label.getLabelName()));
        AArch64Binary addInst = new AArch64Binary(addOperands, destReg, AArch64Binary.AArch64BinaryType.add);
        addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
        addInstr(addInst, insList, predefine);
    }
    

    private void loadFloatConstant(AArch64Reg destReg, double value, ArrayList<AArch64Instruction> insList, boolean predefine) {
        // 将 double 值截断为单精度并获取其 32-bit IEEE754 位模式
        int bits = Float.floatToIntBits((float) value);

        AArch64VirReg tempReg = new AArch64VirReg(false);

        AArch64Move movz = new AArch64Move(tempReg, new AArch64Imm(bits & 0xFFFF), true, AArch64Move.MoveType.MOVZ);
        addInstr(movz, insList, predefine);

        int high16 = (bits >> 16) & 0xFFFF;
        if (high16 != 0) {
            AArch64Move movk = new AArch64Move(tempReg, new AArch64Imm(high16), true, AArch64Move.MoveType.MOVK);
            movk.setShift(16);
            addInstr(movk, insList, predefine);
        }

        // fmov 将位模式解释为浮点数（w -> s）
        AArch64Fmov fmovInst = new AArch64Fmov(destReg, tempReg);
        addInstr(fmovInst, insList, predefine);
    }

    private AArch64Reg ensureArgumentAArch64Reg(Argument arg, ArrayList<AArch64Instruction> insList, boolean predefine) {
        if (RegList.containsKey(arg)) {
            return RegList.get(arg);
        }
        

        AArch64Reg argReg = curAArch64Function.getRegArg(arg);
        if (argReg != null) {
            RegList.put(arg, argReg);
            return argReg;
        }
        
        Long stackOffset = curAArch64Function.getStackArg(arg);
        if (stackOffset != null) {
            AArch64VirReg tempReg = arg.getType() instanceof FloatType ? 
                new AArch64VirReg(true) : new AArch64VirReg(false);
                
            AArch64Operand offsetOp = checkImmediate(stackOffset, ImmediateRange.MEMORY_OFFSET_UNSIGNED, insList, predefine);
            
            if (offsetOp instanceof AArch64Reg) {
                AArch64VirReg addrReg = new AArch64VirReg(false);
                ArrayList<AArch64Operand> addOperands = new ArrayList<>();
                addOperands.add(AArch64CPUReg.getAArch64FPReg());
                addOperands.add(offsetOp);
                AArch64Binary addInst = new AArch64Binary(addOperands, addrReg, AArch64Binary.AArch64BinaryType.add);
                addInstr(addInst, insList, predefine);
                
                AArch64Load loadInst = new AArch64Load(addrReg, new AArch64Imm(0), tempReg);
                addInstr(loadInst, insList, predefine);
            } else {
                AArch64Load loadInst = new AArch64Load(
                    AArch64CPUReg.getAArch64FPReg(), 
                    (AArch64Imm)offsetOp, 
                    tempReg
                );
                addInstr(loadInst, insList, predefine);
            }
            
            RegList.put(arg, tempReg);
            return tempReg;
        }
        
        System.err.println("错误: 无法找到参数 " + arg + " 的位置");
        return null;
    }

    /**
     * 处理余数运算 (a % b = a - (a / b) * b)
     */
    private void handleRemOperation(AArch64Operand leftOp, AArch64Operand rightOp, AArch64Reg destReg,
                                  ArrayList<AArch64Instruction> insList, boolean predefine) {
        AArch64Reg rightReg;
        if (rightOp instanceof AArch64Imm) {
            rightReg = new AArch64VirReg(false);
            AArch64Move moveInst = new AArch64Move(rightReg, rightOp, true);
            addInstr(moveInst, insList, predefine);
        } else {
            rightReg = (AArch64Reg) rightOp;
        }
        
        ArrayList<AArch64Operand> divOperands = new ArrayList<>();
        divOperands.add(leftOp);
        divOperands.add(rightReg);
        AArch64Binary divInst = new AArch64Binary(divOperands, destReg, AArch64Binary.AArch64BinaryType.sdiv);
        addInstr(divInst, insList, predefine);
        
        AArch64VirReg tempReg = new AArch64VirReg(false);
        ArrayList<AArch64Operand> mulOperands = new ArrayList<>();
        mulOperands.add(destReg);
        mulOperands.add(rightReg);
        AArch64Binary mulInst = new AArch64Binary(mulOperands, tempReg, AArch64Binary.AArch64BinaryType.mul);
        addInstr(mulInst, insList, predefine);
        
        ArrayList<AArch64Operand> subOperands = new ArrayList<>();
        subOperands.add(leftOp);
        subOperands.add(tempReg);
        AArch64Binary subInst = new AArch64Binary(subOperands, destReg, AArch64Binary.AArch64BinaryType.sub);
        addInstr(subInst, insList, predefine);
    }


    private AArch64Operand checkImmediate(long value, ImmediateRange instrType, ArrayList<AArch64Instruction> insList, boolean predefine) {
        if (instrType.isInRange(value)) {
            return new AArch64Imm(value);
        } else {
            AArch64VirReg tempReg = new AArch64VirReg(false);
            
            if (instrType == ImmediateRange.MOV && (value > 65535 || value < -65536)) {
                loadLargeImmediate(tempReg, value, insList, predefine);
            } else {
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

    private enum ImmediateRange {
        DEFAULT(4095),            
        CMP(4095),               
        MOV(65535),              
        ADD_SUB(4095),           
        LOGICAL(4095),           
        MEMORY_OFFSET_SIGNED(255), 
        MEMORY_OFFSET_UNSIGNED(32760), 
        SHIFT_AMOUNT(63),        
        STACK(32760);            

        private final long maxValue;
        private final long minValue;

        ImmediateRange(long maxValue) {
            this.maxValue = maxValue;
            this.minValue = 0; 
        }

        ImmediateRange(long minValue, long maxValue) {
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public boolean isInRange(long value) {
            switch (this) {
                case MEMORY_OFFSET_SIGNED:
                    return value >= -256 && value <= 255;
                case MEMORY_OFFSET_UNSIGNED:
                    return value >= 0 && value <= 32760 && (value % 8 == 0);
                case SHIFT_AMOUNT:
                    return value >= 0 && value <= 63;
                case STACK:
                    return value >= 0 && value <= 32760 && (value % 8 == 0);
                default:
                    // 其他指令：12位无符号立即数
                    return value >= 0 && value <= maxValue;
            }
        }
    }

    private AArch64Operand processOperand(Value operand, ArrayList<AArch64Instruction> insList, 
                                       boolean predefine, boolean isFloat, boolean requiresReg, ImmediateRange immRange) {
        if (operand instanceof ConstantInt) {
            long value = ((ConstantInt) operand).getValue();
            if (requiresReg) {
                AArch64VirReg tempReg = new AArch64VirReg(false);
                if (value > 65535 || value < -65536) {
                    loadLargeImmediate(tempReg, value, insList, predefine);
                } else {
                    AArch64Move moveInst = new AArch64Move(tempReg, new AArch64Imm(value), true);
                    addInstr(moveInst, insList, predefine);
                }
                return tempReg;
            } else {
                return checkImmediate(value, immRange, insList, predefine);
            }
        } 
        else if (operand instanceof ConstantFloat) {
            double floatValue = ((ConstantFloat) operand).getValue();
            AArch64VirReg fReg = new AArch64VirReg(true);
            loadFloatConstant(fReg, floatValue, insList, predefine);
            return fReg;
        } 
        else if (operand instanceof Argument) {
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
        else {
            // 使用通用方法获取寄存器
            return getOrCreateRegister(operand, isFloat);
        }
    }


    private AArch64Operand processOperand(Value operand, ArrayList<AArch64Instruction> insList, 
                                       boolean predefine, boolean isFloat, boolean requiresReg) {
        return processOperand(operand, insList, predefine, isFloat, requiresReg, ImmediateRange.DEFAULT);
    }

    private AArch64Operand createSafeMemoryOperand(long offset, ArrayList<AArch64Instruction> insList, boolean predefine) {
        if (ImmediateRange.MEMORY_OFFSET_SIGNED.isInRange(offset)) {
            return new AArch64Imm(offset);
        }
        
        if (ImmediateRange.MEMORY_OFFSET_UNSIGNED.isInRange(offset)) {
            return new AArch64Imm(offset);
        }
        
        AArch64VirReg tempReg = new AArch64VirReg(false);
        loadLargeImmediate(tempReg, offset, insList, predefine);
        return tempReg;
    }


    private AArch64Instruction createSafeMemoryInstruction(AArch64Reg baseReg, long offset, AArch64Reg valueReg, 
                                           boolean isLoad, ArrayList<AArch64Instruction> insList, boolean predefine) {
        AArch64Operand offsetOp = createSafeMemoryOperand(offset, insList, predefine);
        
        if (offsetOp instanceof AArch64Reg) {
            AArch64VirReg addrReg = new AArch64VirReg(false);
            ArrayList<AArch64Operand> addOperands = new ArrayList<>();
            addOperands.add(baseReg);
            addOperands.add(offsetOp);
            AArch64Binary addInst = new AArch64Binary(addOperands, addrReg, AArch64Binary.AArch64BinaryType.add);
            addInst.setUse32BitMode(false); // 地址计算使用64位寄存器
            addInstr(addInst, insList, predefine);
            
            if (isLoad) {
                AArch64Load loadInst = new AArch64Load(addrReg, new AArch64Imm(0), valueReg);
                addInstr(loadInst, insList, predefine);
                return loadInst;
            } else {
                AArch64Store storeInst = new AArch64Store(valueReg, addrReg, new AArch64Imm(0));
                addInstr(storeInst, insList, predefine);
                return storeInst;
            }
        } else {
            if (isLoad) {
                AArch64Load loadInst = new AArch64Load(baseReg, (AArch64Imm)offsetOp, valueReg);
                addInstr(loadInst, insList, predefine);
                return loadInst;
            } else {
                AArch64Store storeInst = new AArch64Store(valueReg, baseReg, (AArch64Imm)offsetOp);
                addInstr(storeInst, insList, predefine);
                return storeInst;
            }
        }
    }

    private AArch64Instruction handleLargeStackOffset(AArch64Reg baseReg, long offset, AArch64Reg valueReg, 
                                      boolean isLoad, boolean isFloat, 
                                      ArrayList<AArch64Instruction> insList, boolean predefine) {
        return createSafeMemoryInstruction(baseReg, offset, valueReg, isLoad, insList, predefine);
    }

    private boolean isAddSubImmEncodable(long value) {
        if (value < 0) return false;
        return (value <= 4095) || ((value & 0xFFF) == 0 && (value >> 12) <= 4095);
    }
} 
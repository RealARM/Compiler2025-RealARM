package MiddleEnd.IR;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class IRBuilder {
    private static int tmpCounter = 0;
    private static int blockCounter = 0;
    
    public static Module createModule(String name) {
        return new Module(name);
    }
    
    public static Function createFunction(String name, Type returnType, Module module) {
        Function function = new Function(name, returnType);
        module.addFunction(function);
        return function;
    }
    
    public static Function createExternalFunction(String name, Type returnType, Module module) {
        Function function = new Function(name, returnType);
        function.setExternal(true);
        module.addLibFunction(function);
        return function;
    }
    
    public static Argument createArgument(String name, Type type, Function function, int index) {
        Argument arg = new Argument(name, type, function, index);
        function.addArgument(arg);
        return arg;
    }
    
    public static GlobalVariable createGlobalVariable(String name, Type type, Module module) {
        GlobalVariable var = new GlobalVariable(name, type, false);
        module.addGlobalVariable(var);
        return var;
    }
    
    public static GlobalVariable createGlobalConstant(String name, Type type, Value initializer, Module module) {
        GlobalVariable var = new GlobalVariable(name, type, true);
        var.setInitializer(initializer);
        module.addGlobalVariable(var);
        return var;
    }
    
    public static GlobalVariable createGlobalArray(String name, Type elementType, int size, Module module) {
        GlobalVariable var = new GlobalVariable(name, elementType, size, false);
        module.addGlobalVariable(var);
        return var;
    }
    
    public static BasicBlock createBasicBlock(Function function) {
        return new BasicBlock("block" + blockCounter++, function);
    }
    
    public static BasicBlock createBasicBlock(String name, Function function) {
        return new BasicBlock(name, function);
    }
    
    public static ConstantInt createConstantInt(int value) {
        return new ConstantInt(value);
    }
    
    public static ConstantFloat createConstantFloat(double value) {
        return new ConstantFloat(value);
    }
    
    public static LoadInstruction createLoad(Value pointer, BasicBlock block) {
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("加载指令必须从指针类型加载");
        }
        
        Type pointedType = ((PointerType) pointer.getType()).getElementType();
        String name = "load_" + tmpCounter++;
        
        LoadInstruction inst = new LoadInstruction(pointer, pointedType, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    public static AllocaInstruction createAlloca(Type type, BasicBlock block) {
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
    
    public static AllocaInstruction createArrayAlloca(Type elementType, int size, BasicBlock block) {
        String name = "array_alloca_" + tmpCounter++;
        AllocaInstruction inst = new AllocaInstruction(elementType, size, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    public static StoreInstruction createStore(Value value, Value pointer, BasicBlock block) {
        if (pointer.getType() instanceof PointerType) {
            Type elementType = ((PointerType) pointer.getType()).getElementType();
            Type valueType = value.getType();
            
            if (elementType instanceof FloatType && valueType instanceof IntegerType) {
                if (value instanceof ConstantInt constInt) {
                    value = createConstantFloat((double) constInt.getValue());
                } else {
                    value = createIntToFloat(value, block);
                }
            }
            else if (elementType instanceof IntegerType && valueType instanceof FloatType) {
                if (value instanceof ConstantFloat constFloat) {
                    value = createConstantInt((int) constFloat.getValue());
                } else {
                    value = createFloatToInt(value, block);
                }
            }
        }
        
        StoreInstruction inst = new StoreInstruction(value, pointer);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    public static BinaryInstruction createBinaryInstOnly(OpCode opCode, Value left, Value right) {
        String name = opCode.getName().toLowerCase() + "_result_" + tmpCounter++;
        
        boolean isFloatOp = opCode == OpCode.FADD || opCode == OpCode.FSUB || 
                          opCode == OpCode.FMUL || opCode == OpCode.FDIV || opCode == OpCode.FREM;
        boolean isIntOp = opCode == OpCode.ADD || opCode == OpCode.SUB || 
                        opCode == OpCode.MUL || opCode == OpCode.DIV || opCode == OpCode.REM;
        
        if (isFloatOp && left.getType() instanceof IntegerType && right.getType() instanceof IntegerType) {
            switch (opCode) {
                case FADD: opCode = OpCode.ADD; break;
                case FSUB: opCode = OpCode.SUB; break;
                case FMUL: opCode = OpCode.MUL; break;
                case FDIV: opCode = OpCode.DIV; break;
                case FREM: opCode = OpCode.REM; break;
            }
        }
        
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

    private static OpCode getFloatOpCode(OpCode opCode) {
        return switch (opCode) {
            case ADD -> OpCode.FADD;
            case SUB -> OpCode.FSUB;
            case MUL -> OpCode.FMUL;
            case DIV -> OpCode.FDIV;
            case REM -> OpCode.FREM;
            default -> opCode;
        };
    }

    public static BinaryInstruction createBinaryInst(OpCode opCode, Value left, Value right, BasicBlock block) {
        Value promotedLeft = left;
        Value promotedRight = right;

        // 自动类型提升
        if (left.getType().isFloatType() || right.getType().isFloatType()) {
            opCode = getFloatOpCode(opCode);
            promotedLeft = promoteToFloat(left, block);
            promotedRight = promoteToFloat(right, block);
        }

        Type resultType = promotedLeft.getType();
        
        if (opCode.getName().startsWith("f")) {
            resultType = FloatType.F64;
        }
        
        BinaryInstruction inst = new BinaryInstruction(opCode, promotedLeft, promotedRight, resultType);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    public static CompareInstruction createCompare(OpCode compareType, OpCode predicate, Value left, Value right, BasicBlock block) {
        Type leftType = left.getType();
        Type rightType = right.getType();
        
        if (!leftType.equals(rightType)) {
            if (leftType instanceof FloatType && rightType instanceof IntegerType) {
                right = createIntToFloat(right, block);
                compareType = OpCode.FCMP;
            } 
            else if (rightType instanceof FloatType && leftType instanceof IntegerType) {
                left = createIntToFloat(left, block);
                compareType = OpCode.FCMP;
            }
        } else {
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
    
    public static CompareInstruction createICmp(OpCode predicate, Value left, Value right, BasicBlock block) {
        return createCompare(OpCode.ICMP, predicate, left, right, block);
    }
    
    public static CompareInstruction createFCmp(OpCode predicate, Value left, Value right, BasicBlock block) {
        return createCompare(OpCode.FCMP, predicate, left, right, block);
    }
    
    public static ReturnInstruction createReturn(BasicBlock block) {
        ReturnInstruction inst = new ReturnInstruction();
        block.addInstruction(inst);
        return inst;
    }
    
    public static ReturnInstruction createReturn(Value value, BasicBlock block) {
        ReturnInstruction inst = new ReturnInstruction(value);
        block.addInstruction(inst);
        return inst;
    }
    
    public static BranchInstruction createBr(BasicBlock target, BasicBlock block) {
        BranchInstruction inst = new BranchInstruction(target);
        block.addInstruction(inst);
        
        block.addSuccessor(target);
        
        target.addPredecessor(block);
        
        return inst;
    }
    
    public static BranchInstruction createCondBr(Value condition, BasicBlock trueBlock, BasicBlock falseBlock, BasicBlock block) {
        if (condition.getType() != IntegerType.I1) {
            condition = createICmp(OpCode.NE, condition, createConstantInt(0), block);
        }
        
        BranchInstruction inst = new BranchInstruction(condition, trueBlock, falseBlock);
        block.addInstruction(inst);
        
        block.addSuccessor(trueBlock);
        block.addSuccessor(falseBlock);
        
        trueBlock.addPredecessor(block);
        falseBlock.addPredecessor(block);
        
        return inst;
    }
    
    public static CallInstruction createCall(Function callee, List<Value> arguments, BasicBlock block) {
        CallInstruction inst;
        
        if (callee.getReturnType() instanceof VoidType) {
            inst = new CallInstruction(callee, arguments, null);
        } else {
            String name = "call_" + tmpCounter++;
            inst = new CallInstruction(callee, arguments, name);
        }
        
        if (block != null) {
            block.addInstruction(inst);
            
            if (!callee.isExternal()) {
                Function caller = block.getParentFunction();
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
    
    public static PhiInstruction createPhi(Type type, BasicBlock block) {
        String name = "phi_" + tmpCounter++;
        PhiInstruction inst = new PhiInstruction(type, name);
        if (block != null) {
            block.addInstructionFirst(inst);
            
            List<BasicBlock> predecessors = block.getPredecessors();
            if (!predecessors.isEmpty()) {
                updatePhiNodePredecessors(inst, block);
            }
        }
        return inst;
    }
    
    public static void validateAllPhiNodes(Function function) {
        if (function == null) return;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : new ArrayList<>(block.getInstructions())) {
                if (inst instanceof PhiInstruction phi) {
                    phi.validatePredecessors();
                }
            }
        }
        
        forceFixPhiNodes(function);
    }
    
    public static void forceFixPhiNodes(Function function) {
        if (function == null) return;
        
        Map<BasicBlock, List<BasicBlock>> actualPredecessors = new HashMap<>();
        
        for (BasicBlock block : function.getBasicBlocks()) {
            actualPredecessors.put(block, new ArrayList<>());
        }
        
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
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<BasicBlock> realPreds = actualPredecessors.get(block);
            
            for (Instruction inst : new ArrayList<>(block.getInstructions())) {
                if (inst instanceof PhiInstruction phi) {
                    Map<BasicBlock, Value> validIncomings = new HashMap<>();
                    
                    for (BasicBlock pred : realPreds) {
                        if (phi.getIncomingValues().containsKey(pred)) {
                            validIncomings.put(pred, phi.getIncomingValues().get(pred));
                        } else {
                            Value defaultValue = phi.getType().toString().equals("i1") ?
                                new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                            validIncomings.put(pred, defaultValue);
                        }
                    }
                    
                    phi.removeAllOperands();
                    phi.getIncomingValues().clear();
                    for (Map.Entry<BasicBlock, Value> entry : validIncomings.entrySet()) {
                        phi.addIncoming(entry.getValue(), entry.getKey());
                    }
                }
            }
        }
    }
    
    public static void updatePhiNodePredecessors(PhiInstruction phi, BasicBlock block) {
        List<BasicBlock> predecessors = block.getPredecessors();
        
        if (phi.getIncomingBlocks().isEmpty() && !predecessors.isEmpty()) {
            for (BasicBlock pred : predecessors) {
                Value defaultValue = phi.getType() == IntegerType.I1 ? 
                    new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                phi.addIncoming(defaultValue, pred);
            }
        }
    }
    
    public static void updateAllPhiNodes(BasicBlock block) {
        if (block == null) return;
        
        for (Instruction inst : block.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                updatePhiNodePredecessors(phi, block);
            }
        }
    }
    
    public static GetElementPtrInstruction createGetElementPtr(Value pointer, Value index, BasicBlock block) {
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
    
    public static GetElementPtrInstruction createGetElementPtr(Value pointer, List<Value> indices, BasicBlock block) {
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
    
    public static ConversionInstruction createConversion(Value value, Type targetType, OpCode conversionType, BasicBlock block) {
        String name = conversionType.getName().toLowerCase() + "_" + tmpCounter++;
        ConversionInstruction inst = new ConversionInstruction(value, targetType, conversionType, name);
        if (block != null) {
            block.addInstruction(inst);
        }
        return inst;
    }
    
    public static ConversionInstruction createIntToFloat(Value value, BasicBlock block) {
        return createConversion(value, FloatType.F64, OpCode.SITOFP, block);
    }
    
    public static ConversionInstruction createFloatToInt(Value value, BasicBlock block) {
        return createConversion(value, IntegerType.I32, OpCode.FPTOSI, block);
    }
    
    public static ConversionInstruction createZeroExtend(Value value, Type targetType, BasicBlock block) {
        return createConversion(value, targetType, OpCode.ZEXT, block);
    }
    
    public static ConversionInstruction createTrunc(Value value, Type targetType, BasicBlock block) {
        return createConversion(value, targetType, OpCode.TRUNC, block);
    }
    
    public static ConversionInstruction createBitCast(Value value, Type targetType, BasicBlock block) {
        return createConversion(value, targetType, OpCode.BITCAST, block);
    }
    
    public static List<Value> createOperandList(Value... operands) {
        List<Value> list = new ArrayList<>();
        for (Value op : operands) {
            list.add(op);
        }
        return list;
    }
    
    public static Value calculateConstantExpr(Value left, Value right, OpCode op) {
        if (left.getType().isFloatType() || right.getType().isFloatType()) {
            if (left instanceof ConstantFloat leftFloat && right instanceof ConstantFloat rightFloat) {
                double leftVal = leftFloat.getValue();
                double rightVal = rightFloat.getValue();
                return switch (op) {
                    case FADD -> createConstantFloat(leftVal + rightVal);
                    case FSUB -> createConstantFloat(leftVal - rightVal);
                    case FMUL -> createConstantFloat(leftVal * rightVal);
                    case FDIV -> createConstantFloat(leftVal / rightVal);
                    case FREM -> createConstantFloat(leftVal % rightVal);
                    default -> null;
                };
            }
            if (left instanceof ConstantInt leftInt && right instanceof ConstantFloat rightFloat) {
                double leftVal = leftInt.getValue();
                double rightVal = rightFloat.getValue();
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
                double leftVal = leftFloat.getValue();
                double rightVal = rightInt.getValue();
                return switch (op) {
                    case FADD -> createConstantFloat(leftVal + rightVal);
                    case FSUB -> createConstantFloat(leftVal - rightVal);
                    case FMUL -> createConstantFloat(leftVal * rightVal);
                    case FDIV -> createConstantFloat(leftVal / rightVal);
                    case FREM -> createConstantFloat(leftVal % rightVal);
                    default -> null;
                };
            }
        }
        
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
        
        return null;
    }

    private static Value promoteToFloat(Value value, BasicBlock block) {
        Type type = value.getType();
        if (type.isFloatType()) {
            return value;
        }

        if (value instanceof ConstantInt constInt) {
            return createConstantFloat((double) constInt.getValue());
        } else if (value.getType().isIntegerType()) {
            return createIntToFloat(value, block);
        }
        return value;
    }

    public static MoveInstruction createMove(String targetName, Type targetType, Value source) {
        return new MoveInstruction(targetName, targetType, source);
    }

    public static void resetBlockCounter() {
        blockCounter = 0;
    }
} 
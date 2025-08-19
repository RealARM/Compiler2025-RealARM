package MiddleEnd.Optimization.Constant;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 常量折叠
 */
public class ConstantFolding implements Optimizer.ModuleOptimizer {
    @Override
    public String getName() {
        return "ConstantFolding";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            for (BasicBlock bb : function.getBasicBlocks()) {
                changed |= processBasicBlock(bb);
            }
        }
        
        return changed;
    }
    
    private boolean processBasicBlock(BasicBlock bb) {
        boolean changed = false;
        List<Instruction> toProcess = new ArrayList<>(bb.getInstructions());
        
        for (Instruction inst : toProcess) {
            if (inst instanceof BinaryInstruction) {
                changed |= foldBinaryInstruction((BinaryInstruction) inst);
            }
            else if (inst instanceof CompareInstruction) {
                changed |= foldCompareInstruction((CompareInstruction) inst);
            }
        }
        
        return changed;
    }
    
    private boolean foldBinaryInstruction(BinaryInstruction inst) {
        Value left = inst.getOperand(0);
        Value right = inst.getOperand(1);
        
        if (!(left instanceof Constant && right instanceof Constant)) {
            return false;
        }
        
        Constant result = null;
        OpCode opCode = inst.getOpCode();
        
        if (left instanceof ConstantInt && right instanceof ConstantInt) {
            int leftVal = ((ConstantInt) left).getValue();
            int rightVal = ((ConstantInt) right).getValue();
            
            switch (opCode) {
                case ADD:
                    result = new ConstantInt(leftVal + rightVal);
                    break;
                case SUB:
                    result = new ConstantInt(leftVal - rightVal);
                    break;
                case MUL:
                    result = new ConstantInt(leftVal * rightVal);
                    break;
                case DIV:
                    if (rightVal != 0) {
                        result = new ConstantInt(leftVal / rightVal);
                    }
                    break;
                case REM:
                    if (rightVal != 0) {
                        result = new ConstantInt(leftVal % rightVal);
                    }
                    break;
                case EQ:
                    result = new ConstantInt(leftVal == rightVal ? 1 : 0);
                    break;
                case NE:
                    result = new ConstantInt(leftVal != rightVal ? 1 : 0);
                    break;
                case SLT:
                    result = new ConstantInt(leftVal < rightVal ? 1 : 0);
                    break;
                case SLE:
                    result = new ConstantInt(leftVal <= rightVal ? 1 : 0);
                    break;
                case SGT:
                    result = new ConstantInt(leftVal > rightVal ? 1 : 0);
                    break;
                case SGE:
                    result = new ConstantInt(leftVal >= rightVal ? 1 : 0);
                    break;
                case AND:
                    result = new ConstantInt(leftVal & rightVal);
                    break;
                case OR:
                    result = new ConstantInt(leftVal | rightVal);
                    break;
                case XOR:
                    result = new ConstantInt(leftVal ^ rightVal);
                    break;
                case SHL:
                    result = new ConstantInt(leftVal << rightVal);
                    break;
                case LSHR:
                case ASHR:
                    result = new ConstantInt(leftVal >> rightVal);
                    break;
            }
        }           
        else if (inst.isFloatingPointOp()) {
            // if (left instanceof ConstantFloat && right instanceof ConstantFloat) {
            //     double leftVal = ((ConstantFloat) left).getValue();
            //     double rightVal = ((ConstantFloat) right).getValue();
            //     
            //     switch (inst.getOpCode()) {
            //         case FADD:
            //             result = new ConstantFloat(leftVal + rightVal);
            //             break;
            //         case FSUB:
            //             result = new ConstantFloat(leftVal - rightVal);
            //             break;
            //         case FMUL:
            //             result = new ConstantFloat(leftVal * rightVal);
            //             break;
            //         case FDIV:
            //             if (rightVal != 0.0) {
            //                 result = new ConstantFloat(leftVal / rightVal);
            //             }
            //             break;
            //         case FREM:
            //             if (rightVal != 0.0) {
            //                 result = new ConstantFloat(leftVal % rightVal);
            //             }
            //             break;
            //     }
            // }
        }
        
        if (result != null) {
            for (Value user : new ArrayList<>(inst.getUsers())) {
                if (user instanceof Instruction userInst) {
                    for (int i = 0; i < userInst.getOperandCount(); i++) {
                        if (userInst.getOperand(i) == inst) {
                            userInst.setOperand(i, result);
                        }
                    }
                }
            }
            
            inst.removeFromParent();
            return true;
        }
        
        return false;
    }
    
    private boolean foldCompareInstruction(CompareInstruction inst) {
        Value left = inst.getLeft();
        Value right = inst.getRight();
        
        if (!(left instanceof Constant && right instanceof Constant)) {
            return false;
        }
        
        Constant result = null;
        OpCode predicate = inst.getPredicate();
        
        if (inst.isIntegerCompare() && left instanceof ConstantInt && right instanceof ConstantInt) {
            int l = ((ConstantInt) left).getValue();
            int r = ((ConstantInt) right).getValue();
            boolean res = false;
            switch (predicate) {
                case EQ:
                    res = (l == r);
                    break;
                case NE:
                    res = (l != r);
                    break;
                case SLT:
                    res = (l < r);
                    break;
                case SLE:
                    res = (l <= r);
                    break;
                case SGT:
                    res = (l > r);
                    break;
                case SGE:
                    res = (l >= r);
                    break;
                default:
                    return false;
            }
            result = ConstantInt.getBool(res);
        } else if (inst.isFloatCompare() && left instanceof ConstantFloat && right instanceof ConstantFloat) {
            double l = ((ConstantFloat) left).getValue();
            double r = ((ConstantFloat) right).getValue();
            boolean res = false;
            switch (predicate) {
                case UEQ:
                    res = (l == r);
                    break;
                case UNE:
                    res = (l != r);
                    break;
                case ULT:
                    res = (l < r);
                    break;
                case ULE:
                    res = (l <= r);
                    break;
                case UGT:
                    res = (l > r);
                    break;
                case UGE:
                    res = (l >= r);
                    break;
                default:
                    return false;
            }
            result = ConstantInt.getBool(res);
        }
        
        if (result == null) {
            return false;
        }
        
        for (Value user : new ArrayList<>(inst.getUsers())) {
            if (user instanceof Instruction userInst) {
                for (int i = 0; i < userInst.getOperandCount(); i++) {
                    if (userInst.getOperand(i) == inst) {
                        userInst.setOperand(i, result);
                    }
                }
            }
        }
        
        inst.removeFromParent();
        return true;
    }
} 
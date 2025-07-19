package IR.Pass;

import IR.Module;
import IR.OpCode;
import IR.Type.FloatType;
import IR.Type.IntegerType;
import IR.Value.*;
import IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 常量折叠优化Pass
 * 将常量表达式在编译时计算，减少运行时计算
 */
public class ConstantFolding implements Pass.IRPass {
    @Override
    public String getName() {
        return "ConstantFolding";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        // 对模块中的每个函数进行常量折叠
        for (Function function : module.functions()) {
            // 跳过外部函数
            if (function.isExternal()) {
                continue;
            }
            
            // 处理函数中的每个基本块
            for (BasicBlock bb : function.getBasicBlocks()) {
                changed |= processBasicBlock(bb);
            }
        }
        
        return changed;
    }
    
    /**
     * 处理基本块中的常量折叠
     */
    private boolean processBasicBlock(BasicBlock bb) {
        boolean changed = false;
        List<Instruction> toProcess = new ArrayList<>(bb.getInstructions());
        
        for (Instruction inst : toProcess) {
            // 处理二元运算指令
            if (inst instanceof BinaryInstruction) {
                changed |= foldBinaryInstruction((BinaryInstruction) inst);
            }
            // 可以添加其他类型指令的常量折叠
        }
        
        return changed;
    }
    
    /**
     * 对二元运算指令进行常量折叠
     */
    private boolean foldBinaryInstruction(BinaryInstruction inst) {
        Value left = inst.getOperand(0);
        Value right = inst.getOperand(1);
        
        // 只有当两个操作数都是常量时才能进行折叠
        if (!(left instanceof Constant && right instanceof Constant)) {
            return false;
        }
        
        Constant result = null;
        OpCode opCode = inst.getOpCode();
        
        // 整数运算
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
                // 比较操作
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
                // 位操作
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
        // 浮点运算
        else if (inst.isFloatingPointOp()) {
            if (left instanceof ConstantFloat && right instanceof ConstantFloat) {
                double leftVal = ((ConstantFloat) left).getValue();
                double rightVal = ((ConstantFloat) right).getValue();
                
                switch (inst.getOpCode()) {
                    case FADD:
                        result = new ConstantFloat(leftVal + rightVal);
                        break;
                    case FSUB:
                        result = new ConstantFloat(leftVal - rightVal);
                        break;
                    case FMUL:
                        result = new ConstantFloat(leftVal * rightVal);
                        break;
                    case FDIV:
                        if (rightVal != 0.0) {
                            result = new ConstantFloat(leftVal / rightVal);
                        }
                        break;
                    case FREM:
                        if (rightVal != 0.0) {
                            result = new ConstantFloat(leftVal % rightVal);
                        }
                        break;
                }
            }
        }
        
        // 如果成功折叠，替换指令
        if (result != null) {
            inst.replaceAllUsesWith(result);
            inst.removeFromParent();
            return true;
        }
        
        return false;
    }
} 
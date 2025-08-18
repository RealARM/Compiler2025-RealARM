package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.VoidType;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Value;

public class BranchInstruction extends Instruction implements TerminatorInstruction {
    private final Value condition;
    private final BasicBlock trueBlock;
    private final BasicBlock falseBlock;
    
    public BranchInstruction(BasicBlock target) {
        super("br", VoidType.VOID);
        this.condition = null;
        this.trueBlock = target;
        this.falseBlock = null;
        addOperand(target);
    }
    
    public BranchInstruction(Value condition, BasicBlock trueBlock, BasicBlock falseBlock) {
        super("br", VoidType.VOID);
        this.condition = condition;
        this.trueBlock = trueBlock;
        this.falseBlock = falseBlock;
        
        addOperand(condition);
        addOperand(trueBlock);
        addOperand(falseBlock);
    }
    
    public boolean isUnconditional() {
        return getOperandCount() == 1;
    }
    
    public Value getCondition() {
        return isUnconditional() ? null : getOperand(0);
    }
    
    public BasicBlock getTrueBlock() {
        return isUnconditional() ? (BasicBlock) getOperand(0) : (BasicBlock) getOperand(1);
    }
    
    public BasicBlock getFalseBlock() {
        return isUnconditional() ? null : (BasicBlock) getOperand(2);
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.BR.getName();
    }
    
    @Override
    public String toString() {
        if (isUnconditional()) {
            BasicBlock target = getTrueBlock();
            return "br label %" + target.getName();
        } else {
            Value cond = getCondition();
            BasicBlock t = getTrueBlock();
            BasicBlock f = getFalseBlock();
            return "br i1 " + cond.getName() + ", label %" + t.getName() + ", label %" + f.getName();
        }
    }
    
    @Override
    public BasicBlock[] getSuccessors() {
        if (isUnconditional()) {
            return new BasicBlock[] { getTrueBlock() };
        } else {
            return new BasicBlock[] { getTrueBlock(), getFalseBlock() };
        }
    }
} 
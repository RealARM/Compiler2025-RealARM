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
        return condition == null;
    }
    
    public Value getCondition() {
        return condition;
    }
    
    public BasicBlock getTrueBlock() {
        return trueBlock;
    }
    
    public BasicBlock getFalseBlock() {
        return falseBlock;
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.BR.getName();
    }
    
    @Override
    public String toString() {
        if (isUnconditional()) {
            return "br label %" + trueBlock.getName();
        } else {
            return "br i1 " + condition.getName() + ", label %" + trueBlock.getName() + ", label %" + falseBlock.getName();
        }
    }
    
    @Override
    public BasicBlock[] getSuccessors() {
        if (isUnconditional()) {
            return new BasicBlock[] { trueBlock };
        } else {
            return new BasicBlock[] { trueBlock, falseBlock };
        }
    }
} 
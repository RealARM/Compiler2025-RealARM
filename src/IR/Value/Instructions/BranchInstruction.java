package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.VoidType;
import IR.Value.BasicBlock;
import IR.Value.Value;

/**
 * 分支指令，用于条件或无条件跳转
 */
public class BranchInstruction extends Instruction implements TerminatorInstruction {
    private final Value condition; // 条件表达式，可能为null表示无条件跳转
    private final BasicBlock trueBlock; // 条件为真时跳转的基本块
    private final BasicBlock falseBlock; // 条件为假时跳转的基本块，可能为null
    
    /**
     * 创建一个无条件跳转指令
     */
    public BranchInstruction(BasicBlock target) {
        super("br", VoidType.VOID);
        this.condition = null;
        this.trueBlock = target;
        this.falseBlock = null;
        addOperand(target);
    }
    
    /**
     * 创建一个条件跳转指令
     */
    public BranchInstruction(Value condition, BasicBlock trueBlock, BasicBlock falseBlock) {
        super("br", VoidType.VOID);
        this.condition = condition;
        this.trueBlock = trueBlock;
        this.falseBlock = falseBlock;
        
        addOperand(condition);
        addOperand(trueBlock);
        addOperand(falseBlock);
    }
    
    /**
     * 判断是否为无条件跳转
     */
    public boolean isUnconditional() {
        return condition == null;
    }
    
    /**
     * 获取条件表达式
     */
    public Value getCondition() {
        return condition;
    }
    
    /**
     * 获取条件为真时跳转的基本块
     * 对于无条件跳转，返回目标基本块
     */
    public BasicBlock getTrueBlock() {
        return trueBlock;
    }
    
    /**
     * 获取条件为假时跳转的基本块
     * 对于无条件跳转，返回null
     */
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
package IR.Value.Instructions;

import IR.Type.Type;
import IR.Value.BasicBlock;
import IR.Value.User;

/**
 * 指令基类，所有IR指令都继承自此类
 */
public abstract class Instruction extends User {
    private BasicBlock parent; // 指令所在的基本块
    
    /**
     * 创建一个指令
     */
    public Instruction(String name, Type type) {
        super(name, type);
    }
    
    /**
     * 获取指令所在的基本块
     */
    public BasicBlock getParent() {
        return parent;
    }
    
    /**
     * 设置指令所在的基本块
     */
    public void setParent(BasicBlock parent) {
        this.parent = parent;
    }
    
    /**
     * 从基本块中移除此指令
     */
    public void removeFromParent() {
        if (parent != null) {
            parent.removeInstruction(this);
        }
    }
    
    /**
     * 在指定指令之前插入此指令
     */
    public void insertBefore(Instruction before) {
        if (before != null && before.getParent() != null) {
            before.getParent().addInstructionBefore(this, before);
        }
    }
    
    /**
     * 在指定指令之后插入此指令
     */
    public void insertAfter(Instruction after) {
        if (after != null && after.getParent() != null) {
            BasicBlock block = after.getParent();
            int index = block.getInstructions().indexOf(after);
            if (index != -1 && index < block.getInstructions().size() - 1) {
                block.addInstructionBefore(this, block.getInstructions().get(index + 1));
            } else {
                block.addInstruction(this);
            }
        }
    }
    
    /**
     * 判断指令是否为终止指令（return, br等）
     */
    public boolean isTerminator() {
        return this instanceof TerminatorInstruction;
    }
    
    /**
     * 获取指令的操作码名称
     */
    public abstract String getOpcodeName();
    
    /**
     * 获取指令的字符串表示
     */
    @Override
    public abstract String toString();
} 
package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.User;

public abstract class Instruction extends User {
    private BasicBlock parent;
    
    public Instruction(String name, Type type) {
        super(name, type);
    }
    
    public BasicBlock getParent() {
        return parent;
    }
    
    public void setParent(BasicBlock parent) {
        this.parent = parent;
    }
    
    public void removeFromParent() {
        if (parent != null) {
            parent.removeInstruction(this);
        }
    }
    
    public void insertBefore(Instruction before) {
        if (before != null && before.getParent() != null) {
            before.getParent().addInstructionBefore(this, before);
        }
    }
    
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
    
    public boolean isTerminator() {
        return this instanceof TerminatorInstruction;
    }
        
    public abstract String getOpcodeName();
    
    @Override
    public abstract String toString();
} 
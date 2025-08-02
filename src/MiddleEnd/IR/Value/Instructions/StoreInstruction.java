package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.PointerType;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Type.VoidType;
import MiddleEnd.IR.Value.Value;

public class StoreInstruction extends Instruction {
    public StoreInstruction(Value value, Value pointer) {
        super("store", VoidType.VOID);
        
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("存储指令必须存储到指针类型");
        }
        
        addOperand(value);
        addOperand(pointer);
    }
    
    public Value getValue() {
        return getOperand(0);
    }
    
    public Value getPointer() {
        return getOperand(1);
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.STORE.getName();
    }
    
    @Override
    public String toString() {
        return getOpcodeName() + " " + getValue().getType() + " " + getValue().getName() + ", " + 
               getPointer().getType() + " " + getPointer().getName();
    }
} 
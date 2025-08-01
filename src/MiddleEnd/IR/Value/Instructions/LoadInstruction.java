package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.PointerType;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;

public class LoadInstruction extends Instruction {
    public LoadInstruction(Value pointer, String name) {
        super(name, ((PointerType) pointer.getType()).getElementType());
        
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("加载指令必须从指针类型加载");
        }
        
        addOperand(pointer);
    }
    
    public LoadInstruction(Value pointer, Type pointedType, String name) {
        super(name, pointedType);
        
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("加载指令必须从指针类型加载");
        }
        
        addOperand(pointer);
    }
    
    public Value getPointer() {
        return getOperand(0);
    }
    
    public Type getLoadedType() {
        return getType();
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.LOAD.getName();
    }
    
    @Override
    public String toString() {
        return getName() + " = " + getOpcodeName() + " " + getType() + ", " + 
               getPointer().getType() + " " + getPointer().getName();
    }
} 
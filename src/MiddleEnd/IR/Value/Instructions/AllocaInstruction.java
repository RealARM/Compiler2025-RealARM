package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.PointerType;
import MiddleEnd.IR.Type.Type;

public class AllocaInstruction extends Instruction {
    private final Type allocatedType; 
    private final int arraySize;      
    
    public AllocaInstruction(Type elementType, String name) {
        super(name, new PointerType(elementType));
        this.allocatedType = elementType;
        this.arraySize = 0;
    }
    
    public AllocaInstruction(Type elementType, int arraySize, String name) {
        super(name, new PointerType(elementType));
        this.allocatedType = elementType;
        this.arraySize = arraySize;
    }
    
    public Type getAllocatedType() {
        return allocatedType;
    }
    
    public int getArraySize() {
        return arraySize;
    }
    
    /**
     * 判断是否为数组分配
     */
    public boolean isArrayAllocation() {
        return arraySize > 0;
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.ALLOCA.getName();
    }
    
    @Override
    public String toString() {
        if (isArrayAllocation()) {
            return getName() + " = " + getOpcodeName() + " [" + arraySize + " x " + allocatedType + "]";
        } else {
            return getName() + " = " + getOpcodeName() + " " + allocatedType;
        }
    }
} 
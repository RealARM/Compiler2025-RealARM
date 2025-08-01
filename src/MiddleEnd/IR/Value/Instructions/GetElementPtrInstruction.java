package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.PointerType;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;

import java.util.ArrayList;
import java.util.List;

public class GetElementPtrInstruction extends Instruction {
    public GetElementPtrInstruction(Value pointer, Value offset, String name) {
        super(name, pointer.getType());
        
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("GetElementPtr的第一个参数必须是指针类型");
        }
        
        addOperand(pointer);
        addOperand(offset);
    }
    
    public GetElementPtrInstruction(Value pointer, List<Value> indices, String name) {
        super(name, pointer.getType());
        
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("GetElementPtr的第一个参数必须是指针类型");
        }
        
        addOperand(pointer);
        
        for (Value index : indices) {
            addOperand(index);
        }
    }
    
    public Value getPointer() {
        return getOperand(0);
    }
    
    public Value getOffset() {
        return getOperand(1);
    }
    
    public List<Value> getIndices() {
        List<Value> indices = new ArrayList<>();
        for (int i = 1; i < getOperandCount(); i++) {
            indices.add(getOperand(i));
        }
        return indices;
    }
    
    public Type getElementType() {
        return ((PointerType) getPointer().getType()).getElementType();
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.GETELEMENTPTR.getName();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = ").append(getOpcodeName()).append(" ");
        sb.append(getElementType()).append(", ");
        sb.append(getPointer().getType()).append(" ").append(getPointer().getName());
        
        for (Value index : getIndices()) {
            sb.append(", ").append(index.getType()).append(" ").append(index.getName());
        }
        
        return sb.toString();
    }
} 
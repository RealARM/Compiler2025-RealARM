package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;

public class MoveInstruction extends Instruction {
    private static int nameCounter = 0;
    
    public MoveInstruction(String targetName, Type targetType, Value source) {
        super(targetName, targetType);
        
        addOperand(source);
    }
    
    public MoveInstruction(PhiInstruction phiInstruction, Value source) {
        this(phiInstruction.getName(), phiInstruction.getType(), source);
    }
    
    public String getDestinationName() {
        return getName();
    }
    
    public Value getSource() {
        return getOperand(0);
    }
    
    public void replaceOperand(Value oldValue, Value newValue) {
        for (int i = 0; i < getOperands().size(); i++) {
            if (getOperands().get(i) == oldValue) {
                getOperands().set(i, newValue);
                break;
            }
        }
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.MOV.getName();
    }
    
    @Override
    public String toString() {
        return getDestinationName() + " = mov " + getType() + " " + 
               getSource().getName();
    }
    
    public boolean isSelfAssignment() {
        return getDestinationName().equals(getSource().getName());
    }
} 
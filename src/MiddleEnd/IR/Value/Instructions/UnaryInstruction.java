package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Value.Value;

public class UnaryInstruction extends Instruction {
    private final OpCode opCode;
    
    public UnaryInstruction(OpCode opCode, Value operand, String name) {
        super(name, operand.getType());
        this.opCode = opCode;
        addOperand(operand);
    }
    
    public Value getOperand() {
        return getOperand(0);
    }
    
    public OpCode getOpCode() {
        return opCode;
    }
    
    @Override
    public String getOpcodeName() {
        return opCode.getName();
    }
    
    @Override
    public String toString() {
        return getName() + " = " + opCode.getName() + " " + getType() + " " + getOperand();
    }
} 
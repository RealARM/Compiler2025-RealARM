package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;

public class BinaryInstruction extends Instruction {
    private final OpCode opCode;
    
    private static int nameCounter = 0;
    
    public BinaryInstruction(OpCode opCode, Value left, Value right, Type resultType) {
        super(getInstructionName(opCode), resultType);
        this.opCode = opCode;
        
        addOperand(left);
        addOperand(right);
    }
    
    public OpCode getOpCode() {
        return opCode;
    }
    
    public Value getLeft() {
        return getOperand(0);
    }
    
    public Value getRight() {
        return getOperand(1);
    }
    
    public boolean isArithmeticOp() {
        return opCode == OpCode.ADD || opCode == OpCode.SUB || opCode == OpCode.MUL || 
               opCode == OpCode.DIV || opCode == OpCode.REM || opCode == OpCode.FADD || 
               opCode == OpCode.FSUB || opCode == OpCode.FMUL || opCode == OpCode.FDIV || 
               opCode == OpCode.FREM;
    }
    
    public boolean isBitwiseOp() {
        return opCode == OpCode.AND || opCode == OpCode.OR || opCode == OpCode.XOR ||
               opCode == OpCode.SHL || opCode == OpCode.LSHR || opCode == OpCode.ASHR;
    }
    
    public boolean isFloatingPointOp() {
        return opCode == OpCode.FADD || opCode == OpCode.FSUB || opCode == OpCode.FMUL || 
               opCode == OpCode.FDIV || opCode == OpCode.FREM;
    }
    
    @Override
    public String getOpcodeName() {
        return opCode.getName();
    }
    
    @Override
    public String toString() {
        return getName() + " = " + getOpcodeName() + " " + 
               getType() + " " + getLeft().getName() + ", " + getRight().getName();
    }
    
    private static String getInstructionName(OpCode opCode) {
        return opCode.getName() + "_result_" + nameCounter++;
    }
} 
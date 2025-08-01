package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.User;
import MiddleEnd.IR.Value.Value;

/**
 * 一元操作指令
 */
public class UnaryInstruction extends Instruction {
    private final OpCode opCode; // 操作码
    
    /**
     * 创建一元操作指令
     */
    public UnaryInstruction(OpCode opCode, Value operand, String name) {
        super(name, operand.getType());
        this.opCode = opCode;
        addOperand(operand);
    }
    
    /**
     * 获取操作数
     */
    public Value getOperand() {
        return getOperand(0);
    }
    
    /**
     * 获取操作码
     */
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
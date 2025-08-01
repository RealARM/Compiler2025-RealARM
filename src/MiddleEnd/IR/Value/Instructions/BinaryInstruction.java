package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;

/**
 * 二元操作指令，如加、减、乘、除等
 */
public class BinaryInstruction extends Instruction {
    private final OpCode opCode; // 操作码，如add, sub, mul等
    
    // 为不同的指令名称添加计数器
    private static int nameCounter = 0;
    
    /**
     * 创建一个二元操作指令
     */
    public BinaryInstruction(OpCode opCode, Value left, Value right, Type resultType) {
        super(getInstructionName(opCode), resultType);
        this.opCode = opCode;
        
        // 添加操作数
        addOperand(left);
        addOperand(right);
    }
    
    /**
     * 获取操作码
     */
    public OpCode getOpCode() {
        return opCode;
    }
    
    /**
     * 获取左操作数
     */
    public Value getLeft() {
        return getOperand(0);
    }
    
    /**
     * 获取右操作数
     */
    public Value getRight() {
        return getOperand(1);
    }
    
    /**
     * 判断是否为算术指令（加减乘除余）
     */
    public boolean isArithmeticOp() {
        return opCode == OpCode.ADD || opCode == OpCode.SUB || opCode == OpCode.MUL || 
               opCode == OpCode.DIV || opCode == OpCode.REM || opCode == OpCode.FADD || 
               opCode == OpCode.FSUB || opCode == OpCode.FMUL || opCode == OpCode.FDIV || 
               opCode == OpCode.FREM;
    }
    
    /**
     * 判断是否为位运算指令
     */
    public boolean isBitwiseOp() {
        return opCode == OpCode.AND || opCode == OpCode.OR || opCode == OpCode.XOR ||
               opCode == OpCode.SHL || opCode == OpCode.LSHR || opCode == OpCode.ASHR;
    }
    
    /**
     * 判断是否为浮点操作
     */
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
    
    /**
     * 生成指令名称
     */
    private static String getInstructionName(OpCode opCode) {
        return opCode.getName() + "_result_" + nameCounter++;
    }
} 
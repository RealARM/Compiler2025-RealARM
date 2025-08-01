package Backend.Value.Instruction.Arithmetic;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Base.Armv8Operand;
import Backend.Value.Operand.Register.Armv8FPUReg;
import Backend.Value.Operand.Register.Armv8Reg;

import java.util.ArrayList;

/**
 * 浮点二元操作指令类
 */
public class Armv8FBinary extends Armv8Instruction {
    
    /**
     * 浮点二元操作类型
     */
    public enum Armv8FBinaryType {
        fadd, // 浮点加法
        fsub, // 浮点减法
        fmul, // 浮点乘法
        fdiv, // 浮点除法
        fnmul // 浮点负乘
    }
    
    private Armv8FBinaryType type;
    
    /**
     * 创建浮点二元操作指令
     * 
     * @param operands 操作数列表，需要包含两个操作数
     * @param dest 目标寄存器
     * @param type 操作类型
     */
    public Armv8FBinary(ArrayList<Armv8Operand> operands, Armv8Reg dest, Armv8FBinaryType type) {
        super(dest, operands);
        this.type = type;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name());
        sb.append("\t").append(getDefReg()).append(", ");
        
        ArrayList<Armv8Operand> operandList = getOperands();
        if (operandList.size() >= 2) {
            sb.append(operandList.get(0)).append(", ").append(operandList.get(1));
        }
        
        return sb.toString();
    }
} 
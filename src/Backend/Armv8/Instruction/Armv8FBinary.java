package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8FPUReg;
import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

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
    private boolean is32Bit;
    
    /**
     * 创建浮点二元操作指令
     * 
     * @param operands 操作数列表，需要包含两个操作数
     * @param dest 目标寄存器
     * @param type 操作类型
     * @param is32Bit 是否为32位操作（单精度浮点），否则为64位（双精度浮点）
     */
    public Armv8FBinary(ArrayList<Armv8Operand> operands, Armv8FPUReg dest, Armv8FBinaryType type, boolean is32Bit) {
        super(dest, operands);
        this.type = type;
        this.is32Bit = is32Bit;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name());
        
        // 添加精度后缀
        if (is32Bit) {
            sb.append("s");  // 单精度
        } else {
            sb.append("d");  // 双精度
        }
        
        // 添加操作数
        sb.append("\t").append(getDefReg()).append(", ");
        
        ArrayList<Armv8Operand> operandList = getOperands();
        if (operandList.size() >= 2) {
            sb.append(operandList.get(0)).append(", ").append(operandList.get(1));
        }
        
        return sb.toString();
    }
} 
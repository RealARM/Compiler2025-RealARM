package Backend.Armv8.Instruction;
import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Collections;

/**
 * ARMv8 Move instruction
 * Used for moving values between registers or loading immediate values into registers
 * Implemented as either:
 * - mov rd, operand (register or immediate)
 * - movk rd, #imm (for upper 16-bit immediate loads)
 */
public class Armv8Move extends Armv8Instruction {
    private final boolean isImmediate; // 是否是立即数操作数
    private final MoveType moveType; // 移动类型
    private int shift = 0; // 移位值，用于MOVZ/MOVK指令

    /**
     * 构造一个Move指令
     * @param destReg 目标寄存器
     * @param operand 源操作数（寄存器或立即数）
     * @param isImmediate 是否为立即数操作
     */
    public Armv8Move(Armv8Reg destReg, Armv8Operand operand, boolean isImmediate) {
        super(destReg, new ArrayList<>(Collections.singletonList(operand)));
        this.isImmediate = isImmediate;
        this.moveType = MoveType.MOV;
    }

    /**
     * 构造一个特定类型的Move指令
     * @param destReg 目标寄存器
     * @param operand 源操作数（寄存器或立即数）
     * @param isImmediate 是否为立即数操作
     * @param moveType 移动指令类型
     */
    public Armv8Move(Armv8Reg destReg, Armv8Operand operand, boolean isImmediate, MoveType moveType) {
        super(destReg, new ArrayList<>(Collections.singletonList(operand)));
        this.isImmediate = isImmediate;
        this.moveType = moveType;
    }

    /**
     * 设置指令的移位值（用于MOVZ/MOVK指令）
     * @param shift 移位值（0、16、32或48）
     */
    public void setShift(int shift) {
        this.shift = shift;
    }

    /**
     * 获取移位值
     */
    public int getShift() {
        return shift;
    }

    /**
     * Move指令类型枚举
     */
    public enum MoveType {
        MOV,  // 基本移动
        MOVK, // 保持其他位不变，更新指定16位
        MOVZ, // 将其他位清零，更新指定16位
        MOVN  // 对操作数按位取反后移动
    }

    /**
     * 获取移动指令类型的字符串表示
     */
    private String getMoveTypeString() {
        return moveType.toString().toLowerCase();
    }

    /**
     * 是否为立即数操作
     */
    public boolean isImmediate() {
        return isImmediate;
    }

    @Override
    public String toString() {
        // 构建基本指令
        StringBuilder sb = new StringBuilder();
        sb.append(getMoveTypeString());
        sb.append("\t");
        sb.append(getDefReg().toString());
        sb.append(",\t");
        
        // 使用父类方法获取操作数，确保获取到寄存器分配后的物理寄存器
        sb.append(getOperands().get(0).toString());

        // 添加移位信息（如果有）
        if (shift > 0) {
            sb.append(", lsl #").append(shift);
        }
        
        return sb.toString();
    }
} 
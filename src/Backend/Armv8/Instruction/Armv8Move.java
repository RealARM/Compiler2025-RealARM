package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Imm;
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
    private final boolean is32Bit; // 是否是32位(w寄存器)而非64位(x寄存器)
    private final boolean isImmediate; // 是否是立即数操作数
    private final MoveType moveType; // 移动类型

    /**
     * 构造一个Move指令
     * @param destReg 目标寄存器
     * @param operand 源操作数（寄存器或立即数）
     * @param isImmediate 是否为立即数操作
     */
    public Armv8Move(Armv8Reg destReg, Armv8Operand operand, boolean isImmediate) {
        super(destReg, new ArrayList<>(Collections.singletonList(operand)));
        this.isImmediate = isImmediate;
        this.is32Bit = false; // 默认使用64位寄存器
        this.moveType = MoveType.MOV;
    }

    /**
     * 构造一个Move指令，可指定是否为32位操作
     * @param destReg 目标寄存器
     * @param operand 源操作数（寄存器或立即数）
     * @param isImmediate 是否为立即数操作
     * @param is32Bit 是否为32位操作
     */
    public Armv8Move(Armv8Reg destReg, Armv8Operand operand, boolean isImmediate, boolean is32Bit) {
        super(destReg, new ArrayList<>(Collections.singletonList(operand)));
        this.isImmediate = isImmediate;
        this.is32Bit = is32Bit;
        this.moveType = MoveType.MOV;
    }

    /**
     * 构造一个特定类型的Move指令
     * @param destReg 目标寄存器
     * @param operand 源操作数（寄存器或立即数）
     * @param isImmediate 是否为立即数操作
     * @param is32Bit 是否为32位操作
     * @param moveType 移动指令类型
     */
    public Armv8Move(Armv8Reg destReg, Armv8Operand operand, boolean isImmediate, boolean is32Bit, MoveType moveType) {
        super(destReg, new ArrayList<>(Collections.singletonList(operand)));
        this.isImmediate = isImmediate;
        this.is32Bit = is32Bit;
        this.moveType = moveType;
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

    /**
     * 是否为32位操作
     */
    public boolean is32Bit() {
        return is32Bit;
    }

    @Override
    public String toString() {
        // 确定寄存器前缀
        String regPrefix = is32Bit ? "w" : "x";
        
        // 获取目标寄存器编号
        String destRegStr = defReg.toString();
        if (destRegStr.startsWith("x") || destRegStr.startsWith("w")) {
            destRegStr = regPrefix + destRegStr.substring(1);
        }

        // 构建基本指令
        StringBuilder sb = new StringBuilder();
        sb.append(getMoveTypeString());
        sb.append("\t");
        sb.append(destRegStr);
        sb.append(",\t");
        
        // 对于寄存器类型的源操作数，也需要处理寄存器前缀
        Armv8Operand srcOp = operands.get(0);
        if (!isImmediate && srcOp instanceof Armv8Reg) {
            String srcRegStr = srcOp.toString();
            if (srcRegStr.startsWith("x") || srcRegStr.startsWith("w")) {
                srcRegStr = regPrefix + srcRegStr.substring(1);
            }
            sb.append(srcRegStr);
        } else {
            // 立即数或其他操作数直接添加
            sb.append(srcOp.toString());
        }
        
        return sb.toString();
    }
} 
package Backend.Value.Instruction.DataMovement;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64FPUReg;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Collections;


public class AArch64Move extends AArch64Instruction {
    private final boolean isImmediate;
    private final MoveType moveType;
    private int shift = 0; // 移位值，用于MOVZ/MOVK指令

    public AArch64Move(AArch64Reg destReg, AArch64Operand operand, boolean isImmediate) {
        super(destReg, new ArrayList<>(Collections.singletonList(operand)));
        if (operand == null) {
            System.err.println("警告: 创建Move指令时操作数为null，destReg=" + destReg);
            new Exception().printStackTrace(); // 打印调用栈
        }
        this.isImmediate = isImmediate;
        this.moveType = MoveType.MOV;
    }

    public AArch64Move(AArch64Reg destReg, AArch64Operand operand, boolean isImmediate, MoveType moveType) {
        super(destReg, new ArrayList<>(Collections.singletonList(operand)));
        if (operand == null) {
            System.err.println("警告: 创建Move指令时操作数为null，destReg=" + destReg + ", moveType=" + moveType);
            new Exception().printStackTrace(); // 打印调用栈
        }
        this.isImmediate = isImmediate;
        this.moveType = moveType;
    }

    public void setShift(int shift) {
        this.shift = shift;
    }

    public int getShift() {
        return shift;
    }

    public enum MoveType {
        MOV,  // 基本移动
        MOVK, // 保持其他位不变，更新指定16位
        MOVZ, // 将其他位清零，更新指定16位
        MOVN  // 对操作数按位取反后移动
    }

    private String getMoveTypeString() {
        return moveType.toString().toLowerCase();
    }

    public boolean isImmediate() {
        return isImmediate;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        // 检查是否需要使用fmov指令，只有在寄存器到寄存器的MOV操作时才检查
        boolean shouldUseFmov = false;
        if (moveType == MoveType.MOV && !isImmediate) {
            
            if (getDefReg() instanceof AArch64FPUReg && 
                !getOperands().isEmpty() && 
                getOperands().get(0) instanceof AArch64FPUReg) {
                shouldUseFmov = true;
            }
        }
        
        if (shouldUseFmov) {
            sb.append("fmov");
        } else {
            sb.append(getMoveTypeString());
        }
        
        sb.append("\t");
        sb.append(getDefReg().toString());
        sb.append(",\t");
        
        if (getOperands().isEmpty()) {
            throw new RuntimeException("Move instruction has no operands");
        } else {
            AArch64Operand operand = getOperands().get(0);
            if (operand == null) {
                throw new RuntimeException("Move instruction operand is null");
            } else {
                sb.append(operand.toString());
            }
        }

        if (shift > 0) {
            sb.append(", lsl #").append(shift);
        }
        
        return sb.toString();
    }
} 
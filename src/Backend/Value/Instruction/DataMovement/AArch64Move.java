package Backend.Value.Instruction.DataMovement;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64FPUReg;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64VirReg;
import Backend.Value.Operand.Constant.AArch64Imm;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Move extends AArch64Instruction {
    private final boolean isImmediate;
    private final MoveType moveType;
    private int shift = 0; // 移位值，用于MOVZ/MOVK指令
    private boolean use32BitMode = false; // 指令级别的32位模式标志

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

    public void setUse32BitMode(boolean use32Bit) {
        this.use32BitMode = use32Bit;
    }

    public boolean isUse32BitMode() {
        return this.use32BitMode;
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

    private String getRegisterString(AArch64Reg reg) {
        if (reg instanceof AArch64CPUReg) {
            AArch64CPUReg cpuReg = (AArch64CPUReg) reg;
            return use32BitMode ? cpuReg.to32BitString() : cpuReg.to64BitString();
        } else if (reg instanceof AArch64VirReg) {
            AArch64VirReg virReg = (AArch64VirReg) reg;
            return use32BitMode ? virReg.to32BitString() : virReg.to64BitString();
        } else {
            return reg.toString();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
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
        String destStr;
        if (isImmediate && getDefReg() instanceof AArch64CPUReg) {
            AArch64CPUReg cpuDest = (AArch64CPUReg) getDefReg();
            boolean use64Bit = !use32BitMode; // 使用指令级别的模式设置
            if (!getOperands().isEmpty() && getOperands().get(0) instanceof AArch64Imm) {
                AArch64Imm imm = (AArch64Imm) getOperands().get(0);
                long immVal = imm.getValue();
                // 如果值超出32位范围，强制使用64位
                if ((immVal < 0) || (immVal > 0xFFFFFFFFL)) {
                    use64Bit = true;
                }
            }
            destStr = use64Bit ? cpuDest.to64BitString() : cpuDest.to32BitString();
            if (moveType == MoveType.MOVK || moveType == MoveType.MOVZ) {
                destStr = cpuDest.to64BitString();
            }
        } else {
            // 对于非立即数的情况，使用统一的寄存器字符串生成方法
            destStr = getRegisterString(getDefReg());
        }
        sb.append(destStr);
        sb.append(",\t");
        
        if (getOperands().isEmpty()) {
            throw new RuntimeException("Move instruction has no operands");
        } else {
            AArch64Operand operand = getOperands().get(0);
            if (operand == null) {
                throw new RuntimeException("Move instruction operand is null");
            } else {
                // 对于寄存器操作数，也使用统一的字符串生成方法
                if (operand instanceof AArch64Reg && !shouldUseFmov) {
                    sb.append(getRegisterString((AArch64Reg) operand));
                } else {
                    sb.append(operand.toString());
                }
            }
        }

        if (shift > 0) {
            sb.append(", lsl #").append(shift);
        }
        
        return sb.toString();
    }
} 
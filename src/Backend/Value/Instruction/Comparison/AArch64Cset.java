package Backend.Value.Instruction.Comparison;

import Backend.Utils.AArch64Tools;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64VirReg;

import java.util.ArrayList;

public class AArch64Cset extends AArch64Instruction {
    private AArch64Reg destReg;
    private AArch64Tools.CondType condType;
    private boolean use32BitMode = true; // 标志位：默认使用32位指令

    public AArch64Cset(AArch64Reg destReg, AArch64Tools.CondType condType) {
        super(destReg, new ArrayList<>());
        this.destReg = destReg;
        this.condType = condType;
    }

    public AArch64Reg getDestReg() {
        return destReg;
    }

    public AArch64Tools.CondType getCondType() {
        return condType;
    }
    
    /**
     * 设置指令使用32位模式
     */
    public void setUse32BitMode(boolean use32Bit) {
        this.use32BitMode = use32Bit;
    }
    
    /**
     * 获取当前是否使用32位模式
     */
    public boolean isUse32BitMode() {
        return this.use32BitMode;
    }
    
    /**
     * 根据指令的32位模式标志获取寄存器的字符串表示
     */
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
        sb.append("cset ");
        
        sb.append(getRegisterString(getDefReg()));
        
        sb.append(", ");
        sb.append(AArch64Tools.getCondString(condType));
        
        return sb.toString();
    }
} 
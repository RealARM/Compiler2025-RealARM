package Backend.Value.Instruction.Arithmetic;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64VirReg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Unary extends AArch64Instruction {
    private AArch64Reg srcReg;
    private AArch64Reg destReg;
    private AArch64UnaryType unaryType;
    private boolean use32BitMode = false; // 标志位：是否使用32位指令

    public enum AArch64UnaryType {
        neg,
        mvn,
        fneg
    }

    public AArch64Unary(AArch64Reg srcReg, AArch64Reg destReg, AArch64UnaryType unaryType) {
        super(destReg, new ArrayList<>(Arrays.asList(srcReg)));
        this.srcReg = srcReg;
        this.destReg = destReg;
        this.unaryType = unaryType;
        // 默认情况下，整数运算使用32位模式（浮点运算除外）
        this.use32BitMode = (unaryType != AArch64UnaryType.fneg);
    }

    public AArch64Reg getSrcReg() {
        return srcReg;
    }

    public AArch64Reg getDestReg() {
        return destReg;
    }

    public AArch64UnaryType getUnaryType() {
        return unaryType;
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
        // 浮点运算不使用32位/64位区分
        if (unaryType == AArch64UnaryType.fneg) {
            return reg.toString();
        }
        
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
        
        switch (unaryType) {
            case neg:
                sb.append("neg\t");
                break;
            case mvn:
                sb.append("mvn\t");
                break;
            case fneg:
                sb.append("fneg\t");
                break;
            default:
                sb.append("UNKNOWN_UNARY\t");
                break;
        }
        
        sb.append(getRegisterString(getDefReg())).append(", ").append(getRegisterString(getSrcReg()));
        
        return sb.toString();
    }
} 
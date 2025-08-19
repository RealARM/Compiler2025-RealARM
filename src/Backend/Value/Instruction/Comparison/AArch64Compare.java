package Backend.Value.Instruction.Comparison;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64VirReg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Compare extends AArch64Instruction {
    private CmpType type;
    private boolean use32BitMode = true; // 指令级别的32位模式标志
    
    public AArch64Compare(AArch64Operand left, AArch64Operand right, CmpType type) {
        super(null, new ArrayList<>(Arrays.asList(left, right)));
        this.type = type;
    }

    public enum CmpType {
        cmp, // 比较
        cmn, // 比较负值
        fcmp, // 浮点比较
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
    private String getRegisterString(AArch64Operand operand) {
        if (type == CmpType.fcmp) {
            // 浮点比较始终使用默认格式
            return operand.toString();
        }
        
        if (operand instanceof AArch64CPUReg) {
            AArch64CPUReg cpuReg = (AArch64CPUReg) operand;
            return use32BitMode ? cpuReg.to32BitString() : cpuReg.to64BitString();
        } else if (operand instanceof AArch64VirReg) {
            AArch64VirReg virReg = (AArch64VirReg) operand;
            return use32BitMode ? virReg.to32BitString() : virReg.to64BitString();
        } else {
            return operand.toString();
        }
    }

    public String getCmpTypeStr() {
        switch (this.type) {
            case cmn:
                return "cmn";
            case cmp:
                return "cmp";
            case fcmp:
                return "fcmp";
            default:
                return null;
        }
    }

    @Override
    public String toString() {
        return getCmpTypeStr() + "\t" + getRegisterString(getOperands().get(0)) + ",\t" + getRegisterString(getOperands().get(1));
    }
} 
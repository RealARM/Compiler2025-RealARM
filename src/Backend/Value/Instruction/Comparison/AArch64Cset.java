package Backend.Value.Instruction.Comparison;

import Backend.Utils.AArch64Tools;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;

/**
 * Represents an ARMv8 CSET instruction
 * CSET sets a register to 1 if the condition is true, otherwise 0
 * Example: cset w0, eq   # Set w0 to 1 if the Z flag is set, otherwise 0
 */
public class AArch64Cset extends AArch64Instruction {
    private AArch64Reg destReg;
    private AArch64Tools.CondType condType;

    /**
     * Creates a new CSET instruction
     * @param destReg The destination register
     * @param condType The condition type
     */
    public AArch64Cset(AArch64Reg destReg, AArch64Tools.CondType condType) {
        super(destReg, new ArrayList<>());  // CSET has no operands, but we need an empty ArrayList
        this.destReg = destReg;
        this.condType = condType;
    }

    public AArch64Reg getDestReg() {
        return destReg;
    }

    public AArch64Tools.CondType getCondType() {
        return condType;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("cset ");
        
        // 使用父类的getDefReg()方法，确保获取到寄存器分配后的物理寄存器
        sb.append(getDefReg().toString());
        
        // Append condition
        sb.append(", ");
        sb.append(AArch64Tools.getCondString(condType));
        
        return sb.toString();
    }
} 
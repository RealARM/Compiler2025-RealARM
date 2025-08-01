package Backend.Value.Instruction.Comparison;

import Backend.Utils.AArch64Tools;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;

public class AArch64Cset extends AArch64Instruction {
    private AArch64Reg destReg;
    private AArch64Tools.CondType condType;

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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("cset ");
        
        sb.append(getDefReg().toString());
        
        sb.append(", ");
        sb.append(AArch64Tools.getCondString(condType));
        
        return sb.toString();
    }
} 
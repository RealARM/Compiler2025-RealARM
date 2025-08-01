package Backend.Value.Instruction.Memory;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64VirReg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Load extends AArch64Instruction {
    
    public AArch64Load(AArch64Reg baseReg, AArch64Operand offset, AArch64Reg defReg) {
        super(defReg, new ArrayList<>(Arrays.asList(baseReg, offset)));
    }

    @Override
    public String toString() {
        String loadInstr = "ldr\t";
        
        if (getOperands().get(1) instanceof AArch64Imm && ((AArch64Imm)(getOperands().get(1))).getValue() == 0) {
            return loadInstr + getDefReg() + ",\t[" + getOperands().get(0) +  "]";
        } else {
            return loadInstr + getDefReg() + ",\t[" + getOperands().get(0) + ", " + getOperands().get(1) + "]";
        }
    }
} 
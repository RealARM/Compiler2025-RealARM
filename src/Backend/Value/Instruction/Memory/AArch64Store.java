package Backend.Value.Instruction.Memory;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Store extends AArch64Instruction {
    
    public AArch64Store(AArch64Reg storeReg, AArch64Reg baseReg, AArch64Operand offset) {
        super(null, new ArrayList<>(Arrays.asList(storeReg, baseReg, offset)));
    }

    @Override
    public String toString() {
        String storeInstr = "str\t";
        
        String srcReg = getOperands().get(0).toString();
        
        if (getOperands().get(2) instanceof AArch64Imm && ((AArch64Imm)(getOperands().get(2))).getValue() == 0) {
            return storeInstr + srcReg + ",\t[" + getOperands().get(1) + "]";
        } else {
            return storeInstr + srcReg + ",\t[" + getOperands().get(1) + ", " + getOperands().get(2) + "]";
        }
    }
} 
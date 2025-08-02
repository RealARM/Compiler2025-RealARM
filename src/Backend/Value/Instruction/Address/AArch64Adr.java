package Backend.Value.Instruction.Address;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Symbol.AArch64Label;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Adr extends AArch64Instruction {
    
    public AArch64Adr(AArch64Reg destReg, AArch64Label label) {
        super(destReg, new ArrayList<>(Arrays.asList(label)));
    }

    @Override
    public String toString() {
        return "adr\t" + getDefReg() + ", " + getOperands().get(0);
    }
} 
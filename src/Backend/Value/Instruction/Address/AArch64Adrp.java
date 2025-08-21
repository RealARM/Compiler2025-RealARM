package Backend.Value.Instruction.Address;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Symbol.AArch64Label;

import java.util.ArrayList;

public class AArch64Adrp extends AArch64Instruction {
    public AArch64Adrp(AArch64Reg destReg, AArch64Label label) {
        super(destReg, new ArrayList<>());
        this.operands.add(label);
        // ADRP指令始终使用64位寄存器
    }

    @Override
    public String toString() {
        return "adrp\t" + getDefReg() + ", " + getOperands().get(0);
    }
}
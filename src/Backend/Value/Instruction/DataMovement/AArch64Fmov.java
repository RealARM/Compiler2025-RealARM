package Backend.Value.Instruction.DataMovement;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Fmov extends AArch64Instruction {

    // 统一构造器
    public AArch64Fmov(AArch64Reg destReg, AArch64Operand source) {
        super(destReg, new ArrayList<>(Collections.singletonList(source)));
    }

    @Override
    public String toString() {
        return "fmov\t" + getDefReg() + ", " + getOperands().get(0);
    }
} 
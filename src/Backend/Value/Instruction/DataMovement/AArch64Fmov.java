package Backend.Value.Instruction.DataMovement;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Fmov extends AArch64Instruction {

    public AArch64Fmov(AArch64Reg destReg, AArch64Operand source) {
        super(destReg, new ArrayList<>(Collections.singletonList(source)));
    }

    @Override
    public String toString() {
        String dest = getDefReg().toString();
        String src = getOperands().get(0).toString();

        if (getDefReg() instanceof Backend.Value.Operand.Register.AArch64FPUReg &&
            getOperands().get(0) instanceof Backend.Value.Operand.Register.AArch64CPUReg cpuSrc) {
            src = cpuSrc.get32BitName();
        }
        if (getDefReg() instanceof Backend.Value.Operand.Register.AArch64CPUReg cpuDst &&
            !getOperands().isEmpty() && getOperands().get(0) instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
            dest = cpuDst.get32BitName();
        }

        return "fmov\t" + dest + ", " + src;
    }
} 
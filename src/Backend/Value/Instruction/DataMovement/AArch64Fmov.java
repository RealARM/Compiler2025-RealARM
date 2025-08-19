package Backend.Value.Instruction.DataMovement;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64CPUReg;

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

        // 处理浮点寄存器到CPU寄存器的移动
        if (getDefReg() instanceof Backend.Value.Operand.Register.AArch64FPUReg &&
            getOperands().get(0) instanceof Backend.Value.Operand.Register.AArch64CPUReg) {
            AArch64CPUReg cpuSrc = (AArch64CPUReg) getOperands().get(0);
            src = cpuSrc.to32BitString();
        }
        
        // 处理CPU寄存器到浮点寄存器的移动
        if (getDefReg() instanceof Backend.Value.Operand.Register.AArch64CPUReg &&
            !getOperands().isEmpty() && getOperands().get(0) instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
            AArch64CPUReg cpuDst = (AArch64CPUReg) getDefReg();
            dest = cpuDst.to32BitString();
        }

        return "fmov\t" + dest + ", " + src;
    }
} 
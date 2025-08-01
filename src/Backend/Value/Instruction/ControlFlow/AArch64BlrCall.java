package Backend.Value.Instruction.ControlFlow;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * ARMv8间接分支链接指令
 * 用于调用寄存器中存储的函数地址
 */
public class AArch64BlrCall extends AArch64Instruction {
    public LinkedHashSet<AArch64Reg> usedRegs = new LinkedHashSet<>();
    
    public AArch64BlrCall(AArch64Reg targetReg) {
        super(AArch64CPUReg.getAArch64CPURetValueReg(), new ArrayList<>(Collections.singleton(targetReg)));
    }

    public void addUsedReg(AArch64Reg usedReg) {
        usedRegs.add(usedReg);
    }

    public LinkedHashSet<AArch64Reg> getUsedRegs() {
        return usedRegs;
    }

    @Override
    public String toString() {
        return "blr\t" + getOperands().get(0);
    }
} 
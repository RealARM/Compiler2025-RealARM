package Backend.Value.Instruction.ControlFlow;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Operand.Register.Armv8CPUReg;
import Backend.Value.Operand.Register.Armv8Reg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * ARMv8间接分支链接指令
 * 用于调用寄存器中存储的函数地址
 */
public class Armv8BlrCall extends Armv8Instruction {
    public LinkedHashSet<Armv8Reg> usedRegs = new LinkedHashSet<>();
    
    public Armv8BlrCall(Armv8Reg targetReg) {
        super(Armv8CPUReg.getArmv8CPURetValueReg(), new ArrayList<>(Collections.singleton(targetReg)));
    }

    public void addUsedReg(Armv8Reg usedReg) {
        usedRegs.add(usedReg);
    }

    public LinkedHashSet<Armv8Reg> getUsedRegs() {
        return usedRegs;
    }

    @Override
    public String toString() {
        return "blr\t" + getOperands().get(0);
    }
} 
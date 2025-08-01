package Backend.Value.Instruction.ControlFlow;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Operand.Register.Armv8CPUReg;
import Backend.Value.Operand.Register.Armv8Reg;
import Backend.Value.Operand.Symbol.Armv8Label;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

public class Armv8Call extends Armv8Instruction {
    public LinkedHashSet<Armv8Reg> usedRegs = new LinkedHashSet<>();
    
    public Armv8Call(Armv8Label targetFunction) {
        super(Armv8CPUReg.getArmv8CPURetValueReg(), new ArrayList<>(Collections.singleton(targetFunction)));
    }

    public void addUsedReg(Armv8Reg usedReg) {
        usedRegs.add(usedReg);
    }

    public LinkedHashSet<Armv8Reg> getUsedRegs() {
        return usedRegs;
    }

    @Override
    public String toString() {
        return "bl\t" + getOperands().get(0);
    }
} 
package Backend.Value.Instruction.DataMovement;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Base.Armv8Operand;
import Backend.Value.Operand.Register.Armv8Reg;

import java.util.ArrayList;
import java.util.Collections;

public class Armv8Fmov extends Armv8Instruction {

    // 统一构造器
    public Armv8Fmov(Armv8Reg destReg, Armv8Operand source) {
        super(destReg, new ArrayList<>(Collections.singletonList(source)));
    }

    @Override
    public String toString() {
        return "fmov\t" + getDefReg() + ", " + getOperands().get(0);
    }
} 
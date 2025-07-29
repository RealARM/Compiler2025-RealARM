package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

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
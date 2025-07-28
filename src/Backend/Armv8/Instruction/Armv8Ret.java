package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Collections;

public class Armv8Ret extends Armv8Instruction {
    public Armv8Ret() {
        super(null, new ArrayList<>());
    }

    public Armv8Ret(Armv8Reg returnReg) {
        super(null, new ArrayList<>(Collections.singletonList(returnReg)));
    }

    @Override
    public String toString() {
        return "ret";
    }
} 
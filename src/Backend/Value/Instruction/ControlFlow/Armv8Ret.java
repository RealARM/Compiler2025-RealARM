package Backend.Value.Instruction.ControlFlow;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Operand.Register.Armv8Reg;

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
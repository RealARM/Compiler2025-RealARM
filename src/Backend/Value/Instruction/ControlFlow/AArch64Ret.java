package Backend.Value.Instruction.ControlFlow;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Ret extends AArch64Instruction {
    public AArch64Ret() {
        super(null, new ArrayList<>());
    }

    public AArch64Ret(AArch64Reg returnReg) {
        super(null, new ArrayList<>(Collections.singletonList(returnReg)));
    }

    @Override
    public String toString() {
        return "ret";
    }
} 
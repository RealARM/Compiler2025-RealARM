package Backend.Value.Instruction.System;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Constant.AArch64Imm;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Syscall extends AArch64Instruction {

    public AArch64Syscall(AArch64Imm syscallNumber) {
        super(null, new ArrayList<>(Collections.singleton(syscallNumber)));
    }

    @Override
    public String toString() {
        return "svc\t" + getOperands().get(0);
    }
} 
package Backend.Value.Instruction.System;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Operand.Constant.Armv8Imm;

import java.util.ArrayList;
import java.util.Collections;

/**
 * ARMv8系统调用指令
 * 在ARMv8架构中使用svc指令替代ARMv7的swi指令
 */
public class Armv8Syscall extends Armv8Instruction {

    public Armv8Syscall(Armv8Imm syscallNumber) {
        super(null, new ArrayList<>(Collections.singleton(syscallNumber)));
    }

    @Override
    public String toString() {
        return "svc\t" + getOperands().get(0);
    }
} 
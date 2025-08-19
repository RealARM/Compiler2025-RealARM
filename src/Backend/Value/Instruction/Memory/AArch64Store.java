package Backend.Value.Instruction.Memory;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Store extends AArch64Instruction {
    public AArch64Store(AArch64Reg storeReg, AArch64Reg baseReg, AArch64Operand offset) {
        super(null, new ArrayList<>(Arrays.asList(storeReg, baseReg, offset)));
    }

    @Override
    public String toString() {
        // 内存操作始终使用64位地址寄存器
        return "str\t" + getOperands().get(0) + ", [" + getOperands().get(1) + ", " + getOperands().get(2) + "]";
    }
} 
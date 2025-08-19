package Backend.Value.Instruction.Memory;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Load extends AArch64Instruction {
    public AArch64Load(AArch64Reg baseReg, AArch64Operand offset, AArch64Reg defReg) {
        super(defReg, new ArrayList<>(Arrays.asList(baseReg, offset)));
    }

    @Override
    public String toString() {
        // 内存操作始终使用64位地址寄存器
        return "ldr\t" + getDefReg() + ", [" + getOperands().get(0) + ", " + getOperands().get(1) + "]";
    }
} 
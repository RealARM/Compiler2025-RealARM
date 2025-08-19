package Backend.Value.Instruction.Address;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Symbol.AArch64Label;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Adr extends AArch64Instruction {
    
    public AArch64Adr(AArch64Reg destReg, AArch64Label label) {
        super(destReg, new ArrayList<>(Arrays.asList(label)));
        // ADR指令始终使用64位寄存器（地址必须是64位）
    }

    @Override
    public String toString() {
        // 地址操作始终使用64位寄存器格式
        return "adr\t" + getDefReg() + ", " + getOperands().get(0);
    }
} 
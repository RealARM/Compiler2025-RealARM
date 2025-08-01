package Backend.Value.Instruction.Address;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Symbol.AArch64Label;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ARMv8地址加载指令 (ADR)
 * 用于加载标签相对于程序计数器的相对地址到寄存器
 */
public class AArch64Adr extends AArch64Instruction {
    
    /**
     * 创建一个ADR指令
     * @param destReg 目标寄存器，将地址加载到此寄存器
     * @param label 要加载的标签地址
     */
    public AArch64Adr(AArch64Reg destReg, AArch64Label label) {
        super(destReg, new ArrayList<>(Arrays.asList(label)));
    }

    @Override
    public String toString() {
        return "adr\t" + getDefReg() + ", " + getOperands().get(0);
    }
} 
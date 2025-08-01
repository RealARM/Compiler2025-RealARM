package Backend.Value.Instruction.Address;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Symbol.AArch64Label;

import java.util.ArrayList;

/**
 * ADRP指令 - 计算页地址
 * 用于访问全局变量，可以访问±4GB范围内的地址
 */
public class AArch64Adrp extends AArch64Instruction {
    private final AArch64Label label;

    public AArch64Adrp(AArch64Reg destReg, AArch64Label label) {
        super(destReg, new ArrayList<>());
        this.label = label;
    }

    public AArch64Label getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return "adrp\t" + getDefReg() + ", " + label.getLabelName();
    }
}
package Backend.Value.Instruction.Address;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Operand.Register.Armv8Reg;
import Backend.Value.Operand.Symbol.Armv8Label;

import java.util.ArrayList;

/**
 * ADRP指令 - 计算页地址
 * 用于访问全局变量，可以访问±4GB范围内的地址
 */
public class Armv8Adrp extends Armv8Instruction {
    private final Armv8Label label;

    public Armv8Adrp(Armv8Reg destReg, Armv8Label label) {
        super(destReg, new ArrayList<>());
        this.label = label;
    }

    public Armv8Label getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return "adrp\t" + getDefReg() + ", " + label.getLabelName();
    }
}
package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Label;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ARMv8地址加载指令 (ADR)
 * 用于加载标签相对于程序计数器的相对地址到寄存器
 */
public class Armv8Adr extends Armv8Instruction {
    
    /**
     * 创建一个ADR指令
     * @param destReg 目标寄存器，将地址加载到此寄存器
     * @param label 要加载的标签地址
     */
    public Armv8Adr(Armv8Reg destReg, Armv8Label label) {
        super(destReg, new ArrayList<>(Arrays.asList(label)));
    }

    @Override
    public String toString() {
        return "adr\t" + getDefReg() + ", " + getOperands().get(0);
    }
} 
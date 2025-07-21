package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Imm;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ARMv8配对寄存器存储指令
 * 同时存储两个寄存器，可以提高内存访问效率
 */
public class Armv8StorePair extends Armv8Instruction {
    private final boolean is32Bit; // 是否是32位(w)存储而非64位(x)
    private final boolean isPreIndex; // 是否是前索引模式
    private final boolean isPostIndex; // 是否是后索引模式
    
    /**
     * 创建一个基本的STP指令
     */
    public Armv8StorePair(Armv8Reg storeReg1, Armv8Reg storeReg2, Armv8Reg baseReg, Armv8Imm offset, boolean is32Bit) {
        super(null, new ArrayList<>(Arrays.asList(storeReg1, storeReg2, baseReg, offset)));
        this.is32Bit = is32Bit;
        this.isPreIndex = false;
        this.isPostIndex = false;
    }
    
    /**
     * 创建一个前索引或后索引模式的STP指令
     */
    public Armv8StorePair(Armv8Reg storeReg1, Armv8Reg storeReg2, Armv8Reg baseReg, Armv8Imm offset, 
                        boolean is32Bit, boolean isPreIndex, boolean isPostIndex) {
        super(null, new ArrayList<>(Arrays.asList(storeReg1, storeReg2, baseReg, offset)));
        this.is32Bit = is32Bit;
        this.isPreIndex = isPreIndex;
        this.isPostIndex = isPostIndex;
    }

    @Override
    public String toString() {
        String regType = is32Bit ? "w" : "x";
        String reg1 = getOperands().get(0).toString().startsWith(regType) ? 
            getOperands().get(0).toString() : regType + getOperands().get(0).toString().substring(1);
        String reg2 = getOperands().get(1).toString().startsWith(regType) ? 
            getOperands().get(1).toString() : regType + getOperands().get(1).toString().substring(1);
        
        StringBuilder sb = new StringBuilder();
        sb.append("stp\t").append(reg1).append(", ").append(reg2).append(", ");
        
        if (isPreIndex) {
            sb.append("[").append(getOperands().get(2)).append(", ").append(getOperands().get(3)).append("]!"); 
        } else if (isPostIndex) {
            sb.append("[").append(getOperands().get(2)).append("], ").append(getOperands().get(3));
        } else {
            sb.append("[").append(getOperands().get(2));
            if (getOperands().get(3) instanceof Armv8Imm && ((Armv8Imm)getOperands().get(3)).getValue() != 0) {
                sb.append(", ").append(getOperands().get(3));
            }
            sb.append("]");
        }
        
        return sb.toString();
    }
} 
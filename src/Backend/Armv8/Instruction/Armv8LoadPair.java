package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Imm;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ARMv8配对寄存器加载指令
 * 同时加载两个寄存器，可以提高内存访问效率
 */
public class Armv8LoadPair extends Armv8Instruction {
    private final boolean is32Bit; // 是否是32位(w)加载而非64位(x)
    private final boolean isPreIndex; // 是否是前索引模式
    private final boolean isPostIndex; // 是否是后索引模式
    
    /**
     * 创建一个基本的LDP指令
     */
    public Armv8LoadPair(Armv8Reg baseReg, Armv8Imm offset, Armv8Reg defReg1, Armv8Reg defReg2, boolean is32Bit) {
        super(defReg1, new ArrayList<>(Arrays.asList(baseReg, offset, defReg2)));
        this.is32Bit = is32Bit;
        this.isPreIndex = false;
        this.isPostIndex = false;
    }
    
    /**
     * 创建一个前索引或后索引模式的LDP指令
     */
    public Armv8LoadPair(Armv8Reg baseReg, Armv8Imm offset, Armv8Reg defReg1, Armv8Reg defReg2, 
                       boolean is32Bit, boolean isPreIndex, boolean isPostIndex) {
        super(defReg1, new ArrayList<>(Arrays.asList(baseReg, offset, defReg2)));
        this.is32Bit = is32Bit;
        this.isPreIndex = isPreIndex;
        this.isPostIndex = isPostIndex;
    }

    @Override
    public String toString() {
        String regType = is32Bit ? "w" : "x";
        String reg1 = getDefReg().toString().startsWith(regType) ? 
            getDefReg().toString() : regType + getDefReg().toString().substring(1);
        String reg2 = getOperands().get(2).toString().startsWith(regType) ? 
            getOperands().get(2).toString() : regType + getOperands().get(2).toString().substring(1);
        
        StringBuilder sb = new StringBuilder();
        sb.append("ldp\t").append(reg1).append(", ").append(reg2).append(", ");
        
        if (isPreIndex) {
            sb.append("[").append(getOperands().get(0)).append(", ").append(getOperands().get(1)).append("]!"); 
        } else if (isPostIndex) {
            sb.append("[").append(getOperands().get(0)).append("], ").append(getOperands().get(1));
        } else {
            sb.append("[").append(getOperands().get(0));
            if (getOperands().get(1) instanceof Armv8Imm && ((Armv8Imm)getOperands().get(1)).getValue() != 0) {
                sb.append(", ").append(getOperands().get(1));
            }
            sb.append("]");
        }
        
        return sb.toString();
    }
} 
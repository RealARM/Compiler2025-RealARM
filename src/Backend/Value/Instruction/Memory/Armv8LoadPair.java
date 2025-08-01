package Backend.Value.Instruction.Memory;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Operand.Constant.Armv8Imm;
import Backend.Value.Operand.Register.Armv8Reg;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ARMv8配对寄存器加载指令
 * 同时加载两个寄存器，可以提高内存访问效率
 */
public class Armv8LoadPair extends Armv8Instruction {
    private final boolean isPreIndex; // 是否是前索引模式
    private final boolean isPostIndex; // 是否是后索引模式
    
    /**
     * 创建一个基本的LDP指令
     */
    public Armv8LoadPair(Armv8Reg baseReg, Armv8Imm offset, Armv8Reg defReg1, Armv8Reg defReg2) {
        super(defReg1, new ArrayList<>(Arrays.asList(baseReg, offset, defReg2)));
        this.isPreIndex = false;
        this.isPostIndex = false;
    }
    
    /**
     * 创建一个前索引或后索引模式的LDP指令
     */
    public Armv8LoadPair(Armv8Reg baseReg, Armv8Imm offset, Armv8Reg defReg1, Armv8Reg defReg2, 
                       boolean isPreIndex, boolean isPostIndex) {
        super(defReg1, new ArrayList<>(Arrays.asList(baseReg, offset, defReg2)));
        this.isPreIndex = isPreIndex;
        this.isPostIndex = isPostIndex;
    }

    @Override
    public String toString() {
        // 使用64位x寄存器
        String reg1 = getDefReg().toString();
        String reg2 = getOperands().get(2).toString();
        
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
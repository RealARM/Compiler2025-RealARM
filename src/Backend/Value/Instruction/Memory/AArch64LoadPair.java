package Backend.Value.Instruction.Memory;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64LoadPair extends AArch64Instruction {
    private final boolean isPreIndex;
    private final boolean isPostIndex;
    
    public AArch64LoadPair(AArch64Reg baseReg, AArch64Imm offset, AArch64Reg defReg1, AArch64Reg defReg2) {
        super(defReg1, new ArrayList<>(Arrays.asList(baseReg, offset, defReg2)));
        this.isPreIndex = false;
        this.isPostIndex = false;
        // 内存操作始终使用64位地址寄存器，不需要设置32位模式
    }
    
    public AArch64LoadPair(AArch64Reg baseReg, AArch64Imm offset, AArch64Reg defReg1, AArch64Reg defReg2, 
                       boolean isPreIndex, boolean isPostIndex) {
        super(defReg1, new ArrayList<>(Arrays.asList(baseReg, offset, defReg2)));
        this.isPreIndex = isPreIndex;
        this.isPostIndex = isPostIndex;
        // 内存操作始终使用64位地址寄存器，不需要设置32位模式
    }

    @Override
    public String toString() {
        // 内存操作始终使用64位寄存器格式
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
            if (getOperands().get(1) instanceof AArch64Imm && ((AArch64Imm)getOperands().get(1)).getValue() != 0) {
                sb.append(", ").append(getOperands().get(1));
            }
            sb.append("]");
        }
        
        return sb.toString();
    }
} 
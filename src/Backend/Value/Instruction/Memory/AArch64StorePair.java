package Backend.Value.Instruction.Memory;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64StorePair extends AArch64Instruction {
    private final boolean isPreIndex;
    private final boolean isPostIndex;
    
    public AArch64StorePair(AArch64Reg storeReg1, AArch64Reg storeReg2, AArch64Reg baseReg, AArch64Imm offset) {
        super(null, new ArrayList<>(Arrays.asList(storeReg1, storeReg2, baseReg, offset)));
        this.isPreIndex = false;
        this.isPostIndex = false;
        // 内存操作始终使用64位地址寄存器，不需要设置32位模式
    }
    
    public AArch64StorePair(AArch64Reg storeReg1, AArch64Reg storeReg2, AArch64Reg baseReg, AArch64Imm offset, 
                        boolean isPreIndex, boolean isPostIndex) {
        super(null, new ArrayList<>(Arrays.asList(storeReg1, storeReg2, baseReg, offset)));
        this.isPreIndex = isPreIndex;
        this.isPostIndex = isPostIndex;
        // 内存操作始终使用64位地址寄存器，不需要设置32位模式
    }

    @Override
    public String toString() {
        // 内存操作始终使用64位寄存器格式
        String reg1 = getOperands().get(0).toString();
        String reg2 = getOperands().get(1).toString();
        
        StringBuilder sb = new StringBuilder();
        sb.append("stp\t").append(reg1).append(", ").append(reg2).append(", ");
        
        if (isPreIndex) {
            sb.append("[").append(getOperands().get(2)).append(", ").append(getOperands().get(3)).append("]!"); 
        } else if (isPostIndex) {
            sb.append("[").append(getOperands().get(2)).append("], ").append(getOperands().get(3));
        } else {
            sb.append("[").append(getOperands().get(2));
            if (getOperands().get(3) instanceof AArch64Imm && ((AArch64Imm)getOperands().get(3)).getValue() != 0) {
                sb.append(", ").append(getOperands().get(3));
            }
            sb.append("]");
        }
        
        return sb.toString();
    }
} 
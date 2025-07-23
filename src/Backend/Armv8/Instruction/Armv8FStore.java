package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Imm;
import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;
import Backend.Armv8.Operand.Armv8FPUReg;

import java.util.ArrayList;
import java.util.Arrays;

public class Armv8FStore extends Armv8Instruction {
    private final boolean is32Bit; // 是否是32位(s)存储而非64位(d)
    
    public Armv8FStore(Armv8FPUReg storeReg, Armv8Reg baseReg, Armv8Operand offset, boolean is32Bit) {
        super(null, new ArrayList<>(Arrays.asList(storeReg, baseReg, offset)));
        this.is32Bit = is32Bit;
    }

    @Override
    public String toString() {
        String storeInstr = is32Bit ? "str\ts" : "str\td";
        String regPrefix = getOperands().get(0).toString().substring(0, 1);
        String regSuffix = getOperands().get(0).toString().substring(1);
        
        // 如果这是已经有正确前缀的寄存器则直接使用，否则调整
        String srcReg = (regPrefix.equals(is32Bit ? "s" : "d")) ? 
            getOperands().get(0).toString() : 
            (is32Bit ? "s" : "d") + regSuffix;
        
        if (getOperands().get(2) instanceof Armv8Imm && ((Armv8Imm)(getOperands().get(2))).getValue() == 0) {
            return storeInstr + srcReg.substring(1) + ",\t[" + getOperands().get(1) + "]";
        } else {
            return storeInstr + srcReg.substring(1) + ",\t[" + getOperands().get(1) + ", " + getOperands().get(2) + "]";
        }
    }
} 
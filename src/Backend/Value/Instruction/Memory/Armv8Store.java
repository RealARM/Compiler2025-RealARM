package Backend.Value.Instruction.Memory;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Base.Armv8Operand;
import Backend.Value.Operand.Constant.Armv8Imm;
import Backend.Value.Operand.Register.Armv8Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class Armv8Store extends Armv8Instruction {
    
    public Armv8Store(Armv8Reg storeReg, Armv8Reg baseReg, Armv8Operand offset) {
        super(null, new ArrayList<>(Arrays.asList(storeReg, baseReg, offset)));
    }

    @Override
    public String toString() {
        String storeInstr = "str\t";
        
        // 直接使用寄存器自己的toString
        String srcReg = getOperands().get(0).toString();
        
        if (getOperands().get(2) instanceof Armv8Imm && ((Armv8Imm)(getOperands().get(2))).getValue() == 0) {
            return storeInstr + srcReg + ",\t[" + getOperands().get(1) + "]";
        } else {
            return storeInstr + srcReg + ",\t[" + getOperands().get(1) + ", " + getOperands().get(2) + "]";
        }
    }
} 
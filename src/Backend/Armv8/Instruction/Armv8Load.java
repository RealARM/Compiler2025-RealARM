package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Imm;
import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;
import Backend.Armv8.Operand.Armv8VirReg;

import java.util.ArrayList;
import java.util.Arrays;

public class Armv8Load extends Armv8Instruction {
    
    public Armv8Load(Armv8Reg baseReg, Armv8Operand offset, Armv8Reg defReg) {
        super(defReg, new ArrayList<>(Arrays.asList(baseReg, offset)));
    }

    @Override
    public String toString() {
        String loadInstr = "ldr\t";
        
        if (getOperands().get(1) instanceof Armv8Imm && ((Armv8Imm)(getOperands().get(1))).getValue() == 0) {
            return loadInstr + getDefReg() + ",\t[" + getOperands().get(0) +  "]";
        } else {
            return loadInstr + getDefReg() + ",\t[" + getOperands().get(0) + ", " + getOperands().get(1) + "]";
        }
    }
} 
package Backend.Value.Instruction.Memory;

import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Base.Armv8Operand;
import Backend.Value.Operand.Constant.Armv8Imm;
import Backend.Value.Operand.Register.Armv8Reg;
import Backend.Value.Operand.Register.Armv8VirReg;

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
package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Imm;
import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class Armv8Load extends Armv8Instruction {
    private final boolean is32Bit; // 是否是32位(w)加载而非64位(x)
    
    public Armv8Load(Armv8Reg baseReg, Armv8Operand offset, Armv8Reg defReg, boolean is32Bit) {
        super(defReg, new ArrayList<>(Arrays.asList(baseReg, offset)));
        this.is32Bit = is32Bit;
    }

    @Override
    public String toString() {
        String loadInstr = is32Bit ? "ldr\tw" : "ldr\tx";
        
        if (getOperands().get(1) instanceof Armv8Imm && ((Armv8Imm)(getOperands().get(1))).getValue() == 0) {
            return loadInstr + getDefReg().toString().substring(1) + ",\t[" + getOperands().get(0) +  "]";
        } else {
            return loadInstr + getDefReg().toString().substring(1) + ",\t[" + getOperands().get(0) + ", " + getOperands().get(1) + "]";
        }
    }
} 
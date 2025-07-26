package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Reg;
import Backend.Armv8.tools.Armv8Tools;

import java.util.ArrayList;

/**
 * Represents an ARMv8 CSET instruction
 * CSET sets a register to 1 if the condition is true, otherwise 0
 * Example: cset w0, eq   # Set w0 to 1 if the Z flag is set, otherwise 0
 */
public class Armv8Cset extends Armv8Instruction {
    private Armv8Reg destReg;
    private Armv8Tools.CondType condType;

    /**
     * Creates a new CSET instruction
     * @param destReg The destination register
     * @param condType The condition type
     */
    public Armv8Cset(Armv8Reg destReg, Armv8Tools.CondType condType) {
        super(destReg, new ArrayList<>());  // CSET has no operands, but we need an empty ArrayList
        this.destReg = destReg;
        this.condType = condType;
    }

    public Armv8Reg getDestReg() {
        return destReg;
    }

    public Armv8Tools.CondType getCondType() {
        return condType;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("cset ");
        
        sb.append(destReg.toString());
        
        // Append condition
        sb.append(", ");
        sb.append(Armv8Tools.getCondString(condType));
        
        return sb.toString();
    }
} 
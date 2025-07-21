package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Represents an ARMv8 unary operation instruction
 * Examples:
 * - NEG w0, w1    (negation)
 * - MVN w0, w1    (bitwise NOT)
 * - FNEG s0, s1   (floating point negation)
 */
public class Armv8Unary extends Armv8Instruction {
    private Armv8Reg srcReg;
    private Armv8Reg destReg;
    private Armv8UnaryType unaryType;
    private boolean is32Bit;

    public enum Armv8UnaryType {
        neg,  // Negation: NEG
        mvn,  // Bitwise NOT: MVN
        fneg  // Floating point negation: FNEG
    }

    public Armv8Unary(Armv8Reg srcReg, Armv8Reg destReg, Armv8UnaryType unaryType, boolean is32Bit) {
        super(destReg, new ArrayList<>(Arrays.asList(srcReg)));
        this.srcReg = srcReg;
        this.destReg = destReg;
        this.unaryType = unaryType;
        this.is32Bit = is32Bit;
    }

    public Armv8Reg getSrcReg() {
        return srcReg;
    }

    public Armv8Reg getDestReg() {
        return destReg;
    }

    public Armv8UnaryType getUnaryType() {
        return unaryType;
    }

    public boolean is32Bit() {
        return is32Bit;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        // Append the instruction mnemonic based on type and bit-width
        switch (unaryType) {
            case neg:
                sb.append(is32Bit ? "neg w" : "neg x");
                break;
            case mvn:
                sb.append(is32Bit ? "mvn w" : "mvn x");
                break;
            case fneg:
                sb.append("fneg s");  // Floating point always uses s registers
                break;
            default:
                sb.append("UNKNOWN_UNARY ");
                break;
        }
        
        // Append destination register
        sb.append(destReg.getRegNum()).append(", ");
        
        // Append source register with appropriate prefix
        if (unaryType == Armv8UnaryType.fneg) {
            sb.append("s");
        } else {
            sb.append(is32Bit ? "w" : "x");
        }
        sb.append(srcReg.getRegNum());
        
        return sb.toString();
    }
} 
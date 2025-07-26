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

    public enum Armv8UnaryType {
        neg,  // Negation: NEG
        mvn,  // Bitwise NOT: MVN
        fneg  // Floating point negation: FNEG
    }

    public Armv8Unary(Armv8Reg srcReg, Armv8Reg destReg, Armv8UnaryType unaryType) {
        super(destReg, new ArrayList<>(Arrays.asList(srcReg)));
        this.srcReg = srcReg;
        this.destReg = destReg;
        this.unaryType = unaryType;
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        // Append the instruction mnemonic based on type
        switch (unaryType) {
            case neg:
                sb.append("neg ");
                break;
            case mvn:
                sb.append("mvn ");
                break;
            case fneg:
                sb.append("fneg ");
                break;
            default:
                sb.append("UNKNOWN_UNARY ");
                break;
        }
        
        // 直接使用寄存器的toString方法
        sb.append(destReg).append(", ").append(srcReg);
        
        return sb.toString();
    }
} 
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
                // ARM64中，NEG指令实际上是SUB指令的特殊形式: sub dest, xzr, src
                // 但我们直接使用NEG助记符，汇编器会处理
                sb.append("neg\t");
                break;
            case mvn:
                sb.append("mvn\t");
                break;
            case fneg:
                sb.append("fneg\t");
                break;
            default:
                sb.append("UNKNOWN_UNARY\t");
                break;
        }
        
        // 使用父类方法获取寄存器，确保获取到寄存器分配后的物理寄存器
        sb.append(getDefReg()).append(", ").append(getOperands().get(0));
        
        return sb.toString();
    }
} 
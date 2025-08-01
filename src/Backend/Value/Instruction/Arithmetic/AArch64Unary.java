package Backend.Value.Instruction.Arithmetic;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Represents an ARMv8 unary operation instruction
 * Examples:
 * - NEG w0, w1    (negation)
 * - MVN w0, w1    (bitwise NOT)
 * - FNEG s0, s1   (floating point negation)
 */
public class AArch64Unary extends AArch64Instruction {
    private AArch64Reg srcReg;
    private AArch64Reg destReg;
    private AArch64UnaryType unaryType;

    public enum AArch64UnaryType {
        neg,  // Negation: NEG
        mvn,  // Bitwise NOT: MVN
        fneg  // Floating point negation: FNEG
    }

    public AArch64Unary(AArch64Reg srcReg, AArch64Reg destReg, AArch64UnaryType unaryType) {
        super(destReg, new ArrayList<>(Arrays.asList(srcReg)));
        this.srcReg = srcReg;
        this.destReg = destReg;
        this.unaryType = unaryType;
    }

    public AArch64Reg getSrcReg() {
        return srcReg;
    }

    public AArch64Reg getDestReg() {
        return destReg;
    }

    public AArch64UnaryType getUnaryType() {
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
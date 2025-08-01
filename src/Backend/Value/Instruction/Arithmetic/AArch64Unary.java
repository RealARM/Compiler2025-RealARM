package Backend.Value.Instruction.Arithmetic;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Unary extends AArch64Instruction {
    private AArch64Reg srcReg;
    private AArch64Reg destReg;
    private AArch64UnaryType unaryType;

    public enum AArch64UnaryType {
        neg,
        mvn,
        fneg
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
        
        switch (unaryType) {
            case neg:
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
        
        sb.append(getDefReg()).append(", ").append(getOperands().get(0));
        
        return sb.toString();
    }
} 
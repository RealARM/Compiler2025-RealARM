package Backend.Value.Instruction.Arithmetic;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Register.AArch64FPUReg;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;

public class AArch64FBinary extends AArch64Instruction {
    
    public enum AArch64FBinaryType {
        fadd,
        fsub,
        fmul,
        fdiv,
        fnmul
    }
    
    private AArch64FBinaryType type;
    
    public AArch64FBinary(ArrayList<AArch64Operand> operands, AArch64Reg dest, AArch64FBinaryType type) {
        super(dest, operands);
        this.type = type;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name());
        sb.append("\t").append(getDefReg()).append(", ");
        
        ArrayList<AArch64Operand> operandList = getOperands();
        if (operandList.size() >= 2) {
            sb.append(operandList.get(0)).append(", ").append(operandList.get(1));
        }
        
        return sb.toString();
    }
} 
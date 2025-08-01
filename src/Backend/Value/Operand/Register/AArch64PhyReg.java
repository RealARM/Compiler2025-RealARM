package Backend.Value.Operand.Register;

public class AArch64PhyReg extends AArch64Reg {  
    public boolean canBeReorder() {
        return true;
    }
    
    @Override
    public String toString() {
        return "PhyReg";
    }
} 
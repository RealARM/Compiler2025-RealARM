package Backend.Armv8.Operand;

public class Armv8PhyReg extends Armv8Reg {  
    public boolean canBeReorder() {
        return true;
    }
    
    @Override
    public String toString() {
        return "PhyReg";
    }
} 
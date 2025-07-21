package Backend.Armv8.Operand;

public class Armv8PhyReg extends Armv8Reg {
    
    public Armv8PhyReg(int regNum) {
        super(regNum, true);
    }
    
    public boolean canBeReorder() {
        return true;
    }
    
    @Override
    public String toString() {
        return super.toString();
    }
} 
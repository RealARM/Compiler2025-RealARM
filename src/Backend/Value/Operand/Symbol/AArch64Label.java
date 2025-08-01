package Backend.Value.Operand.Symbol;

import Backend.Value.Base.AArch64Operand;

public class AArch64Label extends AArch64Operand {
    private String labelName;
    
    public AArch64Label(String labelName) {
        this.labelName = labelName;
    }
    
    public String getLabelName() {
        return labelName;
    }
    
    @Override
    public String toString() {
        return labelName;
    }
} 
package Backend.Armv8.Operand;

public class Armv8Label extends Armv8Operand {
    private String labelName;
    
    public Armv8Label(String labelName) {
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
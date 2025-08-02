package MiddleEnd.IR.Type;

public class LabelType extends Type {
    public static final LabelType LABEL = new LabelType();
    
    private LabelType() {
    }
    
    @Override
    public int getSize() {
        return 0; 
    }
    
    @Override
    public String toString() {
        return "label";
    }
    
    @Override
    public boolean equals(Object obj) {
        return obj instanceof LabelType;
    }
    
    @Override
    public int hashCode() {
        return LabelType.class.hashCode();
    }
} 
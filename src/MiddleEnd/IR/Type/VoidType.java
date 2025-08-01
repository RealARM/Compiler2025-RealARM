package MiddleEnd.IR.Type;

public class VoidType extends Type {
    public static final VoidType VOID = new VoidType();
    
    private VoidType() {
    }
    
    @Override
    public int getSize() {
        return 0; 
    }
    
    @Override
    public String toString() {
        return "void";
    }
    
    @Override
    public boolean equals(Object obj) {
        return obj instanceof VoidType;
    }
    
    @Override
    public int hashCode() {
        return VoidType.class.hashCode();
    }
} 
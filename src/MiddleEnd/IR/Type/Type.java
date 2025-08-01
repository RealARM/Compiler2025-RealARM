package MiddleEnd.IR.Type;

public abstract class Type {
    public boolean isIntegerType() {
        return this instanceof IntegerType;
    }
    
    public boolean isFloatType() {
        return this instanceof FloatType;
    }
    
    public boolean isVoidType() {
        return this instanceof VoidType;
    }
    
    public boolean isPointerType() {
        return this instanceof PointerType;
    }
    
    public abstract int getSize();
    
    @Override
    public abstract String toString();
} 
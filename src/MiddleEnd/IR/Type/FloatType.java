package MiddleEnd.IR.Type;

public class FloatType extends Type {
    public static final FloatType F32 = new FloatType(32);
    public static final FloatType F64 = new FloatType(64);
    
    private final int bitWidth; 
    
    public FloatType(int bitWidth) {
        if (bitWidth != 32 && bitWidth != 64) { 
            throw new IllegalArgumentException("浮点类型位宽只能是32或64");
        }
        this.bitWidth = bitWidth;
    }
    
    public int getBitWidth() {
        return bitWidth;
    }
    
    @Override
    public int getSize() {
        return bitWidth / 8;
    }
    
    @Override
    public String toString() {
        // return bitWidth == 32 ? "float" : "double";
        return "float";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FloatType)) return false;
        FloatType that = (FloatType) obj;
        return bitWidth == that.bitWidth;
    }
    
    @Override
    public int hashCode() {
        return Integer.hashCode(bitWidth);
    }
} 
package MiddleEnd.IR.Type;

public class IntegerType extends Type {
    private final int bitWidth; 
    
    public static final IntegerType I32 = new IntegerType(32);
    public static final IntegerType I1 = new IntegerType(1);
    public static final IntegerType I8 = new IntegerType(8);
    
    public IntegerType(int bitWidth) {
        this.bitWidth = bitWidth;
    }
    
    public int getBitWidth() {
        return bitWidth;
    }
    
    @Override
    public int getSize() {
        return (bitWidth + 7) / 8; 
    }
    
    @Override
    public String toString() {
        return "i" + bitWidth;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof IntegerType)) return false;
        IntegerType that = (IntegerType) obj;
        return bitWidth == that.bitWidth;
    }
    
    @Override
    public int hashCode() {
        return Integer.hashCode(bitWidth);
    }
} 
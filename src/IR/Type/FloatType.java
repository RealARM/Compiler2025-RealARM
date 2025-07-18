package IR.Type;

/**
 * IR浮点类型
 */
public class FloatType extends Type {
    // 32位浮点数类型（单精度）
    public static final FloatType F32 = new FloatType(32);
    // 64位浮点数类型（双精度）
    public static final FloatType F64 = new FloatType(64);
    
    private final int bitWidth; // 位宽
    
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
        return bitWidth == 32 ? "float" : "double";
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
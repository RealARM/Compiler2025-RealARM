package IR.Type;

/**
 * IR整型类型
 */
public class IntegerType extends Type {
    private final int bitWidth; // 位宽，例如32表示32位整数
    
    // 常用的32位整数类型
    public static final IntegerType I32 = new IntegerType(32);
    // 常用的1位整数类型（用于布尔值）
    public static final IntegerType I1 = new IntegerType(1);
    // 常用的8位整数类型（字符）
    public static final IntegerType I8 = new IntegerType(8);
    
    public IntegerType(int bitWidth) {
        this.bitWidth = bitWidth;
    }
    
    public int getBitWidth() {
        return bitWidth;
    }
    
    @Override
    public int getSize() {
        return (bitWidth + 7) / 8; // 向上取整为字节数
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
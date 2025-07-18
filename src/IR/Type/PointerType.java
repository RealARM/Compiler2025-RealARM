package IR.Type;

/**
 * IR指针类型
 */
public class PointerType extends Type {
    private final Type elementType; // 指针指向的元素类型
    
    // 常用的指针类型
    public static final PointerType INT32_PTR = new PointerType(IntegerType.I32);
    public static final PointerType FLOAT32_PTR = new PointerType(FloatType.F32);
    
    public PointerType(Type elementType) {
        this.elementType = elementType;
    }
    
    /**
     * 获取指针指向的元素类型
     */
    public Type getElementType() {
        return elementType;
    }
    
    @Override
    public int getSize() {
        return 8; // 指针大小为8字节（64位架构）
    }
    
    @Override
    public String toString() {
        return elementType.toString() + "*";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PointerType)) return false;
        PointerType that = (PointerType) obj;
        return elementType.equals(that.elementType);
    }
    
    @Override
    public int hashCode() {
        return 31 * elementType.hashCode() + PointerType.class.hashCode();
    }
} 
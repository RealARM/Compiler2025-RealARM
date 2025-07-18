package IR.Type;

/**
 * IR void类型，表示无返回值
 */
public class VoidType extends Type {
    // 单例模式
    public static final VoidType VOID = new VoidType();
    
    private VoidType() {
        // 私有构造函数，防止外部实例化
    }
    
    @Override
    public int getSize() {
        return 0; // void类型没有大小
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
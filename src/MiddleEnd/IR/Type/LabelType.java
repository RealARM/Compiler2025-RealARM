package MiddleEnd.IR.Type;

/**
 * 表示标签类型，用于基本块
 */
public class LabelType extends Type {
    // 单例模式
    public static final LabelType LABEL = new LabelType();
    
    private LabelType() {
        // 私有构造函数，防止外部实例化
    }
    
    @Override
    public int getSize() {
        return 0; // 标签不占用空间
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
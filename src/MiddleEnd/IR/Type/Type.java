package MiddleEnd.IR.Type;

/**
 * IR类型系统的基类，所有具体类型都继承自此类
 */
public abstract class Type {
    /**
     * 判断是否为整数类型
     */
    public boolean isIntegerType() {
        return this instanceof IntegerType;
    }
    
    /**
     * 判断是否为浮点类型
     */
    public boolean isFloatType() {
        return this instanceof FloatType;
    }
    
    /**
     * 判断是否为void类型
     */
    public boolean isVoidType() {
        return this instanceof VoidType;
    }
    
    /**
     * 判断是否为指针类型
     */
    public boolean isPointerType() {
        return this instanceof PointerType;
    }
    
    /**
     * 获取类型的位宽（字节数）
     */
    public abstract int getSize();
    
    /**
     * 获取类型的字符串表示
     */
    @Override
    public abstract String toString();
} 
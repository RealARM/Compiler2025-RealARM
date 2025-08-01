package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.IntegerType;

/**
 * 表示整型常量值
 */
public class ConstantInt extends Constant {
    private final int value;  // 整型常量值
    
    public ConstantInt(int value) {
        this(value, IntegerType.I32);
    }
    
    public ConstantInt(int value, IntegerType type) {
        super(String.valueOf(value), type);
        this.value = value;
    }
    
    /**
     * 获取常量值
     */
    public int getValue() {
        return value;
    }
    
    /**
     * 创建值为0的常量
     */
    public static ConstantInt getZero(IntegerType type) {
        return new ConstantInt(0, type);
    }
    
    /**
     * 创建值为1的常量
     */
    public static ConstantInt getOne(IntegerType type) {
        return new ConstantInt(1, type);
    }
    
    /**
     * 创建布尔常量
     */
    public static ConstantInt getBool(boolean value) {
        return new ConstantInt(value ? 1 : 0, IntegerType.I1);
    }
    
    @Override
    public String toString() {
        return String.valueOf(value);
    }
} 
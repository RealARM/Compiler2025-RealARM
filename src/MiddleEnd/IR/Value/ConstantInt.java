package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.IntegerType;

public class ConstantInt extends Constant {
    private final int value;
    
    public ConstantInt(int value) {
        this(value, IntegerType.I32);
    }
    
    public ConstantInt(int value, IntegerType type) {
        super(String.valueOf(value), type);
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
    
    public static ConstantInt getZero(IntegerType type) {
        return new ConstantInt(0, type);
    }
    
    public static ConstantInt getOne(IntegerType type) {
        return new ConstantInt(1, type);
    }
    
    public static ConstantInt getBool(boolean value) {
        return new ConstantInt(value ? 1 : 0, IntegerType.I1);
    }
    
    @Override
    public String toString() {
        return String.valueOf(value);
    }
} 
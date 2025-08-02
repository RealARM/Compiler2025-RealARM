package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.FloatType;

public class ConstantFloat extends Constant {
    private final double value;
    
    public ConstantFloat(double value) {
        super(String.valueOf(value), FloatType.F64);
        this.value = value;
    }
    
    public double getValue() {
        return value;
    }
    
    public static ConstantFloat getZero() {
        return new ConstantFloat(0.0);
    }
    
    public static ConstantFloat getOne() {
        return new ConstantFloat(1.0);
    }
    
    @Override
    public String toString() {
        if (Double.isNaN(value)) {
            return "NaN";
        } else if (Double.isInfinite(value)) {
            return value > 0 ? "Inf" : "-Inf";
        }
        
        // 使用Double.doubleToLongBits来获取64位表示
        return "0x" + Long.toHexString(Double.doubleToLongBits((float) value));
    }
} 
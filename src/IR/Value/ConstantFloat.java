package IR.Value;

import IR.Type.FloatType;

/**
 * 表示浮点常量值
 */
public class ConstantFloat extends Constant {
    private final double value;  // 浮点常量值
    
    public ConstantFloat(double value) {
        super(String.valueOf(value), FloatType.F64);
        this.value = value;
    }
    
    /**
     * 获取常量值
     */
    public double getValue() {
        return value;
    }
    
    /**
     * 创建值为0.0的常量
     */
    public static ConstantFloat getZero() {
        return new ConstantFloat(0.0);
    }
    
    /**
     * 创建值为1.0的常量
     */
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
        return "0x" + Long.toHexString(Double.doubleToLongBits(value));
    }
} 
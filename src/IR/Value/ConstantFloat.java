package IR.Value;

import IR.Type.FloatType;

/**
 * 表示浮点常量值
 */
public class ConstantFloat extends Constant {
    private final float value;  // 浮点常量值
    
    public ConstantFloat(float value) {
        super(String.valueOf(value), FloatType.F32);
        this.value = value;
    }
    
    /**
     * 获取常量值
     */
    public float getValue() {
        return value;
    }
    
    /**
     * 创建值为0.0的常量
     */
    public static ConstantFloat getZero() {
        return new ConstantFloat(0.0f);
    }
    
    /**
     * 创建值为1.0的常量
     */
    public static ConstantFloat getOne() {
        return new ConstantFloat(1.0f);
    }
    
    @Override
    public String toString() {
        if (Float.isNaN(value)) {
            return "NaN";
        } else if (Float.isInfinite(value)) {
            return value > 0 ? "Inf" : "-Inf";
        }
        
        // 如果是整数值（如1.0），则省略小数部分
        if (value == (int)value) {
            return (int)value + ".0";
        }
        return String.valueOf(value);
    }
} 
package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.FloatType;

/**
 * 单精度浮点常量。
 * 内部以 IEEE-754 组件形式存储：sign（1bit）、exponent（unbiased 8bit）、mantissa（23bit）。
 */
public class ConstantFloat extends Constant {
    // 0 – 正数，1 – 负数
    private final int sign;
    // 去偏置后的实际指数值（范围 -127…128）
    private final int exponent;
    // 23 位尾数（不含隐式最高位）
    private final int mantissa;

    public ConstantFloat(float value) {
        super(String.valueOf(value), FloatType.F32);
        int bits = Float.floatToIntBits(value);
        this.sign = (bits >>> 31) & 0x1;
        this.exponent = ((bits >>> 23) & 0xFF) - 127; // 去掉偏置
        this.mantissa = bits & 0x7FFFFF;
    }

    public ConstantFloat(double value) {
        this((float) value);
    }

    public int getSign() {
        return sign;
    }

    public int getExponent() {
        return exponent;
    }

    public int getMantissa() {
        return mantissa;
    }

    public int toIEEEBits() {
        int biasedExp = exponent + 127;
        return (sign << 31) | (biasedExp << 23) | (mantissa & 0x7FFFFF);
    }

    public float toFloat() {
        return Float.intBitsToFloat(toIEEEBits());
    }

    public double getValue() {
        return (double) toFloat();
    }

    public static ConstantFloat getZero() {
        return new ConstantFloat(0.0f);
    }

    public static ConstantFloat getOne() {
        return new ConstantFloat(1.0f);
    }

    @Override
    public String toString() {
        return "0x" + Integer.toHexString(toIEEEBits());
    }
}

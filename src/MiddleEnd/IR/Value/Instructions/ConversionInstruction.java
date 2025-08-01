package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;

/**
 * 类型转换指令，用于不同类型之间的转换
 * 对应示例代码中的ConversionInst
 */
public class ConversionInstruction extends Instruction {
    private final OpCode conversionType; // 转换类型，如Ftoi, Itof, Zext等
    
    /**
     * 创建一个类型转换指令
     */
    public ConversionInstruction(Value source, Type targetType, OpCode conversionType, String name) {
        super(name, targetType);
        this.conversionType = conversionType;
        
        // 添加源值作为操作数
        addOperand(source);
    }
    
    /**
     * 获取源值
     */
    public Value getSource() {
        return getOperand(0);
    }
    
    /**
     * 获取转换类型
     */
    public OpCode getConversionType() {
        return conversionType;
    }
    
    @Override
    public String getOpcodeName() {
        return conversionType.getName();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = ").append(getOpcodeName()).append(" ");
        sb.append(getSource().getType()).append(" ").append(getSource().getName());
        sb.append(" to ").append(getType());
        return sb.toString();
    }
    
    /**
     * 判断是否为整数到浮点数的转换
     */
    public boolean isIntToFloat() {
        return conversionType == OpCode.SITOFP || conversionType == OpCode.UITOFP;
    }
    
    /**
     * 判断是否为浮点数到整数的转换
     */
    public boolean isFloatToInt() {
        return conversionType == OpCode.FPTOSI || conversionType == OpCode.FPTOUI;
    }
    
    /**
     * 判断是否为位扩展操作
     */
    public boolean isExtension() {
        return conversionType == OpCode.ZEXT || conversionType == OpCode.SEXT;
    }
    
    /**
     * 判断是否为位截断操作
     */
    public boolean isTruncation() {
        return conversionType == OpCode.TRUNC;
    }
    
    /**
     * 判断是否为位类型转换
     */
    public boolean isBitCast() {
        return conversionType == OpCode.BITCAST;
    }
} 
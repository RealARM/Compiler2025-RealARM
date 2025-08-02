package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;

public class ConversionInstruction extends Instruction {
    private final OpCode conversionType;
    
    public ConversionInstruction(Value source, Type targetType, OpCode conversionType, String name) {
        super(name, targetType);
        this.conversionType = conversionType;
        
        addOperand(source);
    }
    
    public Value getSource() {
        return getOperand(0);
    }
    
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
    
    public boolean isIntToFloat() {
        return conversionType == OpCode.SITOFP || conversionType == OpCode.UITOFP;
    }
    
    public boolean isFloatToInt() {
        return conversionType == OpCode.FPTOSI || conversionType == OpCode.FPTOUI;
    }
    
    public boolean isExtension() {
        return conversionType == OpCode.ZEXT || conversionType == OpCode.SEXT;
    }
    
    public boolean isTruncation() {
        return conversionType == OpCode.TRUNC;
    }
    
    public boolean isBitCast() {
        return conversionType == OpCode.BITCAST;
    }
} 
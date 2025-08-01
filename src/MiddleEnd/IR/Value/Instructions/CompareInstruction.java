package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.Value.Value;

public class CompareInstruction extends Instruction {
    private final OpCode compareType;
    private final OpCode predicate;
    
    private static int nameCounter = 0;
    
    public CompareInstruction(OpCode compareType, OpCode predicate, Value left, Value right) {
        super(compareType.getName() + "_" + predicate.getName() + "_result_" + nameCounter++, IntegerType.I1);
        
        if (compareType != OpCode.ICMP && compareType != OpCode.FCMP) {
            throw new IllegalArgumentException("比较指令类型必须是icmp或fcmp");
        }
        
        this.compareType = compareType;
        this.predicate = predicate;
        
        addOperand(left);
        addOperand(right);
    }
    
    public OpCode getCompareType() {
        return compareType;
    }
    
    public OpCode getPredicate() {
        return predicate;
    }
    
    public Value getLeft() {
        return getOperand(0);
    }
    
    public Value getRight() {
        return getOperand(1);
    }
    
    public boolean isIntegerCompare() {
        return compareType == OpCode.ICMP;
    }
    
    public boolean isFloatCompare() {
        return compareType == OpCode.FCMP;
    }
    
    public boolean isEqualityCompare() {
        return predicate == OpCode.EQ || predicate == OpCode.NE || 
               predicate == OpCode.UEQ || predicate == OpCode.UNE;
    }
    
    public boolean isRelationalCompare() {
        return predicate == OpCode.SGT || predicate == OpCode.SGE || 
               predicate == OpCode.SLT || predicate == OpCode.SLE ||
               predicate == OpCode.UGT || predicate == OpCode.UGE || 
               predicate == OpCode.ULT || predicate == OpCode.ULE;
    }
    
    @Override
    public String getOpcodeName() {
        return compareType.getName();
    }
    
    @Override
    public String toString() {
        return getName() + " = " + getOpcodeName() + " " + predicate.getName() + " " + 
               getLeft().getType() + " " + getLeft().getName() + ", " + getRight().getName();
    }
} 
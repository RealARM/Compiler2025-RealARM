package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.IntegerType;
import IR.Value.Value;

/**
 * 比较指令，用于比较两个值
 */
public class CompareInstruction extends Instruction {
    private final OpCode compareType; // 比较类型：icmp或fcmp
    private final OpCode predicate;   // 比较谓词：eq, ne, sgt等
    
    // 为比较指令名称添加计数器
    private static int nameCounter = 0;
    
    /**
     * 创建一个比较指令
     */
    public CompareInstruction(OpCode compareType, OpCode predicate, Value left, Value right) {
        super(compareType.getName() + "_" + predicate.getName() + "_result_" + nameCounter++, IntegerType.I1); // 比较结果为1位整数（布尔值）
        
        if (compareType != OpCode.ICMP && compareType != OpCode.FCMP) {
            throw new IllegalArgumentException("比较指令类型必须是icmp或fcmp");
        }
        
        this.compareType = compareType;
        this.predicate = predicate;
        
        // 添加操作数
        addOperand(left);
        addOperand(right);
    }
    
    /**
     * 获取比较类型
     */
    public OpCode getCompareType() {
        return compareType;
    }
    
    /**
     * 获取比较谓词
     */
    public OpCode getPredicate() {
        return predicate;
    }
    
    /**
     * 获取左操作数
     */
    public Value getLeft() {
        return getOperand(0);
    }
    
    /**
     * 获取右操作数
     */
    public Value getRight() {
        return getOperand(1);
    }
    
    /**
     * 判断是否为整数比较
     */
    public boolean isIntegerCompare() {
        return compareType == OpCode.ICMP;
    }
    
    /**
     * 判断是否为浮点比较
     */
    public boolean isFloatCompare() {
        return compareType == OpCode.FCMP;
    }
    
    /**
     * 判断是否为等值比较（等于或不等于）
     */
    public boolean isEqualityCompare() {
        return predicate == OpCode.EQ || predicate == OpCode.NE || 
               predicate == OpCode.UEQ || predicate == OpCode.UNE;
    }
    
    /**
     * 判断是否为关系比较（大于、小于等）
     */
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
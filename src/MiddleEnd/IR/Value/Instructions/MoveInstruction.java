package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.ConstantInt;

/**
 * Move指令，用于PHI消除过程中的变量赋值
 * 内部是move操作，但toString会伪装成add指令（a = add b, 0）以便测试
 */
public class MoveInstruction extends Instruction {
    private static int nameCounter = 0;
    
    /**
     * 创建一个Move指令
     * 这个Move指令将会产生和targetName同名的结果
     * @param targetName 目标变量的名称（通常是PHI指令的名称）
     * @param targetType 目标变量的类型
     * @param source 源变量
     */
    public MoveInstruction(String targetName, Type targetType, Value source) {
        super(targetName, targetType);
        
        // 只添加源操作数
        addOperand(source);
    }
    
    /**
     * 兼容性构造函数（用于原有的PHI消除逻辑）
     * @param phiInstruction PHI指令（我们将使用它的名称和类型）
     * @param source 源变量
     */
    public MoveInstruction(PhiInstruction phiInstruction, Value source) {
        this(phiInstruction.getName(), phiInstruction.getType(), source);
    }
    
    /**
     * 获取目标名称（这个指令产生的值的名称）
     */
    public String getDestinationName() {
        return getName();
    }
    
    /**
     * 获取源操作数（赋值的右边）
     */
    public Value getSource() {
        return getOperand(0);
    }
    
    /**
     * 替换操作数
     */
    public void replaceOperand(Value oldValue, Value newValue) {
        for (int i = 0; i < getOperands().size(); i++) {
            if (getOperands().get(i) == oldValue) {
                getOperands().set(i, newValue);
                break;
            }
        }
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.MOV.getName();
    }
    
    /**
     * toString方法直接输出mov指令格式："a = mov b"
     * 既然已经退出SSA形式，不需要再伪装
     */
    @Override
    public String toString() {
        return getDestinationName() + " = mov " + getType() + " " + 
               getSource().getName();
    }
    
    /**
     * 生成指令名称
     */
    private static String generateName() {
        return "mov_" + nameCounter++;
    }
    
    /**
     * 判断是否为自身赋值（a = a）
     */
    public boolean isSelfAssignment() {
        return getDestinationName().equals(getSource().getName());
    }
} 
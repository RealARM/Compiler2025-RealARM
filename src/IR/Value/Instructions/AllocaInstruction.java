package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.PointerType;
import IR.Type.Type;

/**
 * 分配栈空间指令，用于在函数栈上分配内存
 */
public class AllocaInstruction extends Instruction {
    private final Type allocatedType; // 分配的元素类型
    private final int arraySize;      // 数组大小，为0时表示单一变量
    
    /**
     * 创建一个分配单一元素的指令
     */
    public AllocaInstruction(Type elementType, String name) {
        super(name, new PointerType(elementType));
        this.allocatedType = elementType;
        this.arraySize = 0;
    }
    
    /**
     * 创建一个分配数组的指令
     */
    public AllocaInstruction(Type elementType, int arraySize, String name) {
        super(name, new PointerType(elementType));
        this.allocatedType = elementType;
        this.arraySize = arraySize;
    }
    
    /**
     * 获取分配的元素类型
     */
    public Type getAllocatedType() {
        return allocatedType;
    }
    
    /**
     * 获取数组大小
     */
    public int getArraySize() {
        return arraySize;
    }
    
    /**
     * 判断是否为数组分配
     */
    public boolean isArrayAllocation() {
        return arraySize > 0;
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.ALLOCA.getName();
    }
    
    @Override
    public String toString() {
        if (isArrayAllocation()) {
            return getName() + " = " + getOpcodeName() + " [" + arraySize + " x " + allocatedType + "]";
        } else {
            return getName() + " = " + getOpcodeName() + " " + allocatedType;
        }
    }
} 
package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.PointerType;
import IR.Type.Type;
import IR.Value.Value;

/**
 * 加载指令，用于从内存加载值
 */
public class LoadInstruction extends Instruction {
    /**
     * 创建一个加载指令
     */
    public LoadInstruction(Value pointer, String name) {
        super(name, ((PointerType) pointer.getType()).getElementType());
        
        // 检查类型
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("加载指令必须从指针类型加载");
        }
        
        // 添加操作数（指针）
        addOperand(pointer);
    }
    
    /**
     * 获取指针操作数
     */
    public Value getPointer() {
        return getOperand(0);
    }
    
    /**
     * 获取被加载元素的类型
     */
    public Type getLoadedType() {
        return getType();
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.LOAD.getName();
    }
    
    @Override
    public String toString() {
        return getName() + " = " + getOpcodeName() + " " + getType() + ", " + 
               getPointer().getType() + " " + getPointer().getName();
    }
} 
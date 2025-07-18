package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.PointerType;
import IR.Type.Type;
import IR.Type.VoidType;
import IR.Value.Value;

/**
 * 存储指令，用于将值存储到内存
 */
public class StoreInstruction extends Instruction {
    /**
     * 创建一个存储指令
     */
    public StoreInstruction(Value value, Value pointer) {
        super("store", VoidType.VOID); // 存储指令没有返回值
        
        // 检查类型
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("存储指令必须存储到指针类型");
        }
        
        // 添加操作数（值和指针）
        addOperand(value);
        addOperand(pointer);
    }
    
    /**
     * 获取存储的值
     */
    public Value getValue() {
        return getOperand(0);
    }
    
    /**
     * 获取存储的目标位置（指针）
     */
    public Value getPointer() {
        return getOperand(1);
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.STORE.getName();
    }
    
    @Override
    public String toString() {
        return getOpcodeName() + " " + getValue().getType() + " " + getValue().getName() + ", " + 
               getPointer().getType() + " " + getPointer().getName();
    }
} 
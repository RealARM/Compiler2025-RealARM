package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.PointerType;
import IR.Type.Type;
import IR.Value.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 获取元素指针指令，用于计算数组或结构体元素的地址
 * 对应示例代码中的PtrInst
 */
public class GetElementPtrInstruction extends Instruction {
    /**
     * 创建一个简单的指针偏移指令（类似于示例中的PtrInst）
     */
    public GetElementPtrInstruction(Value pointer, Value offset, String name) {
        super(name, pointer.getType());
        
        // 检查指针类型
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("GetElementPtr的第一个参数必须是指针类型");
        }
        
        // 添加操作数
        addOperand(pointer);
        addOperand(offset);
    }
    
    /**
     * 创建一个多维索引的GEP指令
     */
    public GetElementPtrInstruction(Value pointer, List<Value> indices, String name) {
        super(name, pointer.getType());
        
        // 检查指针类型
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("GetElementPtr的第一个参数必须是指针类型");
        }
        
        // 添加基地址
        addOperand(pointer);
        
        // 添加所有索引
        for (Value index : indices) {
            addOperand(index);
        }
    }
    
    /**
     * 获取基地址
     */
    public Value getPointer() {
        return getOperand(0);
    }
    
    /**
     * 获取偏移量（对于简单版本）
     */
    public Value getOffset() {
        return getOperand(1);
    }
    
    /**
     * 获取所有索引
     */
    public List<Value> getIndices() {
        List<Value> indices = new ArrayList<>();
        for (int i = 1; i < getOperandCount(); i++) {
            indices.add(getOperand(i));
        }
        return indices;
    }
    
    /**
     * 获取元素类型
     */
    public Type getElementType() {
        return ((PointerType) getPointer().getType()).getElementType();
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.GETELEMENTPTR.getName();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = ").append(getOpcodeName()).append(" ");
        sb.append(getElementType()).append(", ");
        sb.append(getPointer().getType()).append(" ").append(getPointer().getName());
        
        for (Value index : getIndices()) {
            sb.append(", ").append(index.getType()).append(" ").append(index.getName());
        }
        
        return sb.toString();
    }
} 
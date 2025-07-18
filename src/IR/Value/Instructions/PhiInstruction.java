package IR.Value.Instructions;

import IR.Type.Type;
import IR.Value.BasicBlock;
import IR.Value.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phi指令，用于SSA形式的变量汇合
 */
public class PhiInstruction extends Instruction {
    private final Map<BasicBlock, Value> incomingValues = new HashMap<>();
    
    /**
     * 创建一个Phi指令
     */
    public PhiInstruction(Type type, String name) {
        super(name, type);
    }
    
    /**
     * 添加一个输入值
     */
    public void addIncoming(Value value, BasicBlock block) {
        incomingValues.put(block, value);
        addOperand(value);
    }
    
    /**
     * 获取所有输入值
     */
    public Map<BasicBlock, Value> getIncomingValues() {
        return incomingValues;
    }
    
    /**
     * 获取指定基本块的输入值
     */
    public Value getIncomingValue(BasicBlock block) {
        return incomingValues.get(block);
    }
    
    /**
     * 获取所有输入基本块
     */
    public List<BasicBlock> getIncomingBlocks() {
        return new ArrayList<>(incomingValues.keySet());
    }
    
    @Override
    public String getOpcodeName() {
        return "phi";
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = ").append(getOpcodeName()).append(" ").append(getType());
        
        boolean first = true;
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            if (!first) {
                sb.append(",");
            } else {
                first = false;
            }
            sb.append(" [ ").append(entry.getValue().getName()).append(", %")
                .append(entry.getKey().getName()).append(" ]");
        }
        
        return sb.toString();
    }
} 
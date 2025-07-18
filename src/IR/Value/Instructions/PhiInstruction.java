package IR.Value.Instructions;

import IR.OpCode;
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
    
    /**
     * 修复前驱基本块列表变化后的phi指令
     * 示例代码中的fixPreBlocks
     */
    public void updatePredecessors(List<BasicBlock> oldPredecessors, List<BasicBlock> newPredecessors) {
        ArrayList<Value> values = new ArrayList<>();
        
        for (BasicBlock newPred : newPredecessors) {
            int index = oldPredecessors.indexOf(newPred);
            if (index >= 0 && index < getOperandCount()) {
                values.add(getOperand(index));
            }
        }
        
        // 清除所有操作数
        removeAllOperands();
        
        // 重新添加操作数
        for (Value value : values) {
            addOperand(value);
        }
        
        // 重建incomingValues映射
        incomingValues.clear();
        for (int i = 0; i < newPredecessors.size() && i < values.size(); i++) {
            incomingValues.put(newPredecessors.get(i), values.get(i));
        }
    }
    
    /**
     * 移除所有操作数
     */
    @Override
    public void removeAllOperands() {
        super.removeAllOperands();
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.PHI.getName();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = ").append(getOpcodeName()).append(" ");
        sb.append(getType()).append(" ");
        
        boolean first = true;
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            if (!first) {
                sb.append(", ");
            } else {
                first = false;
            }
            
            sb.append("[ ").append(entry.getValue().getName())
              .append(", %").append(entry.getKey().getName()).append(" ]");
        }
        
        return sb.toString();
    }
} 
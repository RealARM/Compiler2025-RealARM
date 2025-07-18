package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.Type;
import IR.Value.BasicBlock;
import IR.Value.Value;
import IR.Value.ConstantInt;
import IR.Type.IntegerType;

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
     * 添加一个输入值（使用字符串标识符和索引）
     * 这对于短路逻辑很有用，尤其是在块尚未连接到其前驱时
     */
    public void addIncoming(Value value, String label, int index) {
        // 先仅添加操作数，在后续处理中再关联到具体的基本块
        addOperand(value);
        
        // 记录额外信息，稍后处理
        // 注意：这里不更新incomingValues映射，因为我们还没有实际的基本块引用
    }
    
    /**
     * 添加输入值，如果块已经有一个值，则更新它
     */
    public void addOrUpdateIncoming(Value value, BasicBlock block) {
        if (incomingValues.containsKey(block)) {
            // 更新现有操作数
            int index = getIncomingBlocks().indexOf(block);
            if (index >= 0 && index < getOperandCount()) {
                setOperand(index, value);
                incomingValues.put(block, value);
            }
        } else {
            // 添加新操作数
            addIncoming(value, block);
        }
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
    
    /**
     * 验证并修复PHI节点的前驱关系
     * 确保PHI节点的前驱与基本块的实际前驱完全匹配
     */
    public void validatePredecessors() {
        if (getParent() == null) return;
        
        List<BasicBlock> actualPreds = getParent().getPredecessors();
        
        // 如果没有前驱，但有PHI节点输入，清空它们
        if (actualPreds.isEmpty() && !incomingValues.isEmpty()) {
            removeAllOperands();
            incomingValues.clear();
            return;
        }
        
        // 记录需要修复的情况
        boolean needsFix = false;
        
        // 检查所有PHI输入，确保它们都对应实际前驱
        for (BasicBlock phiPred : new ArrayList<>(incomingValues.keySet())) {
            if (!actualPreds.contains(phiPred)) {
                needsFix = true;
                break;
            }
        }
        
        // 检查所有实际前驱，确保都有PHI输入
        for (BasicBlock actualPred : actualPreds) {
            if (!incomingValues.containsKey(actualPred)) {
                needsFix = true;
                break;
            }
        }
        
        // 如果需要修复，重新构建PHI节点的输入
        if (needsFix) {
            // 创建新的PHI节点输入映射
            Map<BasicBlock, Value> newIncomingValues = new HashMap<>();
            
            // 处理每个实际前驱
            for (BasicBlock pred : actualPreds) {
                // 如果已有对应的输入，保留它
                if (incomingValues.containsKey(pred)) {
                    newIncomingValues.put(pred, incomingValues.get(pred));
                } else {
                    // 否则添加默认值
                    Value defaultValue = getType().toString().equals("i1") ? 
                        new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                    newIncomingValues.put(pred, defaultValue);
                }
            }
            
            // 清除所有原始操作数
            removeAllOperands();
            incomingValues.clear();
            
            // 添加新的输入值
            for (Map.Entry<BasicBlock, Value> entry : newIncomingValues.entrySet()) {
                addIncoming(entry.getValue(), entry.getKey());
            }
        }
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
package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.Type.IntegerType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phi指令，用于SSA形式的变量汇合
 */
public class PhiInstruction extends Instruction {
    private final Map<BasicBlock, Value> incomingValues = new LinkedHashMap<>();
    
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
        // 确保block确实是PHI所在基本块的前驱
        if (getParent() != null && !getParent().getPredecessors().contains(block)) {
            return; // 如果不是前驱块，不添加
        }
        
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
        
        // 基本检查：确保PHI节点没有空前驱
        List<BasicBlock> phiPreds = getIncomingBlocks();
        for (BasicBlock phiPred : new ArrayList<>(phiPreds)) {
            if (phiPred == null) {
                // 移除空前驱
                Value value = incomingValues.remove(phiPred);
                if (value != null) {
                    removeOperand(value);
                }
            }
        }
    }
    
    /**
     * 移除指定操作数
     */
    public void removeOperand(Value value) {
        // 查找操作数的索引
        int index = -1;
        for (int i = 0; i < getOperandCount(); i++) {
            if (getOperand(i) == value) {
                index = i;
                break;
            }
        }
        
        // 如果找到，移除它
        if (index >= 0) {
            // 移除用户关系
            value.removeUser(this);
            
            // 移除操作数
            List<Value> newOperands = new ArrayList<>();
            for (int i = 0; i < getOperandCount(); i++) {
                if (i != index) {
                    newOperands.add(getOperand(i));
                }
            }
            
            // 清除所有操作数并重新添加
            removeAllOperands();
            for (Value op : newOperands) {
                addOperand(op);
            }
        }
    }
    
    /**
     * 移除指定基本块的输入值
     */
    public void removeIncoming(BasicBlock block) {
        if (incomingValues.containsKey(block)) {
            Value value = incomingValues.remove(block);
            if (value != null) {
                removeOperand(value);
            }
        }
    }
    
    /**
     * 安全地设置Phi指令的操作数，同时更新incomingValues映射
     * 这个方法通过基本块来标识要更新的操作数，避免索引混乱
     */
    public void setOperandForBlock(BasicBlock block, Value value) {
        // 找到这个基本块在当前前驱列表中的索引
        List<BasicBlock> predecessors = getParent().getPredecessors();
        int index = predecessors.indexOf(block);
        
        if (index >= 0 && index < getOperandCount()) {
            // 更新操作数数组
            super.setOperand(index, value);
            // 更新incomingValues映射
            incomingValues.put(block, value);
        }
    }
    
    /**
     * 重写setOperand方法，确保同时更新incomingValues映射
     * 通过反向查找来确定哪个基本块对应这个操作数索引
     */
    @Override
    public void setOperand(int index, Value value) {
        // 获取旧的操作数，找到对应的基本块
        Value oldOperand = null;
        if (index >= 0 && index < getOperandCount()) {
            oldOperand = getOperand(index);
        }
        
        // 调用父类方法更新操作数数组
        super.setOperand(index, value);
        
        // 找到对应的基本块并更新incomingValues映射
        if (oldOperand != null) {
            BasicBlock correspondingBlock = null;
            // 在incomingValues中查找使用旧值的基本块
            for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
                if (entry.getValue() == oldOperand) {
                    correspondingBlock = entry.getKey();
                    break;
                }
            }
            
            // 如果找到对应的基本块，更新映射
            if (correspondingBlock != null) {
                incomingValues.put(correspondingBlock, value);
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
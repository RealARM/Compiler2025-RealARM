package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PhiInstruction extends Instruction {
    private final Map<BasicBlock, Value> incomingValues = new LinkedHashMap<>();
    
    public PhiInstruction(Type type, String name) {
        super(name, type);
    }
    
    public void addIncoming(Value value, BasicBlock block) {
        if (getParent() != null && !getParent().getPredecessors().contains(block)) {
            return;
        }
        
        incomingValues.put(block, value);
        addOperand(value);
    }
    
    public void addIncoming(Value value, String label, int index) {
        addOperand(value);
    }
    
    public void addOrUpdateIncoming(Value value, BasicBlock block) {
        if (incomingValues.containsKey(block)) {
            int index = getIncomingBlocks().indexOf(block);
            if (index >= 0 && index < getOperandCount()) {
                setOperand(index, value);
                incomingValues.put(block, value);
            }
        } else {
            addIncoming(value, block);
        }
    }
    
    public Map<BasicBlock, Value> getIncomingValues() {
        return incomingValues;
    }
    
    public Value getIncomingValue(BasicBlock block) {
        return incomingValues.get(block);
    }
    
    public List<BasicBlock> getIncomingBlocks() {
        return new ArrayList<>(incomingValues.keySet());
    }
    
    public void updatePredecessors(List<BasicBlock> oldPredecessors, List<BasicBlock> newPredecessors) {
        ArrayList<Value> values = new ArrayList<>();
        
        for (BasicBlock newPred : newPredecessors) {
            int index = oldPredecessors.indexOf(newPred);
            if (index >= 0 && index < getOperandCount()) {
                values.add(getOperand(index));
            }
        }
        
        removeAllOperands();
        
        for (Value value : values) {
            addOperand(value);
        }
        
        incomingValues.clear();
        for (int i = 0; i < newPredecessors.size() && i < values.size(); i++) {
            incomingValues.put(newPredecessors.get(i), values.get(i));
        }
    }
    
    @Override
    public void removeAllOperands() {
        super.removeAllOperands();
    }
    
    public void validatePredecessors() {
        if (getParent() == null) return;
        
        List<BasicBlock> phiPreds = getIncomingBlocks();
        for (BasicBlock phiPred : new ArrayList<>(phiPreds)) {
            if (phiPred == null) {
                Value value = incomingValues.remove(phiPred);
                if (value != null) {
                    removeOperand(value);
                }
            }
        }
    }
    
    public void removeOperand(Value value) {
        int index = -1;
        for (int i = 0; i < getOperandCount(); i++) {
            if (getOperand(i) == value) {
                index = i;
                break;
            }
        }
        
        if (index >= 0) {
            value.removeUser(this);
            
            List<Value> newOperands = new ArrayList<>();
            for (int i = 0; i < getOperandCount(); i++) {
                if (i != index) {
                    newOperands.add(getOperand(i));
                }
            }
            
            removeAllOperands();
            for (Value op : newOperands) {
                addOperand(op);
            }
        }
    }
    
    public void removeIncoming(BasicBlock block) {
        if (incomingValues.containsKey(block)) {
            Value value = incomingValues.remove(block);
            if (value != null) {
                removeOperand(value);
            }
        }
    }
    
    public void setOperandForBlock(BasicBlock block, Value value) {
        List<BasicBlock> predecessors = getParent().getPredecessors();
        int index = predecessors.indexOf(block);
        
        if (index >= 0 && index < getOperandCount()) {
            super.setOperand(index, value);
            incomingValues.put(block, value);
        }
    }
    
    @Override
    public void setOperand(int index, Value value) {
        Value oldOperand = null;
        if (index >= 0 && index < getOperandCount()) {
            oldOperand = getOperand(index);
        }
        
        super.setOperand(index, value);
        
        if (oldOperand != null) {
            BasicBlock correspondingBlock = null;
            for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
                if (entry.getValue() == oldOperand) {
                    correspondingBlock = entry.getKey();
                    break;
                }
            }
            
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
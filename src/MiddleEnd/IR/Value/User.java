package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Use;

import java.util.ArrayList;
import java.util.List;

public class User extends Value {
    private List<Use> operands = new ArrayList<>();
    
    public User(String name, Type type) {
        super(name, type);
    }
    
    public int getOperandCount() {
        return operands.size();
    }
    
    public Value getOperand(int index) {
        return operands.get(index).getValue();
    }
    
    public List<Value> getOperands() {
        List<Value> values = new ArrayList<>();
        for (Use use : operands) {
            values.add(use.getValue());
        }
        return values;
    }
    
    public void setOperand(int index, Value value) {
        while (operands.size() <= index) {
            operands.add(null);
        }
        
        Use oldUse = operands.get(index);
        if (oldUse != null && oldUse.getValue() != null) {
            oldUse.getValue().removeUser(this);
        }
        
        Use newUse = new Use(value, this);
        operands.set(index, newUse);
        if (value != null) {
            value.addUser(this);
        }
    }
    
    public void addOperand(Value value) {
        setOperand(operands.size(), value);
    }
    
    public void replaceAllUsesWith(Value oldValue, Value newValue) {
        for (int i = 0; i < operands.size(); i++) {
            if (getOperand(i) == oldValue) {
                setOperand(i, newValue);
            }
        }
    }
    
    public void removeAllOperands() {
        for (Use use : new ArrayList<>(operands)) {
            if (use != null && use.getValue() != null) {
                use.getValue().removeUser(this);
            }
        }
        operands.clear();
    }
} 
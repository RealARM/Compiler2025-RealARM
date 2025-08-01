package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Use;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示使用其他Value的Value
 */
public class User extends Value {
    private List<Use> operands = new ArrayList<>();  // 操作数列表
    
    public User(String name, Type type) {
        super(name, type);
    }
    
    /**
     * 获取操作数数量
     */
    public int getOperandCount() {
        return operands.size();
    }
    
    /**
     * 获取指定索引的操作数
     */
    public Value getOperand(int index) {
        return operands.get(index).getValue();
    }
    
    /**
     * 获取所有操作数
     */
    public List<Value> getOperands() {
        List<Value> values = new ArrayList<>();
        for (Use use : operands) {
            values.add(use.getValue());
        }
        return values;
    }
    
    /**
     * 设置指定索引的操作数
     */
    public void setOperand(int index, Value value) {
        // 如果索引超出范围，添加空操作数直到满足索引
        while (operands.size() <= index) {
            operands.add(null);
        }
        
        // 如果原来有值，移除该值的用户
        Use oldUse = operands.get(index);
        if (oldUse != null && oldUse.getValue() != null) {
            oldUse.getValue().removeUser(this);
        }
        
        // 设置新的值，并添加用户关系
        Use newUse = new Use(value, this);
        operands.set(index, newUse);
        if (value != null) {
            value.addUser(this);
        }
    }
    
    /**
     * 添加一个操作数
     */
    public void addOperand(Value value) {
        setOperand(operands.size(), value);
    }
    
    /**
     * 替换所有使用oldValue的地方为newValue
     */
    public void replaceAllUsesWith(Value oldValue, Value newValue) {
        for (int i = 0; i < operands.size(); i++) {
            if (getOperand(i) == oldValue) {
                setOperand(i, newValue);
            }
        }
    }
    
    /**
     * 移除所有操作数
     */
    public void removeAllOperands() {
        for (Use use : new ArrayList<>(operands)) {
            if (use != null && use.getValue() != null) {
                use.getValue().removeUser(this);
            }
        }
        operands.clear();
    }
} 
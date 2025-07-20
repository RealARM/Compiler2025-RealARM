package IR.Value;

import IR.Type.Type;

/**
 * 表示函数参数
 */
public class Argument extends Value {
    private final Function parentFunction; // 所属的函数
    private final int index;               // 在参数列表中的索引
    
    /**
     * 创建一个函数参数
     */
    public Argument(String name, Type type, Function function, int index) {
        super(name, type);
        this.parentFunction = function;
        this.index = index;
    }
    
    /**
     * 获取所属的函数
     */
    public Function getParentFunction() {
        return parentFunction;
    }
    
    /**
     * 获取在参数列表中的索引
     */
    public int getIndex() {
        return index;
    }
    
    @Override
    public String toString() {
        return getType() + " %" + getName();
    }
} 
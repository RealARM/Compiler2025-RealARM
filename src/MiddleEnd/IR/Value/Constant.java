package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;

/**
 * 表示编译期已知的常量值的基类
 */
public abstract class Constant extends Value {
    
    public Constant(String name, Type type) {
        super(name, type);
    }
    
    /**
     * 判断是否为常量
     */
    public boolean isConstant() {
        return true;
    }
} 
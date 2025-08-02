package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;

public abstract class Constant extends Value {
    
    public Constant(String name, Type type) {
        super(name, type);
    }
    
    public boolean isConstant() {
        return true;
    }
} 
package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;

public class Argument extends Value {
    private final Function parentFunction;
    private final int index;
    
    public Argument(String name, Type type, Function function, int index) {
        super(name, type);
        this.parentFunction = function;
        this.index = index;
    }
    
    public Function getParentFunction() {
        return parentFunction;
    }
    
    public int getIndex() {
        return index;
    }
    
    @Override
    public String toString() {
        return getType() + " %" + getName();
    }
} 
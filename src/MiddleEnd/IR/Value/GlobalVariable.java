package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.PointerType;
import MiddleEnd.IR.Type.Type;

import java.util.ArrayList;
import java.util.List;

public class GlobalVariable extends Value {
    private Value initializer;
    private final boolean isConstant;
    private final boolean isArray;
    private int arraySize;
    private boolean isZeroInitialized = false;
    private List<Value> arrayValues;
    
    public GlobalVariable(String name, Type type, boolean isConstant) {
        super(name, new PointerType(type));
        this.isConstant = isConstant;
        this.isArray = false;
    }
    
    public GlobalVariable(String name, Type type, int arraySize, boolean isConstant) {
        super(name, new PointerType(type));
        this.isConstant = isConstant;
        this.isArray = true;
        this.arraySize = arraySize;
        this.arrayValues = new ArrayList<>();
    }
    
    public GlobalVariable(String name, Type type, List<Value> arrayValues, boolean isConstant) {
        super(name, new PointerType(type));
        this.isConstant = isConstant;
        this.isArray = true;
        this.arrayValues = arrayValues;
        this.arraySize = arrayValues.size();
    }
    
    public Type getElementType() {
        return ((PointerType) getType()).getElementType();
    }
    
    public void setInitializer(Value initializer) {
        this.initializer = initializer;
    }
    
    public Value getInitializer() {
        return initializer;
    }
    
    public boolean hasInitializer() {
        return initializer != null || (arrayValues != null && !arrayValues.isEmpty());
    }
    
    public boolean isConstant() {
        return isConstant;
    }
    
    public boolean isArray() {
        return isArray;
    }
    
    public int getArraySize() {
        return arraySize;
    }
    
    public void setArraySize(int size) {
        if (isArray) {
            this.arraySize = size;
        }
    }
    
    public void setZeroInitialized(int size) {
        this.isZeroInitialized = true;
        this.arraySize = size;
    }
    
    public boolean isZeroInitialized() {
        return isZeroInitialized;
    }
    
    public List<Value> getArrayValues() {
        return arrayValues;
    }
    
    public void setArrayValues(List<Value> values) {
        if (isArray) {
            this.arrayValues = values;
            this.arraySize = values.size();
        }
    }
    
    public String getBitCastString() {
        String elementTypeStr = getElementType().toString();
        String pointerTypeStr = getType().toString();
        String resultName = "%" + getName().substring(1) + "_ptr";
        
        return resultName + " = bitcast " + pointerTypeStr + " " + getName() + 
               " to " + elementTypeStr + "*";
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = ");
        
        if (isConstant) {
            sb.append("constant ");
        } else {
            sb.append("global ");
        }
        
        sb.append(getElementType());
        
        if (isArray) {
            if (isZeroInitialized) {
                sb.append(" zeroinitializer");
            } else if (arrayValues != null && !arrayValues.isEmpty()) {
                sb.append(" [");
                for (int i = 0; i < arrayValues.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(arrayValues.get(i));
                }
                sb.append("]");
            } else {
                sb.append(" [undefined]");
            }
        } else if (hasInitializer()) {
            sb.append(" ").append(initializer);
        } else {
            sb.append(" zeroinitializer");
        }
        
        return sb.toString();
    }
} 
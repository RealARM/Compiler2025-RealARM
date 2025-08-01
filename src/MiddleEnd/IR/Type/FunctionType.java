package MiddleEnd.IR.Type;

import java.util.ArrayList;
import java.util.List;

public class FunctionType extends Type {
    private final Type returnType;         
    private final List<Type> paramTypes;   
    
    public FunctionType(Type returnType) {
        this(returnType, new ArrayList<>());
    }
    
    public FunctionType(Type returnType, List<Type> paramTypes) {
        this.returnType = returnType;
        this.paramTypes = new ArrayList<>(paramTypes);
    }
    
    public Type getReturnType() {
        return returnType;
    }
    
    public List<Type> getParamTypes() {
        return paramTypes;
    }
    
    public void addParamType(Type type) {
        paramTypes.add(type);
    }
    
    public int getParamCount() {
        return paramTypes.size();
    }
    
    public Type getParamType(int index) {
        return paramTypes.get(index);
    }
    
    @Override
    public int getSize() {
        return 0; 
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(returnType).append(" (");
        
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(paramTypes.get(i));
        }
        
        sb.append(")");
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FunctionType)) return false;
        
        FunctionType that = (FunctionType) obj;
        
        if (!returnType.equals(that.returnType)) return false;
        if (paramTypes.size() != that.paramTypes.size()) return false;
        
        for (int i = 0; i < paramTypes.size(); i++) {
            if (!paramTypes.get(i).equals(that.paramTypes.get(i))) {
                return false;
            }
        }
        
        return true;
    }
    
    @Override
    public int hashCode() {
        int result = returnType.hashCode();
        for (Type type : paramTypes) {
            result = 31 * result + type.hashCode();
        }
        return result;
    }
} 
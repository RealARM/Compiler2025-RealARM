package MiddleEnd.IR.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示函数类型
 */
public class FunctionType extends Type {
    private final Type returnType;         // 返回值类型
    private final List<Type> paramTypes;   // 参数类型列表
    
    /**
     * 创建一个只有返回值类型的函数类型
     */
    public FunctionType(Type returnType) {
        this(returnType, new ArrayList<>());
    }
    
    /**
     * 创建一个有参数的函数类型
     */
    public FunctionType(Type returnType, List<Type> paramTypes) {
        this.returnType = returnType;
        this.paramTypes = new ArrayList<>(paramTypes);
    }
    
    /**
     * 获取返回值类型
     */
    public Type getReturnType() {
        return returnType;
    }
    
    /**
     * 获取参数类型列表
     */
    public List<Type> getParamTypes() {
        return paramTypes;
    }
    
    /**
     * 添加参数类型
     */
    public void addParamType(Type type) {
        paramTypes.add(type);
    }
    
    /**
     * 获取参数数量
     */
    public int getParamCount() {
        return paramTypes.size();
    }
    
    /**
     * 获取指定索引的参数类型
     */
    public Type getParamType(int index) {
        return paramTypes.get(index);
    }
    
    @Override
    public int getSize() {
        return 0; // 函数类型不占用空间
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
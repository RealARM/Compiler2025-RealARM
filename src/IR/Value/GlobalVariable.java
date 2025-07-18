package IR.Value;

import IR.Type.PointerType;
import IR.Type.Type;

/**
 * 表示全局变量
 */
public class GlobalVariable extends Value {
    private Value initializer; // 初始值
    private final boolean isConstant; // 是否为常量
    private final boolean isArray; // 是否为数组
    private int arraySize; // 数组大小
    
    /**
     * 创建一个全局变量
     */
    public GlobalVariable(String name, Type type, boolean isConstant) {
        super(name, new PointerType(type));
        this.isConstant = isConstant;
        this.isArray = false;
    }
    
    /**
     * 创建一个全局数组
     */
    public GlobalVariable(String name, Type type, int arraySize, boolean isConstant) {
        super(name, new PointerType(type));
        this.isConstant = isConstant;
        this.isArray = true;
        this.arraySize = arraySize;
    }
    
    /**
     * 获取元素类型
     */
    public Type getElementType() {
        return ((PointerType) getType()).getElementType();
    }
    
    /**
     * 设置初始值
     */
    public void setInitializer(Value initializer) {
        this.initializer = initializer;
    }
    
    /**
     * 获取初始值
     */
    public Value getInitializer() {
        return initializer;
    }
    
    /**
     * 判断是否有初始值
     */
    public boolean hasInitializer() {
        return initializer != null;
    }
    
    /**
     * 判断是否为常量
     */
    public boolean isConstant() {
        return isConstant;
    }
    
    /**
     * 判断是否为数组
     */
    public boolean isArray() {
        return isArray;
    }
    
    /**
     * 获取数组大小
     */
    public int getArraySize() {
        return arraySize;
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
        
        if (hasInitializer()) {
            sb.append(" ").append(initializer);
        } else if (isArray) {
            sb.append(" [zeroinitializer]");
        } else {
            sb.append(" zeroinitializer");
        }
        
        return sb.toString();
    }
} 
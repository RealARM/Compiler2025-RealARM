package IR.Value;

import IR.Type.PointerType;
import IR.Type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示全局变量
 */
public class GlobalVariable extends Value {
    private Value initializer; // 初始值
    private final boolean isConstant; // 是否为常量
    private final boolean isArray; // 是否为数组
    private int arraySize; // 数组大小
    private boolean isZeroInitialized = false; // 是否默认为0初始化
    private List<Value> arrayValues; // 数组元素值（对于数组类型）
    
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
        this.arrayValues = new ArrayList<>();
    }
    
    /**
     * 创建一个带初始值的全局数组
     */
    public GlobalVariable(String name, Type type, List<Value> arrayValues, boolean isConstant) {
        super(name, new PointerType(type));
        this.isConstant = isConstant;
        this.isArray = true;
        this.arrayValues = arrayValues;
        this.arraySize = arrayValues.size();
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
        return initializer != null || !arrayValues.isEmpty();
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
    
    /**
     * 设置数组大小
     */
    public void setArraySize(int size) {
        if (isArray) {
            this.arraySize = size;
        }
    }
    
    /**
     * 设置为零初始化
     */
    public void setZeroInitialized(int size) {
        this.isZeroInitialized = true;
        this.arraySize = size;
    }
    
    /**
     * 判断是否为零初始化
     */
    public boolean isZeroInitialized() {
        return isZeroInitialized;
    }
    
    /**
     * 获取数组元素值列表
     */
    public List<Value> getArrayValues() {
        return arrayValues;
    }
    
    /**
     * 设置数组元素值列表
     */
    public void setArrayValues(List<Value> values) {
        if (isArray) {
            this.arrayValues = values;
            this.arraySize = values.size();
        }
    }
    
    /**
     * 获取位类型转换字符串（用于LLVM IR）
     * 与示例代码的getBitcast功能类似
     */
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
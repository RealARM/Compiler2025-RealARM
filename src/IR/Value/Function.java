package IR.Value;

import IR.Type.FunctionType;
import IR.Type.Type;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 表示函数
 */
public class Function extends Value {
    private final List<BasicBlock> blocks = new LinkedList<>();     // 基本块列表
    private final List<Argument> arguments = new ArrayList<>();    // 参数列表
    private boolean isExternal;                                   // 是否为外部函数（库函数）
    private Type returnType;                                      // 返回值类型
    
    /**
     * 创建一个函数
     */
    public Function(String name, Type returnType) {
        super(name, new FunctionType(returnType));
        this.returnType = returnType;
        this.isExternal = false;
    }
    
    /**
     * 获取返回值类型
     */
    public Type getReturnType() {
        return returnType;
    }
    
    /**
     * 添加参数
     */
    public void addArgument(Argument argument) {
        arguments.add(argument);
    }
    
    /**
     * 获取参数列表
     */
    public List<Argument> getArguments() {
        return arguments;
    }
    
    /**
     * 获取参数数量
     */
    public int getArgumentCount() {
        return arguments.size();
    }
    
    /**
     * 获取指定索引的参数
     */
    public Argument getArgument(int index) {
        return arguments.get(index);
    }
    
    /**
     * 添加基本块
     */
    public void addBasicBlock(BasicBlock block) {
        blocks.add(block);
    }
    
    /**
     * 获取所有基本块
     */
    public List<BasicBlock> getBasicBlocks() {
        return blocks;
    }
    
    /**
     * 获取入口基本块
     */
    public BasicBlock getEntryBlock() {
        if (blocks.isEmpty()) {
            return null;
        }
        return blocks.get(0);
    }
    
    /**
     * 设置为外部函数
     */
    public void setExternal(boolean external) {
        isExternal = external;
    }
    
    /**
     * 判断是否为外部函数
     */
    public boolean isExternal() {
        return isExternal;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("define ").append(returnType).append(" @")
            .append(getName()).append("(");
        
        // 添加参数
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(arguments.get(i));
        }
        
        sb.append(")");
        
        // 如果是外部函数，只需声明
        if (isExternal) {
            return sb.toString();
        }
        
        // 否则，添加函数体
        sb.append(" {\n");
        for (BasicBlock block : blocks) {
            sb.append("  ").append(block).append("\n");
        }
        sb.append("}");
        
        return sb.toString();
    }
} 
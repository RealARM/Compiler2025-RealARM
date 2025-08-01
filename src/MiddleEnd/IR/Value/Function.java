package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Instructions.Instruction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 表示函数
 */
public class Function extends Value {
    private final List<BasicBlock> blocks = new ArrayList<>();     // 基本块列表
    private final List<Argument> arguments = new ArrayList<>();    // 参数列表
    private boolean isExternal;                                   // 是否为外部函数（库函数）
    private Type returnType;                                      // 返回值类型
    
    // 调用关系
    private final List<Function> callers = new ArrayList<>();     // 调用此函数的函数列表
    private final List<Function> callees = new ArrayList<>();     // 此函数调用的函数列表
    
    // 全局变量访问信息
    private final LinkedHashSet<GlobalVariable> loadGVs = new LinkedHashSet<>();  // 函数读取的全局变量
    private final LinkedHashSet<GlobalVariable> storeGVs = new LinkedHashSet<>(); // 函数写入的全局变量
    
    // 副作用标记
    private boolean mayHaveSideEffect = false;
    private boolean storeGlobalVar = false;
    private boolean storeArgument = false;
    
    // 循环和支配关系分析信息
    private BasicBlock exitBlock;
    private LinkedHashMap<BasicBlock, ArrayList<BasicBlock>> immediateDominators;
    
    /**
     * 创建一个函数
     */
    public Function(String name, Type returnType) {
        super(name, returnType);
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
     * 在指定位置添加基本块
     */
    public void addBasicBlockBefore(BasicBlock newBlock, BasicBlock before) {
        int index = blocks.indexOf(before);
        if (index != -1) {
            blocks.add(index, newBlock);
        } else {
            blocks.add(newBlock);
        }
    }
    
    /**
     * 移除基本块
     */
    public void removeBasicBlock(BasicBlock block) {
        blocks.remove(block);
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
     * 获取出口基本块
     */
    public BasicBlock getExitBlock() {
        return exitBlock;
    }
    
    /**
     * 设置出口基本块
     */
    public void setExitBlock(BasicBlock block) {
        this.exitBlock = block;
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
    
    /**
     * 添加调用者
     */
    public void addCaller(Function caller) {
        if (!callers.contains(caller)) {
            callers.add(caller);
        }
    }
    
    /**
     * 添加被调用函数
     */
    public void addCallee(Function callee) {
        if (!callees.contains(callee)) {
            callees.add(callee);
        }
    }
    
    /**
     * 获取调用者列表
     */
    public List<Function> getCallers() {
        return callers;
    }
    
    /**
     * 获取被调用函数列表
     */
    public List<Function> getCallees() {
        return callees;
    }
    
    /**
     * 设置可能有副作用标记
     */
    public void setMayHaveSideEffect(boolean mayHaveSideEffect) {
        this.mayHaveSideEffect = mayHaveSideEffect;
    }
    
    /**
     * 判断是否可能有副作用
     */
    public boolean mayHaveSideEffect() {
        return mayHaveSideEffect;
    }
    
    /**
     * 设置存储全局变量标记
     */
    public void setStoreGlobalVar(boolean storeGlobalVar) {
        this.storeGlobalVar = storeGlobalVar;
    }
    
    /**
     * 判断是否存储全局变量
     */
    public boolean storesGlobalVar() {
        return storeGlobalVar;
    }
    
    /**
     * 设置存储参数标记
     */
    public void setStoreArgument(boolean storeArgument) {
        this.storeArgument = storeArgument;
    }
    
    /**
     * 判断是否存储参数
     */
    public boolean storesArgument() {
        return storeArgument;
    }
    
    /**
     * 添加读取的全局变量
     */
    public void addLoadGlobalVar(GlobalVariable var) {
        loadGVs.add(var);
    }
    
    /**
     * 添加写入的全局变量
     */
    public void addStoreGlobalVar(GlobalVariable var) {
        storeGVs.add(var);
    }
    
    /**
     * 获取读取的全局变量集合
     */
    public LinkedHashSet<GlobalVariable> getLoadGlobalVars() {
        return loadGVs;
    }
    
    /**
     * 获取写入的全局变量集合
     */
    public LinkedHashSet<GlobalVariable> getStoreGlobalVars() {
        return storeGVs;
    }
    
    /**
     * 设置直接支配关系
     */
    public void setImmediateDominators(LinkedHashMap<BasicBlock, ArrayList<BasicBlock>> dominators) {
        this.immediateDominators = dominators;
    }
    
    /**
     * 获取直接支配关系
     */
    public LinkedHashMap<BasicBlock, ArrayList<BasicBlock>> getImmediateDominators() {
        return immediateDominators;
    }
    
    /**
     * 获取指定基本块的循环深度
     */
    public int getLoopDepth(BasicBlock block) {
        return block.getLoopDepth();
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
            for (Instruction inst : block.getInstructions()) {
                sb.append("    ").append(inst).append("\n");
            }
        }
        sb.append("}");
        
        return sb.toString();
    }
} 
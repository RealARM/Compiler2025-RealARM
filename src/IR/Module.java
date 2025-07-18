package IR;

import IR.Value.Function;
import IR.Value.GlobalVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * IR模块，表示整个程序的IR表示
 */
public class Module {
    private final List<Function> functions;          // 普通函数列表
    private final List<GlobalVariable> globalVars;   // 全局变量列表
    private final List<Function> externalFunctions;  // 外部函数（库函数）列表
    private String name;                            // 模块名称
    
    public Module(String name) {
        this.name = name;
        this.functions = new ArrayList<>();
        this.globalVars = new ArrayList<>();
        this.externalFunctions = new ArrayList<>();
    }
    
    /**
     * 获取模块名称
     */
    public String getName() {
        return name;
    }
    
    /**
     * 设置模块名称
     */
    public void setName(String name) {
        this.name = name;
    }
    
    /**
     * 添加普通函数
     */
    public void addFunction(Function function) {
        functions.add(function);
    }
    
    /**
     * 添加全局变量
     */
    public void addGlobalVariable(GlobalVariable globalVar) {
        globalVars.add(globalVar);
    }
    
    /**
     * 添加外部函数
     */
    public void addExternalFunction(Function function) {
        externalFunctions.add(function);
    }
    
    /**
     * 获取所有普通函数
     */
    public List<Function> getFunctions() {
        return functions;
    }
    
    /**
     * 获取所有全局变量
     */
    public List<GlobalVariable> getGlobalVariables() {
        return globalVars;
    }
    
    /**
     * 获取所有外部函数
     */
    public List<Function> getExternalFunctions() {
        return externalFunctions;
    }
    
    /**
     * 根据名称查找函数
     */
    public Function getFunctionByName(String name) {
        for (Function function : functions) {
            if (function.getName().equals(name)) {
                return function;
            }
        }
        
        // 在外部函数中查找
        for (Function function : externalFunctions) {
            if (function.getName().equals(name)) {
                return function;
            }
        }
        
        return null;
    }
    
    /**
     * 根据名称查找全局变量
     */
    public GlobalVariable getGlobalVariableByName(String name) {
        for (GlobalVariable globalVar : globalVars) {
            if (globalVar.getName().equals(name)) {
                return globalVar;
            }
        }
        return null;
    }
} 
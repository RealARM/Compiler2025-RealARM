package IR;

import IR.Value.Function;
import IR.Value.GlobalVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * IR模块，表示整个程序的IR表示
 */
public class Module {
    private final ArrayList<Function> functions;          // 普通函数列表
    private final ArrayList<GlobalVariable> globalVars;   // 全局变量列表
    private final ArrayList<Function> libFunctions;      // 外部函数（库函数）列表
    private String name;                                // 模块名称
    
    public Module(String name) {
        this.name = name;
        this.functions = new ArrayList<>();
        this.globalVars = new ArrayList<>();
        this.libFunctions = new ArrayList<>();
    }

    /**
     * 添加全局变量
     */
    public void addGlobalVariable(GlobalVariable globalVar) {
        globalVars.add(globalVar);
    }

    /**
     * 添加函数
     */
    public void addFunction(Function function) {
        functions.add(function);
    }

    /**
     * 添加库函数
     */
    public void addLibFunction(Function function) {
        libFunctions.add(function);
    }

    /**
     * 获取函数列表
     */
    public ArrayList<Function> functions() {
        return functions;
    }

    /**
     * 获取全局变量列表
     */
    public ArrayList<GlobalVariable> globalVars() {
        return globalVars;
    }

    /**
     * 获取库函数列表
     */
    public ArrayList<Function> libFunctions() {
        return libFunctions;
    }

    /**
     * 根据名称查找库函数
     */
    public Function getLibFunction(String name) {
        for (Function func : libFunctions) {
            if (func.getName().equals("@" + name)) {
                return func;
            }
        }
        return null;
    }
} 
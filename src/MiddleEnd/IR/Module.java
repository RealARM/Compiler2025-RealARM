package MiddleEnd.IR;

import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.GlobalVariable;

import java.util.ArrayList;

public class Module {
    private final ArrayList<Function> functions;
    private final ArrayList<GlobalVariable> globalVars;
    private final ArrayList<Function> libFunctions;
    private String name;
    
    public Module(String name) {
        this.name = name;
        this.functions = new ArrayList<>();
        this.globalVars = new ArrayList<>();
        this.libFunctions = new ArrayList<>();
    }

    public void addGlobalVariable(GlobalVariable globalVar) {
        globalVars.add(globalVar);
    }

    public void addFunction(Function function) {
        functions.add(function);
    }

    public void addLibFunction(Function function) {
        libFunctions.add(function);
    }

    public ArrayList<Function> functions() {
        return functions;
    }

    public ArrayList<GlobalVariable> globalVars() {
        return globalVars;
    }

    public ArrayList<Function> libFunctions() {
        return libFunctions;
    }

    public Function getLibFunction(String name) {
        for (Function func : libFunctions) {
            if (func.getName().equals("@" + name)) {
                return func;
            }
        }
        return null;
    }
} 
package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.GlobalVariable;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.*;

/**
 * 函数纯度分析器
 * 识别纯函数（无副作用、返回值只依赖输入参数的函数）
 */
public class FunctionPurityAnalysis implements Optimizer.Analyzer {
    
    private Set<Function> pureFunctions = new HashSet<>();
    private Set<Function> impureFunctions = new HashSet<>();
    private Map<Function, Set<Function>> callGraph = new HashMap<>();
    
    private static final Set<String> KNOWN_IMPURE_FUNCTIONS = Set.of(
        "@putint", "@putch", "@putfarray", "@putfloat", "@putarray",
        "@getint", "@getch", "@getfarray", "@getfloat", "@getarray",
        "@starttime", "@stoptime", "@main"
    );
    
    @Override
    public String getName() {
        return "FunctionPurityAnalysis";
    }
    
    @Override
    public void run(Module module) {
        pureFunctions.clear();
        impureFunctions.clear();
        callGraph.clear();
        
        // 第一步：构建调用图
        buildCallGraph(module);
        
        // 第二步：标记明显的不纯函数
        markObviouslyImpureFunctions(module);
        
        // 第三步：迭代分析直到收敛
        propagatePurityInformation(module);
    }
    
    @Override
    public Object getResult() {
        return pureFunctions;
    }
    
    public boolean isPureFunction(Function function) {
        return pureFunctions.contains(function);
    }
    
    private void buildCallGraph(Module module) {
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            Set<Function> callees = new HashSet<>();
            
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst instanceof CallInstruction) {
                        CallInstruction call = (CallInstruction) inst;
                        Function callee = call.getCallee();
                        if (callee != null) {
                            callees.add(callee);
                        }
                    }
                }
            }
            
            callGraph.put(function, callees);
        }
    }
    
    private void markObviouslyImpureFunctions(Module module) {
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                if (KNOWN_IMPURE_FUNCTIONS.contains(function.getName())) {
                    impureFunctions.add(function);
                }
                continue;
            }
            
            if (isObviouslyImpure(function)) {
                impureFunctions.add(function);
            }
        }
    }
    
    private boolean isObviouslyImpure(Function function) {
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof StoreInstruction) {
                    StoreInstruction store = (StoreInstruction) inst;
                    if (store.getPointer() instanceof GlobalVariable) {
                        return true;
                    }
                    return true;
                }
                
                if (inst instanceof CallInstruction) {
                    CallInstruction call = (CallInstruction) inst;
                    Function callee = call.getCallee();
                    if (callee != null && impureFunctions.contains(callee)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    private void propagatePurityInformation(Module module) {
        boolean changed = true;
        int iterations = 0;
        final int MAX_ITERATIONS = 100;
        
        while (changed && iterations < MAX_ITERATIONS) {
            changed = false;
            iterations++;
            
            for (Function function : module.functions()) {
                if (function.isExternal() || impureFunctions.contains(function)) {
                    continue;
                }
                
                Set<Function> callees = callGraph.get(function);
                if (callees != null) {
                    for (Function callee : callees) {
                        if (impureFunctions.contains(callee)) {
                            if (!impureFunctions.contains(function)) {
                                impureFunctions.add(function);
                                changed = true;
                            }
                            break;
                        }
                    }
                }
                
                if (!impureFunctions.contains(function) && !pureFunctions.contains(function)) {
                    if (canBePure(function)) {
                        pureFunctions.add(function);
                        changed = true;
                    }
                }
            }
        }
        
        if (iterations >= MAX_ITERATIONS) {
            System.out.println("[FunctionPurityAnalysis] WARNING: Analysis did not converge within " + MAX_ITERATIONS + " iterations");
        }
    }
    
    private boolean canBePure(Function function) {
        if (function.getReturnType().isVoidType()) {
            return false;
        }
        
        Set<Function> callees = callGraph.get(function);
        if (callees != null) {
            for (Function callee : callees) {
                if (callee.isExternal()) {
                    return false;
                }
                
                if (callee == function) {
                    continue;
                }
                
                if (!callee.isExternal() && 
                    !pureFunctions.contains(callee) && 
                    !impureFunctions.contains(callee)) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    public void printStatistics() {
        System.out.println("[FunctionPurityAnalysis] Pure functions: " + pureFunctions.size());
        System.out.println("[FunctionPurityAnalysis] Impure functions: " + impureFunctions.size());
        
        System.out.println("Pure functions:");
        for (Function func : pureFunctions) {
            System.out.println("  " + func.getName());
        }
    }
}
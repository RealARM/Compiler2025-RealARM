package MiddleEnd.Optimization.Advanced;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.Optimization.Analysis.FunctionPurityAnalysis;
import MiddleEnd.Optimization.Core.OptimizeManager;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯净函数识别器
 * 识别纯净函数并在函数名后添加_memoizable标记
 * 本来想做记忆化，但发现太复杂，所以只做标记，让GVN优化
 */
public class MemoizationAdvisorSimple implements Optimizer.ModuleOptimizer {
    
    private final List<Function> pureFunctions = new ArrayList<>();
    
    @Override
    public String getName() {
        return "PureFunctionAnalyzer";
    }
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        FunctionPurityAnalysis purityAnalysis = new FunctionPurityAnalysis();
        purityAnalysis.run(module);
        
        OptimizeManager.getInstance().cacheAnalysisResult("FunctionPurityAnalysis", purityAnalysis);
        
        int pureCount = 0;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            boolean isPure = purityAnalysis.isPureFunction(function);
            
            if (isPure && shouldMarkPureFunction(function)) {
                pureCount++;
                pureFunctions.add(function);
                
                System.out.println("[PureFunctionAnalyzer] Function " + function.getName() + 
                                 " is pure and will be marked as memoizable");
                
                markFunctionForMemoization(function);
                changed = true;
            }
        }
        
        System.out.println("[PureFunctionAnalyzer] Analysis complete: " + 
                         pureCount + " pure functions marked as memoizable");
        
        printAnalysisStats();
        
        return changed;
    }
    
    /**
     */
    private boolean shouldMarkPureFunction(Function function) {
        if (function.getReturnType().isVoidType()) {
            return false;
        }
        
        if (function.getName().endsWith("_memoizable")) {
            return false;
        }
        
        if (function.getName().equals("@main")) {
            return false;
        }
        
        return true;
    }
    
    private void markFunctionForMemoization(Function function) {
        String currentName = function.getName();
        if (!currentName.endsWith("_memoizable")) {
            function.setName(currentName + "_memoizable");
        }
    }
    
    private void printAnalysisStats() {
        System.out.println("\n=== Pure Function Analysis Statistics ===");
        System.out.println("Total pure functions marked: " + pureFunctions.size());
        
        if (!pureFunctions.isEmpty()) {
            System.out.println("\nPure functions marked as memoizable:");
            for (Function func : pureFunctions) {
                System.out.println("  - " + func.getName());
            }
        }
        System.out.println("========================================\n");
    }
    
    public List<Function> getPureFunctions() {
        return new ArrayList<>(pureFunctions);
    }
    
    public boolean isPureFunction(Function function) {
        return pureFunctions.contains(function);
    }
    
    public int getPureFunctionCount() {
        return pureFunctions.size();
    }
} 
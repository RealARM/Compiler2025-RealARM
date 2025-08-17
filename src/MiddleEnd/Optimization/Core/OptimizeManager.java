package MiddleEnd.Optimization.Core;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer.ModuleOptimizer;
import MiddleEnd.Optimization.Core.Optimizer.FunctionOptimizer;
import MiddleEnd.IR.Value.Function;

import MiddleEnd.Optimization.Advanced.*;
import MiddleEnd.Optimization.Cleanup.*;
import MiddleEnd.Optimization.Constant.*;
import MiddleEnd.Optimization.ControlFlow.*;
import MiddleEnd.Optimization.Global.*;
import MiddleEnd.Optimization.Instruction.*;
import MiddleEnd.Optimization.Memory.*;
import MiddleEnd.Optimization.Loop.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优化管理器，负责组织和运行IR优化器
 */
public class OptimizeManager {
    // 单例模式
    private static final OptimizeManager instance = new OptimizeManager();
    
    public static OptimizeManager getInstance() {
        return instance;
    }
    
    private final List<ModuleOptimizer> moduleOptimizers = new ArrayList<>();
    
    private final List<FunctionOptimizer> functionOptimizers = new ArrayList<>();

    private final Map<String, Object> analysisResults = new HashMap<>();
    
    private boolean debug = false;
    
    private OptimizeManager() {
        initializeOptimizers();
    }

    private void initializeOptimizers() {

        // 尾递归消除优化
        addModuleOptimizer(new TailRecursionElimination());
        
        // 第一次Mem2Reg优化
        addModuleOptimizer(new Mem2Reg());
        
        // 函数内联展开优化
        addModuleOptimizer(new InlineExpansion());

        // 第二次Mem2Reg优化
        addModuleOptimizer(new Mem2Reg());
        
        // 常量优化
        addModuleOptimizer(new ConstantDeduplication());
        addModuleOptimizer(new ConstantArraySimplifier());
        
        // 基本块处理
        addModuleOptimizer(new EmptyBlockHandler());
        
        // 常量处理
        addModuleOptimizer(new ConstantPropagation());
        addModuleOptimizer(new ConstantFolding());
        
        // 全局变量优化
        addModuleOptimizer(new GlobalValueLocalize());
        
        // 指令组合优化
        addModuleOptimizer(new InstCombine());
        
        // 窥孔优化
        addModuleOptimizer(new PeepHole());
        
        // 删除单跳转基本块优化 - 存在问题
        addModuleOptimizer(new RemoveSingleJumpBB());
        
        // 移除无用的不等于比较指令优化
        addModuleOptimizer(new RemoveUselessNE());
        
        // 控制流优化
        addModuleOptimizer(new BranchSimplifier());
        
        // 循环SSA形式转换（在循环优化之前）
        addModuleOptimizer(new LoopSSATransform());

        // 删除无用循环
        addModuleOptimizer(new TrivialLoopDeletion());
        
        // 循环优化
        addModuleOptimizer(new LoopInvariantCodeMotion());
        
        // 循环指针访问优化
        addModuleOptimizer(new LoopPointerExtract());
        addModuleOptimizer(new LoopPointerExtractPlus());
        
        // 循环交换优化
        // addModuleOptimizer(new LoopInterchange());

        // 窥孔优化
        addModuleOptimizer(new PeepHole());
        
        // 全局代码移动优化
        addModuleOptimizer(new GCM());
        
        // 全局值编号优化 (GVN)
        addModuleOptimizer(new GVN());
        
        // 无用代码消除
        addModuleOptimizer(new DCE());

        // 常量处理
        addModuleOptimizer(new ConstantPropagation());
        addModuleOptimizer(new ConstantFolding());
        
        // PHI指令消除（在进入后端前将PHI转换为Move指令）
        // addModuleOptimizer(new RemovePhiPass());
    }
    
    public void addModuleOptimizer(ModuleOptimizer optimizer) {
        moduleOptimizers.add(optimizer);
    }
    
    public void addFunctionOptimizer(FunctionOptimizer optimizer) {
        functionOptimizers.add(optimizer);
    }
    
    public void setDebug(boolean debug) {
        this.debug = debug;
    }
    
    public void runModuleOptimizers(Module module) {
        boolean changed = false;
        for (ModuleOptimizer optimizer : moduleOptimizers) {
            if (debug) {
                System.out.println("Running module optimizer: " + optimizer.getName());
            }
            
            boolean optimizerChanged = optimizer.run(module);
            changed |= optimizerChanged;
            
            if (debug && optimizerChanged) {
                System.out.println("Optimizer " + optimizer.getName() + " changed the IR");
            }
        }
        
        if (debug) {
            System.out.println("IR optimization " + (changed ? "modified" : "did not modify") + " the module");
        }
    }
    
    public void runFunctionOptimizers(Function function) {
        boolean changed = false;
        for (FunctionOptimizer optimizer : functionOptimizers) {
            if (debug) {
                System.out.println("Running function optimizer: " + optimizer.getName() + " on " + function.getName());
            }
            
            boolean optimizerChanged = optimizer.run(function);
            changed |= optimizerChanged;
            
            if (debug && optimizerChanged) {
                System.out.println("Optimizer " + optimizer.getName() + " changed function " + function.getName());
            }
        }
        
        if (debug) {
            System.out.println("Function optimization " + (changed ? "modified" : "did not modify") + " " + function.getName());
        }
    }
    
    public void runFunctionOptimizersOnModule(Module module) {
        for (Function function : module.functions()) {
            // 检查是否为库函数（外部函数），跳过库函数
            if (module.libFunctions().contains(function)) {
                continue;
            }
            
            runFunctionOptimizers(function);
        }
    }
    
    public void cacheAnalysisResult(String optimizerName, Object result) {
        analysisResults.put(optimizerName, result);
    }
    
    public Object getAnalysisResult(String optimizerName) {
        return analysisResults.get(optimizerName);
    }
    
    public void clearAnalysisResults() {
        analysisResults.clear();
    }
    
    public void runAllOptimizers(Module module) {
        // 先运行模块级优化器
        runModuleOptimizers(module);
        
        // 再运行函数级优化器
        runFunctionOptimizersOnModule(module);
    }
    
    public void optimize(Module module, int optimizationLevel) {
        clearAnalysisResults();
        
        if (optimizationLevel <= 0) {
            return;
        }
        
        runModuleOptimizers(module);
        
        if (optimizationLevel >= 2) {
            runModuleOptimizers(module);
        }
        
        if (optimizationLevel >= 3) {
            runModuleOptimizers(module);
        }
    }
}
    

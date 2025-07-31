package MiddleEnd.Optimization.Core;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer.ModuleOptimizer;
import MiddleEnd.Optimization.Core.Optimizer.FunctionOptimizer;
import MiddleEnd.IR.Value.Function;

// 新的优化器导入
import MiddleEnd.Optimization.Advanced.*;
import MiddleEnd.Optimization.Cleanup.*;
import MiddleEnd.Optimization.Constant.*;
import MiddleEnd.Optimization.ControlFlow.*;
import MiddleEnd.Optimization.Global.*;
import MiddleEnd.Optimization.Instruction.*;
import MiddleEnd.Optimization.Memory.*;

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
        // 私有构造函数，防止外部实例化
        initializeOptimizers();
    }
    
    /**
     * 初始化默认的优化器
     */
    private void initializeOptimizers() {

        // 尾递归消除优化 - 应该在Mem2Reg之前运行，避免phi指令冲突
        addModuleOptimizer(new TailRecursionElimination());
        
        // Mem2Reg优化（SSA构造） - 在尾递归消除之后运行
        // addModuleOptimizer(new Mem2Reg());
        
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
        
        // // 循环SSA形式转换（在循环优化之前）
        // addModuleOptimizer(new LoopSSATransform());
        
        // // 循环优化
        // addModuleOptimizer(new LoopInvariantCodeMotion());
        
        // // 循环指针访问优化
        // addModuleOptimizer(new LoopPtrExtract());
        
        // // 循环交换优化
        // addModuleOptimizer(new LoopInterchange());
        
        // 全局代码移动优化
        addModuleOptimizer(new GCM());
        
        // 全局值编号优化 (GVN)
        addModuleOptimizer(new GVN());
        
        // 无用代码消除
        addModuleOptimizer(new DCE());
        
        // PHI指令消除（在进入后端前将PHI转换为Move指令）
        // addModuleOptimizer(new RemovePhiPass());
    }
    
    /**
     * 添加一个模块级优化器
     */
    public void addModuleOptimizer(ModuleOptimizer optimizer) {
        moduleOptimizers.add(optimizer);
    }
    
    /**
     * 添加一个函数级优化器
     */
    public void addFunctionOptimizer(FunctionOptimizer optimizer) {
        functionOptimizers.add(optimizer);
    }
    
    /**
     * 设置是否打印调试信息
     */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }
    
    /**
     * 运行所有模块级优化器
     */
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
    
    /**
     * 对单个函数运行所有函数级优化器
     */
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
    
    /**
     * 对模块中的所有函数运行函数级优化器
     */
    public void runFunctionOptimizersOnModule(Module module) {
        for (Function function : module.functions()) {
            // 检查是否为库函数（外部函数），跳过库函数
            if (module.libFunctions().contains(function)) {
                continue;
            }
            
            runFunctionOptimizers(function);
        }
    }
    
    /**
     * 保存分析结果
     */
    public void cacheAnalysisResult(String optimizerName, Object result) {
        analysisResults.put(optimizerName, result);
    }
    
    /**
     * 获取分析结果
     */
    public Object getAnalysisResult(String optimizerName) {
        return analysisResults.get(optimizerName);
    }
    
    /**
     * 清除所有分析结果
     */
    public void clearAnalysisResults() {
        analysisResults.clear();
    }
    
    /**
     * 运行所有优化器（先模块级别，再函数级别）
     */
    public void runAllOptimizers(Module module) {
        // 先运行模块级优化器
        runModuleOptimizers(module);
        
        // 再运行函数级优化器
        runFunctionOptimizersOnModule(module);
    }
    
    /**
     * 运行优化组合
     * @param module 要优化的模块
     * @param optimizationLevel 优化级别 (0-3)
     */
    public void optimize(Module module, int optimizationLevel) {
        clearAnalysisResults(); // 清除之前的分析结果
        
        if (optimizationLevel <= 0) {
            // 不进行优化
            return;
        }
        
        // 基本优化 (O1)
        runModuleOptimizers(module);
        
        if (optimizationLevel >= 2) {
            // 中等优化 (O2)
            // 再次运行基本优化，因为之前的优化可能创造了新的机会
            runModuleOptimizers(module);
        }
        
        if (optimizationLevel >= 3) {
            // 激进优化 (O3)
            // 第三轮优化
            runModuleOptimizers(module);
        }
    }
}
    

package IR.Pass;

import IR.Module;
import IR.Pass.Pass.IRPass;
import IR.Pass.Pass.FunctionPass;
import IR.Value.Function;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass管理器，负责组织和运行IR优化Pass
 */
public class PassManager {
    // 单例模式
    private static final PassManager instance = new PassManager();
    
    public static PassManager getInstance() {
        return instance;
    }
    
    // IR优化Pass列表
    private final List<IRPass> irPasses = new ArrayList<>();
    
    // 函数级Pass列表
    private final List<FunctionPass> functionPasses = new ArrayList<>();
    
    // 分析结果缓存
    private final Map<String, Object> analysisResults = new HashMap<>();
    
    // 是否打印调试信息
    private boolean debug = false;
    
    private PassManager() {
        // 私有构造函数，防止外部实例化
        
        // 添加默认的Pass
        initializePasses();
    }
    
    /**
     * 初始化默认的优化Pass
     */
    private void initializePasses() {
        // 基本块处理
        addIRPass(new EmptyBlockHandler());
        
        // 删除单跳转基本块优化
        addIRPass(new RemoveSingleJumpBB());
        
        // 常量处理
        addIRPass(new ConstantPropagation());
        addIRPass(new ConstantFolding());
        
        // 全局变量优化
        addIRPass(new GlobalValueLocalize());
        
        // 指令组合优化
        addIRPass(new InstCombine());
        
        // 控制流优化
        addIRPass(new BranchSimplifier());
        
        // 全局代码移动优化
        addIRPass(new GCM());
        
        // 全局值编号优化 (GVN)
        addIRPass(new GVN());
        
        // 无用代码消除
        addIRPass(new DCE());
    }
    
    /**
     * 添加一个IR优化Pass
     */
    public void addIRPass(IRPass pass) {
        irPasses.add(pass);
    }
    
    /**
     * 添加一个函数级优化Pass
     */
    public void addFunctionPass(FunctionPass pass) {
        functionPasses.add(pass);
    }
    
    /**
     * 设置是否打印调试信息
     */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }
    
    /**
     * 运行所有IR优化Pass
     */
    public void runIRPasses(Module module) {
        boolean changed = false;
        for (IRPass pass : irPasses) {
            if (debug) {
                System.out.println("Running IR pass: " + pass.getName());
            }
            
            boolean passChanged = pass.run(module);
            changed |= passChanged;
            
            if (debug && passChanged) {
                System.out.println("Pass " + pass.getName() + " changed the IR");
            }
        }
        
        if (debug) {
            System.out.println("IR optimization " + (changed ? "modified" : "did not modify") + " the module");
        }
    }
    
    /**
     * 对单个函数运行所有函数级Pass
     */
    public void runFunctionPasses(Function function) {
        boolean changed = false;
        for (FunctionPass pass : functionPasses) {
            if (debug) {
                System.out.println("Running function pass: " + pass.getName() + " on " + function.getName());
            }
            
            boolean passChanged = pass.run(function);
            changed |= passChanged;
            
            if (debug && passChanged) {
                System.out.println("Pass " + pass.getName() + " changed function " + function.getName());
            }
        }
        
        if (debug) {
            System.out.println("Function optimization " + (changed ? "modified" : "did not modify") + " " + function.getName());
        }
    }
    
    /**
     * 对模块中的所有函数运行函数级Pass
     */
    public void runFunctionPassesOnModule(Module module) {
        for (Function function : module.functions()) {
            // 检查是否为库函数（外部函数），跳过库函数
            if (module.libFunctions().contains(function)) {
                continue;
            }
            
            runFunctionPasses(function);
        }
    }
    
    /**
     * 保存分析结果
     */
    public void cacheAnalysisResult(String passName, Object result) {
        analysisResults.put(passName, result);
    }
    
    /**
     * 获取分析结果
     */
    public Object getAnalysisResult(String passName) {
        return analysisResults.get(passName);
    }
    
    /**
     * 清除所有分析结果
     */
    public void clearAnalysisResults() {
        analysisResults.clear();
    }
    
    /**
     * 运行所有Pass（先IR级别，再函数级别）
     */
    public void runAllPasses(Module module) {
        // 先运行模块级Pass
        runIRPasses(module);
        
        // 再运行函数级Pass
        runFunctionPassesOnModule(module);
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
        runIRPasses(module);
        
        if (optimizationLevel >= 2) {
            // 中等优化 (O2)
            // 再次运行基本优化，因为之前的优化可能创造了新的机会
            runIRPasses(module);
        }
        
        if (optimizationLevel >= 3) {
            // 激进优化 (O3)
            // 第三轮优化
            runIRPasses(module);
        }
    }
} 
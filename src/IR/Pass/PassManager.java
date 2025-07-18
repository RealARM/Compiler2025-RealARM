package IR.Pass;

import IR.Module;
import IR.Pass.Pass.IRPass;
import IR.Pass.Pass.FunctionPass;
import IR.Value.Function;

import java.util.ArrayList;
import java.util.List;

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
    
    // 中端IR优化Pass列表（后端前最后处理的Pass）
    private final List<IRPass> middleEndPasses = new ArrayList<>();
    
    private PassManager() {
        // 在此处初始化各种Pass
        initializePasses();
    }
    
    /**
     * 初始化各种Pass
     */
    private void initializePasses() {
        // 目前只是一个占位符，后续添加实际的Pass
    }
    
    /**
     * 添加一个IR优化Pass
     */
    public void addIRPass(IRPass pass) {
        irPasses.add(pass);
    }
    
    /**
     * 添加一个中端IR优化Pass
     */
    public void addMiddleEndPass(IRPass pass) {
        middleEndPasses.add(pass);
    }
    
    /**
     * 运行所有IR优化Pass
     */
    public void runIRPasses(Module module) {
        for (IRPass pass : irPasses) {
            System.out.println("Running IR pass: " + pass.getName());
            pass.run(module);
        }
    }
    
    /**
     * 运行所有中端IR优化Pass
     */
    public void runMiddleEndPasses(Module module) {
        for (IRPass pass : middleEndPasses) {
            System.out.println("Running middle-end pass: " + pass.getName());
            pass.run(module);
        }
    }
    
    /**
     * 对单个函数运行所有FunctionPass
     */
    public void runFunctionPasses(Function function, List<FunctionPass> functionPasses) {
        for (FunctionPass pass : functionPasses) {
            System.out.println("Running function pass: " + pass.getName() + " on " + function.getName());
            pass.runOnFunction(function);
        }
    }
} 
package MiddleEnd.Optimization.Cleanup;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Instructions.BranchInstruction;
import MiddleEnd.IR.Value.Instructions.CallInstruction;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.ReturnInstruction;
import MiddleEnd.IR.Value.Instructions.StoreInstruction;
import MiddleEnd.IR.Value.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 无效代码剔除优化 (Dead Code Elimination)
 * 识别并移除对程序结果没有影响的冗余指令
 */
public class DCE implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "DeadCodeElimination";
    }

    // 调试标志 - 禁用调试输出
    private boolean debug = false;
    
    // 记录所有必要保留的指令
    private final HashSet<Instruction> essentialInstructions = new HashSet<>();
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= optimizeFunction(function);
        }
        
        return changed;
    }
    
    /**
     * 对单个函数执行无效代码剔除优化
     * @param function 待优化的函数
     * @return 如果函数被修改返回true，否则返回false
     */
    private boolean optimizeFunction(Function function) {
        // 清空记录
        essentialInstructions.clear();
        
        // 第一步：标记所有必要保留的指令
        markEssentialInstructions(function);
        
        // 第二步：收集所有可以删除的指令
        List<Instruction> toRemove = collectDeadInstructions(function);
        
        // 没有发现无用指令，直接返回
        if (toRemove.isEmpty()) {
            return false;
        }
        
        // 第三步：删除无用指令
        for (Instruction inst : toRemove) {
            try {
                inst.removeFromParent();
            } catch (Exception e) {
                if (debug) {
                    System.err.println("Error removing instruction: " + e.getMessage());
                }
            }
        }
        
        return true;
    }
    
    /**
     * 标记所有必要保留的指令
     */
    private void markEssentialInstructions(Function function) {
        // 使用工作队列记录待处理的指令
        List<Instruction> workList = new ArrayList<>();
        
        // 第一步：找出所有"根指令"（有副作用或控制流）
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (isRootInstruction(inst)) {
                    workList.add(inst);
                    essentialInstructions.add(inst);
                }
            }
        }
        
        // 第二步：从根指令递归标记所有依赖指令
        while (!workList.isEmpty()) {
            Instruction current = workList.remove(0);
            
            // 处理操作数
            for (Value operand : current.getOperands()) {
                if (operand instanceof Instruction dependency) {
                    if (!essentialInstructions.contains(dependency)) {
                        essentialInstructions.add(dependency);
                        workList.add(dependency);
                    }
                }
            }
        }
    }
    
    /**
     * 判断指令是否为根指令（有副作用或控制流）
     */
    private boolean isRootInstruction(Instruction inst) {
        // 终结指令（分支、返回等）
        if (inst instanceof BranchInstruction || inst instanceof ReturnInstruction) {
            return true;
        }
        
        // 存储指令（可能写入内存）
        if (inst instanceof StoreInstruction) {
            return true;
        }
        
        // 函数调用（可能有副作用）
        if (inst instanceof CallInstruction) {
            // 保守策略：假设所有调用都可能有副作用
            return true;
        }
        
        // 指令有其他用户（其结果被使用）
        if (!inst.getUsers().isEmpty()) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 收集所有可以删除的指令
     */
    private List<Instruction> collectDeadInstructions(Function function) {
        List<Instruction> result = new ArrayList<>();
        
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (!essentialInstructions.contains(inst)) {
                    result.add(inst);
                }
            }
        }
        
        return result;
    }
} 
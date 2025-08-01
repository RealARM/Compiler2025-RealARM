package MiddleEnd.Optimization.ControlFlow;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 条件分支简化优化
 * 当分支条件为常量时，简化为无条件跳转
 */
public class BranchSimplifier implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "BranchSimplifier";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        // 对模块中的每个函数进行处理
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= simplifyBranches(function);
        }
        
        return changed;
    }
    
    /**
     * 简化函数中的条件分支
     * @param function 待处理的函数
     * @return 如果有分支被简化则返回true
     */
    private boolean simplifyBranches(Function function) {
        boolean changed = false;
        
        // 处理函数中的每个基本块
        for (BasicBlock block : function.getBasicBlocks()) {
            // 获取基本块的终结指令
            Instruction terminator = block.getTerminator();
            
            // 只处理条件分支指令
            if (!(terminator instanceof BranchInstruction)) {
                continue;
            }
            
            BranchInstruction branch = (BranchInstruction) terminator;
            
            // 只处理条件分支，跳过无条件跳转
            if (branch.isUnconditional()) {
                continue;
            }
            
            Value condition = branch.getCondition();
            
            // 条件必须是常量整数才能在编译时确定
            if (!(condition instanceof ConstantInt)) {
                continue;
            }
            
            // 获取常量条件值
            int condValue = ((ConstantInt) condition).getValue();
            
            // 确定要保留的分支目标和要移除的分支目标
            BasicBlock targetBlock = condValue != 0 ? branch.getTrueBlock() : branch.getFalseBlock();
            BasicBlock removedBlock = condValue != 0 ? branch.getFalseBlock() : branch.getTrueBlock();
            
            // 从被移除的目标块的phi指令中移除当前块的贡献
            removePhiContributions(block, removedBlock);
            
            // 创建新的无条件跳转
            BranchInstruction newBranch = new BranchInstruction(targetBlock);
            
            // 移除旧的分支指令
            branch.removeFromParent();
            
            // 添加新的跳转指令
            block.addInstruction(newBranch);
            
            // 更新CFG：移除当前块和被移除目标块之间的边
            block.removeSuccessor(removedBlock);
            
            changed = true;
        }
        
        return changed;
    }
    
    /**
     * 从目标块中的phi指令中移除源块的贡献
     * @param sourceBlock 源基本块
     * @param targetBlock 目标基本块
     */
    private void removePhiContributions(BasicBlock sourceBlock, BasicBlock targetBlock) {
        // 获取所有phi指令
        List<PhiInstruction> phiInsts = targetBlock.getPhiInstructions();
        
        // 在每个phi指令中移除源块的贡献
        for (PhiInstruction phi : phiInsts) {
            if (phi.getIncomingBlocks().contains(sourceBlock)) {
                phi.removeIncoming(sourceBlock);
            }
        }
    }
} 
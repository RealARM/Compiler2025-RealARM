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
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= simplifyBranches(function);
        }
        
        return changed;
    }
    
    private boolean simplifyBranches(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            Instruction terminator = block.getTerminator();
            
            if (!(terminator instanceof BranchInstruction)) {
                continue;
            }
            
            BranchInstruction branch = (BranchInstruction) terminator;
            
            if (branch.isUnconditional()) {
                continue;
            }
            
            Value condition = branch.getCondition();
            
            if (!(condition instanceof ConstantInt)) {
                continue;
            }
            
            int condValue = ((ConstantInt) condition).getValue();
            
            BasicBlock targetBlock = condValue != 0 ? branch.getTrueBlock() : branch.getFalseBlock();
            BasicBlock removedBlock = condValue != 0 ? branch.getFalseBlock() : branch.getTrueBlock();
            
            removePhiContributions(block, removedBlock);
            
            BranchInstruction newBranch = new BranchInstruction(targetBlock);
            
            branch.removeFromParent();
            
            block.addInstruction(newBranch);
            
            block.removeSuccessor(removedBlock);
            
            changed = true;
        }
        
        return changed;
    }
    
    private void removePhiContributions(BasicBlock sourceBlock, BasicBlock targetBlock) {
        List<PhiInstruction> phiInsts = targetBlock.getPhiInstructions();
        
        for (PhiInstruction phi : phiInsts) {
            if (phi.getIncomingBlocks().contains(sourceBlock)) {
                phi.removeIncoming(sourceBlock);
            }
        }
    }
} 
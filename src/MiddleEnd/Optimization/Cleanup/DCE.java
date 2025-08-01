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

    private boolean debug = false;
    
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
    
    private boolean optimizeFunction(Function function) {
        essentialInstructions.clear();
        
        markEssentialInstructions(function);
        
        List<Instruction> toRemove = collectDeadInstructions(function);
        
        if (toRemove.isEmpty()) {
            return false;
        }
        
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
    
    private void markEssentialInstructions(Function function) {
        List<Instruction> workList = new ArrayList<>();
        
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (isRootInstruction(inst)) {
                    workList.add(inst);
                    essentialInstructions.add(inst);
                }
            }
        }
        
        while (!workList.isEmpty()) {
            Instruction current = workList.remove(0);
            
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
    
    private boolean isRootInstruction(Instruction inst) {
        if (inst instanceof BranchInstruction || inst instanceof ReturnInstruction) {
            return true;
        }

        if (inst instanceof StoreInstruction) {
            return true;
        }
        
        if (inst instanceof CallInstruction) {
            return true;
        }
        
        if (!inst.getUsers().isEmpty()) {
            return true;
        }
        
        return false;
    }
    
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
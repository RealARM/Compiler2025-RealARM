package MiddleEnd.Optimization.ControlFlow;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Type.*;

/**
 * 处理空基本块的Pass
 * 为空基本块添加适当的终结指令
 */
public class EmptyBlockHandler implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "EmptyBlockHandler";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            for (BasicBlock block : function.getBasicBlocks()) {
                if (block.getInstructions().isEmpty()) {
                    addTerminatorToEmptyBlock(block);
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    private void addTerminatorToEmptyBlock(BasicBlock block) {
        if (!block.getSuccessors().isEmpty()) {
            BranchInstruction brInst = new BranchInstruction(block.getSuccessors().get(0));
            block.addInstruction(brInst);
        } else {
            Function function = block.getParentFunction();
            Type returnType = function.getReturnType();
            
            ReturnInstruction retInst;
            if (returnType instanceof VoidType) {
                retInst = new ReturnInstruction();
            } else if (returnType instanceof IntegerType) {
                retInst = new ReturnInstruction(new ConstantInt(0));
            } else if (returnType instanceof FloatType) {
                retInst = new ReturnInstruction(new ConstantFloat(0.0f));
            } else {
                retInst = new ReturnInstruction(new ConstantInt(0));
            }
            block.addInstruction(retInst);
        }
    }
} 
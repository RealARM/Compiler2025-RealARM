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
    
    /**
     * 为空基本块添加终结指令
     */
    private void addTerminatorToEmptyBlock(BasicBlock block) {
        // 首先检查后继基本块
        if (!block.getSuccessors().isEmpty()) {
            // 如果有后继块，添加无条件跳转到第一个后继块
            BranchInstruction brInst = new BranchInstruction(block.getSuccessors().get(0));
            block.addInstruction(brInst);
        } else {
            // 如果没有后继块，则添加返回指令
            Function function = block.getParentFunction();
            Type returnType = function.getReturnType();
            
            ReturnInstruction retInst;
            if (returnType instanceof VoidType) {
                // 返回void
                retInst = new ReturnInstruction();
            } else if (returnType instanceof IntegerType) {
                // 返回整数0
                retInst = new ReturnInstruction(new ConstantInt(0));
            } else if (returnType instanceof FloatType) {
                // 返回浮点数0.0
                retInst = new ReturnInstruction(new ConstantFloat(0.0f));
            } else {
                // 默认返回整数0
                retInst = new ReturnInstruction(new ConstantInt(0));
            }
            block.addInstruction(retInst);
        }
    }
} 
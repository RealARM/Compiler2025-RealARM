package MiddleEnd.Optimization.Instruction;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Cleanup.DCE;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 移除无用的不等于比较指令和相关无用转换指令Pass
 * 优化：
 * 1. 形如 (x != 0) 的比较指令，其中x已经是布尔值(i1类型)
 * 2. 形如 (x != 0) 的比较指令后，使用zext对结果进行扩展的模式
 */
public class RemoveUselessNE implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "RemoveUselessNE";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;

        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            for (BasicBlock bb : function.getBasicBlocks()) {
                changed |= optimizeBasicBlock(bb);
            }
        }
        
        if (changed) {
            DCE dce = new DCE();
            dce.run(module);
        }
        
        return changed;
    }
    
    private boolean optimizeBasicBlock(BasicBlock bb) {
        boolean changed = false;
        List<OptimizationCandidate> optimizationCandidates = new ArrayList<>();
        
        for (Instruction inst : bb.getInstructions()) {
            OptimizationCandidate candidate = findUselessNEComparison(inst);
            if (candidate != null) {
                optimizationCandidates.add(candidate);
                continue;
            }
            
            candidate = findUselessZext(inst);
            if (candidate != null) {
                optimizationCandidates.add(candidate);
            }
        }
        
        for (OptimizationCandidate candidate : optimizationCandidates) {
            applyOptimization(candidate);
            changed = true;
        }
        
        return changed;
    }
    
    private OptimizationCandidate findUselessNEComparison(Instruction inst) {
        if (!(inst instanceof CompareInstruction cmpInst) || 
            !cmpInst.isIntegerCompare() || 
            cmpInst.getPredicate() != OpCode.NE) {
            return null;
        }
        
        Value left = cmpInst.getLeft();
        Value right = cmpInst.getRight();
        
        Value boolValue = null;
        
        if (right instanceof ConstantInt constInt && constInt.getValue() == 0) {
            boolValue = left;
        }
        else if (left instanceof ConstantInt constInt && constInt.getValue() == 0) {
            boolValue = right;
        } else {
            return null;
        }
        
        if (boolValue instanceof BinaryInstruction binaryInst
                && binaryInst.getType() == IntegerType.I1) {
            return new OptimizationCandidate(inst, binaryInst);
        }
        
        return null;
    }
    
    private OptimizationCandidate findUselessZext(Instruction inst) {
        if (!(inst instanceof ConversionInstruction convInst) || 
            convInst.getConversionType() != OpCode.ZEXT) {
            return null;
        }
        
        Value source = convInst.getSource();
        
        if (source instanceof CompareInstruction cmpInst && 
            cmpInst.getType() == IntegerType.I1) {
            
            if (cmpInst.isIntegerCompare()) {
                if (cmpInst.getPredicate() == OpCode.NE) {
                    Value left = cmpInst.getLeft();
                    Value right = cmpInst.getRight();
                    
                    if ((right instanceof ConstantInt constIntRight && constIntRight.getValue() == 0) ||
                        (left instanceof ConstantInt constIntLeft && constIntLeft.getValue() == 0)) {
                        
                        Value otherOperand = (right instanceof ConstantInt) ? left : right;
                        if (otherOperand instanceof BinaryInstruction binaryInst && 
                            binaryInst.getType() == IntegerType.I1) {
                            
                            return new OptimizationCandidate(inst, null, OptimizationType.SKIP_FOR_NOW);
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    private void applyOptimization(OptimizationCandidate candidate) {
        if (candidate.type == OptimizationType.SKIP_FOR_NOW) {
            return;
        }
        
        Instruction uselessInst = candidate.uselessInstruction;
        Value replacementValue = candidate.replacementValue;
        
        List<User> users = new ArrayList<>(uselessInst.getUsers());
        
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == uselessInst) {
                    user.setOperand(i, replacementValue);
                }
            }
        }
        
        if (uselessInst.getUsers().isEmpty()) {
            uselessInst.removeFromParent();
        }
    }
    
    private enum OptimizationType {
        REPLACE,       // 替换指令
        SKIP_FOR_NOW   // 暂时跳过，可能在后续Pass中处理
    }
    
    private static class OptimizationCandidate {
        final Instruction uselessInstruction;
        final Value replacementValue;
        final OptimizationType type;
        
        OptimizationCandidate(Instruction uselessInstruction, Value replacementValue) {
            this.uselessInstruction = uselessInstruction;
            this.replacementValue = replacementValue;
            this.type = OptimizationType.REPLACE;
        }
        
        OptimizationCandidate(Instruction uselessInstruction, Value replacementValue, OptimizationType type) {
            this.uselessInstruction = uselessInstruction;
            this.replacementValue = replacementValue;
            this.type = type;
        }
    }
} 
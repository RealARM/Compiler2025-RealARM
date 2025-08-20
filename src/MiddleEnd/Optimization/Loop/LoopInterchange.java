package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Analysis.Loop;
import MiddleEnd.Optimization.Analysis.LoopAnalysis;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.*;

/**
 * 循环重排优化
 * 通过分析嵌套循环的访问模式，重新安排循环顺序以提高缓存效率
 */
public class LoopInterchange implements Optimizer.ModuleOptimizer {
    private final boolean debug = false;
    private static final int MAX_BLOCK_THRESHOLD = 1000;
    
    // 缓存已分析的循环对，避免重复处理
    private final Set<String> analyzedPairs = new HashSet<>();
    
    @Override
    public String getName() {
        return "LoopInterchange";
    }
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        if (debug) {
            System.out.println("[LoopInterchange] 开始循环重排优化");
        }
        
        for (Function function : module.functions()) {
            if (function.isExternal()) continue;
            changed |= optimizeFunction(function);
        }
        
        if (debug) {
            System.out.println("[LoopInterchange] 优化完成，是否有改动: " + changed);
        }
        
        return changed;
    }
    
    private boolean optimizeFunction(Function function) {
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BLOCK_THRESHOLD) {
            System.out.println("[LoopInterchange] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        boolean changed = false;
        analyzedPairs.clear();
        
        if (debug) {
            System.out.println("[LoopInterchange] 分析函数: " + function.getName());
        }
        
        // 分析循环结构和归纳变量
        List<Loop> topLoops = LoopAnalysis.analyzeLoops(function);
        LoopAnalysis.analyzeInductionVariables(function);
        
        if (debug) {
            System.out.println("[LoopInterchange] 找到 " + topLoops.size() + " 个顶层循环");
        }
        
        // 递归处理所有循环
        for (Loop topLoop : topLoops) {
            changed |= processNestedLoops(topLoop);
        }
        
        return changed;
    }
    
    /**
     * 递归处理嵌套循环，寻找可优化的循环对
     */
    private boolean processNestedLoops(Loop loop) {
        boolean changed = false;
        
        // 深度优先处理子循环
        for (Loop subLoop : loop.getSubLoops()) {
            changed |= processNestedLoops(subLoop);
        }
        
        // 检查当前循环与其直接子循环的交换可能性
        for (Loop innerLoop : loop.getSubLoops()) {
            if (shouldAnalyzeInterchange(loop, innerLoop)) {
                if (canSafelyInterchange(loop, innerLoop)) {
                    if (isInterchangeBeneficial(loop, innerLoop)) {
                        if (debug) {
                            System.out.println("[LoopInterchange] 重排循环: " + 
                                loop.getHeader().getName() + " <-> " + innerLoop.getHeader().getName());
                        }
                        changed |= performInterchange(loop, innerLoop);
                    }
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 判断是否应该分析这对循环
     */
    private boolean shouldAnalyzeInterchange(Loop outer, Loop inner) {
        // 生成唯一标识避免重复分析
        String pairKey = outer.getHeader().getName() + "_" + inner.getHeader().getName();
        if (analyzedPairs.contains(pairKey)) {
            return false;
        }
        analyzedPairs.add(pairKey);
        
        return true;
    }
    
    /**
     * 检查两个循环是否可以安全交换
     */
    private boolean canSafelyInterchange(Loop outer, Loop inner) {
        // 基本结构检查
        if (!outer.hasInductionVariable() || !inner.hasInductionVariable()) {
            return false;
        }
        
        // 确保是直接的父子关系
        if (!outer.getSubLoops().contains(inner)) {
            return false;
        }
        
        // 要求简单的循环结构
        if (outer.getLatchBlocks().size() != 1 || inner.getLatchBlocks().size() != 1) {
            return false;
        }
        
        // 要求单一出口
        outer.computeExitBlocks();
        inner.computeExitBlocks();
        if (outer.getExitBlocks().size() != 1 || inner.getExitBlocks().size() != 1) {
            return false;
        }
        
        // 检查归纳变量的独立性
        if (!areInductionVariablesIndependent(outer, inner)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查归纳变量之间是否独立
     */
    private boolean areInductionVariablesIndependent(Loop outer, Loop inner) {
        Value outerIndVar = outer.getInductionVariable();
        Value innerIndVar = inner.getInductionVariable();
        
        // 外层循环的边界和步长不应依赖内层变量
        if (dependsOn(outer.getEndValue(), innerIndVar) ||
            dependsOn(outer.getStepValue(), innerIndVar)) {
            return false;
        }
        
        // 内层循环的步长不应依赖外层变量
        if (dependsOn(inner.getStepValue(), outerIndVar)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查值是否依赖于目标变量
     */
    private boolean dependsOn(Value value, Value target) {
        if (value == target) {
            return true;
        }
        
        if (value instanceof Instruction inst) {
            for (int i = 0; i < inst.getOperandCount(); i++) {
                if (dependsOn(inst.getOperand(i), target)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 判断交换是否有益（启发式分析）
     */
    private boolean isInterchangeBeneficial(Loop outer, Loop inner) {
        // 分析内存访问模式
        MemoryAccessPattern pattern = analyzeMemoryAccessPattern(inner);
        
        if (pattern.beneficialAccesses > pattern.totalAccesses / 2) {
            return true;
        }
        
        // 如果内层循环迭代次数更少，交换可能有益
        long outerIterations = estimateIterations(outer);
        long innerIterations = estimateIterations(inner);
        
        if (innerIterations > 0 && outerIterations > 0 && 
            innerIterations < outerIterations * 0.8) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 分析内存访问模式
     */
    private MemoryAccessPattern analyzeMemoryAccessPattern(Loop loop) {
        int totalAccesses = 0;
        int beneficialAccesses = 0;
        
        Value outerVar = loop.getParentLoop() != null ? 
            loop.getParentLoop().getInductionVariable() : null;
        Value innerVar = loop.getInductionVariable();
        
        if (outerVar == null) {
            return new MemoryAccessPattern(0, 0);
        }
        
        // 分析循环内的内存访问
        for (BasicBlock block : loop.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof LoadInstruction || inst instanceof StoreInstruction) {
                    totalAccesses++;
                    
                    Value address = getMemoryAddress(inst);
                    if (address instanceof GetElementPtrInstruction gep) {
                        if (isAccessPatternBeneficialForInterchange(gep, outerVar, innerVar)) {
                            beneficialAccesses++;
                        }
                    }
                }
            }
        }
        
        return new MemoryAccessPattern(totalAccesses, beneficialAccesses);
    }
    
    /**
     * 获取内存指令的地址操作数
     */
    private Value getMemoryAddress(Instruction inst) {
        if (inst instanceof LoadInstruction load) {
            return load.getPointer();
        } else if (inst instanceof StoreInstruction store) {
            return store.getPointer();
        }
        return null;
    }
    
    /**
     * 判断GEP访问模式是否从交换中受益
     */
    private boolean isAccessPatternBeneficialForInterchange(GetElementPtrInstruction gep, 
                                                          Value outerVar, Value innerVar) {
        // 分析多维数组访问模式
        if (gep.getOperandCount() >= 3) {
            Value lastIndex = gep.getOperand(gep.getOperandCount() - 1);
            Value secondLastIndex = gep.getOperand(gep.getOperandCount() - 2);
            
            boolean lastIsOuter = containsVariable(lastIndex, outerVar);
            boolean lastIsInner = containsVariable(lastIndex, innerVar);
            boolean secondLastIsOuter = containsVariable(secondLastIndex, outerVar);
            boolean secondLastIsInner = containsVariable(secondLastIndex, innerVar);
            
            // 如果当前是[inner][outer]模式，交换后变为[outer][inner]，对缓存友好
            return secondLastIsInner && lastIsOuter;
        }
        
        return false;
    }
    
    /**
     * 检查值中是否包含特定变量
     */
    private boolean containsVariable(Value value, Value target) {
        if (value == target) {
            return true;
        }
        if (value instanceof Instruction inst) {
            for (int i = 0; i < inst.getOperandCount(); i++) {
                if (containsVariable(inst.getOperand(i), target)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 估算循环迭代次数
     */
    private long estimateIterations(Loop loop) {
        Value init = loop.getInitValue();
        Value end = loop.getEndValue();
        Value step = loop.getStepValue();
        
        if (init instanceof ConstantInt initConst &&
            end instanceof ConstantInt endConst &&
            step instanceof ConstantInt stepConst) {
            
            long initVal = initConst.getValue();
            long endVal = endConst.getValue();
            long stepVal = stepConst.getValue();
            
            if (stepVal > 0 && endVal > initVal) {
                return (endVal - initVal + stepVal - 1) / stepVal;
            } else if (stepVal < 0 && endVal < initVal) {
                return (initVal - endVal - stepVal - 1) / (-stepVal);
            }
        }
        
        return 100; // 默认估计值
    }
    
    /**
     * 执行循环交换的核心逻辑
     */
    private boolean performInterchange(Loop outer, Loop inner) {
        try {
            if (debug) {
                System.out.println("[LoopInterchange] 执行重排：外层 " + 
                    outer.getHeader().getName() + " 与内层 " + inner.getHeader().getName());
            }
            
            // 简化的交换策略：交换循环的边界值
            if (!interchangeLoopBounds(outer, inner)) {
                return false;
            }
            
            // 重新构建循环条件
            if (!reconstructLoopConditions(outer, inner)) {
                return false;
            }
            
            if (debug) {
                System.out.println("[LoopInterchange] 成功重排循环");
            }
            
            return true;
            
        } catch (Exception e) {
            if (debug) {
                System.out.println("[LoopInterchange] 重排循环时出错: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }
    }
    
    /**
     * 交换循环边界值
     */
    private boolean interchangeLoopBounds(Loop outer, Loop inner) {
        try {
            Value outerIndVar = outer.getInductionVariable();
            Value innerIndVar = inner.getInductionVariable();
            
            if (!(outerIndVar instanceof PhiInstruction outerPhi) ||
                !(innerIndVar instanceof PhiInstruction innerPhi)) {
                return false;
            }
            
            // 交换初始值
            Value outerInit = outer.getInitValue();
            Value innerInit = inner.getInitValue();
            
            // 通过重新设置归纳变量信息来实现交换
            outer.setInductionVariableInfo(outerIndVar, innerInit, 
                inner.getEndValue(), outer.getStepValue(), outer.getUpdateInstruction());
            inner.setInductionVariableInfo(innerIndVar, outerInit,
                outer.getEndValue(), inner.getStepValue(), inner.getUpdateInstruction());
            
            return true;
            
        } catch (Exception e) {
            if (debug) {
                System.out.println("[LoopInterchange] 交换边界值时出错: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 重新构建循环条件
     */
    private boolean reconstructLoopConditions(Loop outer, Loop inner) {
        try {
            BasicBlock outerHeader = outer.getHeader();
            BasicBlock innerHeader = inner.getHeader();
            
            Instruction outerTerminator = outerHeader.getTerminator();
            Instruction innerTerminator = innerHeader.getTerminator();
            
            if (!(outerTerminator instanceof BranchInstruction outerBr) ||
                !(innerTerminator instanceof BranchInstruction innerBr)) {
                return false;
            }
            
            if (outerBr.isUnconditional() || innerBr.isUnconditional()) {
                return false;
            }
            
            // 由于BranchInstruction的条件是final的，我们需要创建新的分支指令
            Value outerCondition = outerBr.getCondition();
            Value innerCondition = innerBr.getCondition();
            
            // 移除旧的分支指令
            outerHeader.removeInstruction(outerBr);
            innerHeader.removeInstruction(innerBr);
            
            // 创建新的分支指令，条件交换
            BranchInstruction newOuterBr = new BranchInstruction(
                innerCondition, outerBr.getTrueBlock(), outerBr.getFalseBlock());
            BranchInstruction newInnerBr = new BranchInstruction(
                outerCondition, innerBr.getTrueBlock(), innerBr.getFalseBlock());
            
            outerHeader.addInstruction(newOuterBr);
            innerHeader.addInstruction(newInnerBr);
            
            return true;
            
        } catch (Exception e) {
            if (debug) {
                System.out.println("[LoopInterchange] 重构循环条件时出错: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 内存访问模式的分析结果
     */
    private static class MemoryAccessPattern {
        final int totalAccesses;
        final int beneficialAccesses;
        
        MemoryAccessPattern(int total, int beneficial) {
            this.totalAccesses = total;
            this.beneficialAccesses = beneficial;
        }
    }
}

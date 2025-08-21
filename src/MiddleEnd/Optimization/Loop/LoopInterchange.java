package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.Value.Argument;
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
        
        // 新增：检查是否为简单的初始化循环，如果是则不进行重排
        if (isSimpleInitializationLoop(outer) || isSimpleInitializationLoop(inner)) {
            return false;
        }
        
        // 新增：检查边界值的兼容性
        if (!areBoundsCompatibleForInterchange(outer, inner)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查是否为简单的初始化循环（通常不应该重排）
     */
    private boolean isSimpleInitializationLoop(Loop loop) {
        // 检查循环体是否主要包含存储指令（数组初始化的特征）
        int storeCount = 0;
        int gepCount = 0;
        int totalInstructions = 0;
        
        for (BasicBlock block : loop.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                totalInstructions++;
                if (inst instanceof StoreInstruction) {
                    storeCount++;
                } else if (inst instanceof GetElementPtrInstruction) {
                    gepCount++;
                }
            }
        }
        
        // 如果存储指令占比很高，认为是初始化循环
        if (totalInstructions > 0 && (double)storeCount / totalInstructions > 0.2) {
            return true;
        }
        
        // 如果包含大量GEP指令（数组访问），认为是数组操作循环
        if (totalInstructions > 0 && (double)gepCount / totalInstructions > 0.1) {
            return true;
        }
        
        // 检查是否为常数边界的简单计数循环
        Value init = loop.getInitValue();
        Value end = loop.getEndValue();
        Value step = loop.getStepValue();
        
        if (init instanceof ConstantInt && 
            end instanceof ConstantInt && 
            step instanceof ConstantInt) {
            ConstantInt stepConst = (ConstantInt) step;
            // 简单的单步递增循环通常是初始化循环
            if (stepConst.getValue() == 1) {
                return true;
            }
        }
        
        // 对于深度超过2的嵌套循环，更加保守
        if (loop.getDepth() > 2) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查两个循环的边界是否兼容交换
     */
    private boolean areBoundsCompatibleForInterchange(Loop outer, Loop inner) {
        Value outerInit = outer.getInitValue();
        Value outerEnd = outer.getEndValue();
        Value innerInit = inner.getInitValue();
        Value innerEnd = inner.getEndValue();
        
        // 要求边界值都是常数，且初始值相同（通常都是0）
        if (!(outerInit instanceof ConstantInt) || 
            !(outerEnd instanceof ConstantInt) ||
            !(innerInit instanceof ConstantInt) || 
            !(innerEnd instanceof ConstantInt)) {
            return false;
        }
        
        ConstantInt outerInitConst = (ConstantInt) outerInit;
        ConstantInt innerInitConst = (ConstantInt) innerInit;
        
        // 初始值必须相同（通常都是0）
        if (outerInitConst.getValue() != innerInitConst.getValue()) {
            return false;
        }
        
        ConstantInt outerEndConst = (ConstantInt) outerEnd;
        ConstantInt innerEndConst = (ConstantInt) innerEnd;
        
        // 如果边界值差距过大，不适合交换
        long outerRange = outerEndConst.getValue() - outerInitConst.getValue();
        long innerRange = innerEndConst.getValue() - innerInitConst.getValue();
        
        // 如果范围差距超过2倍，认为不适合交换
        if (outerRange > innerRange * 2 || innerRange > outerRange * 2) {
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
        return dependsOn(value, target, new HashSet<>());
    }
    
    /**
     * 检查值是否依赖于目标变量（带访问集合避免循环）
     */
    private boolean dependsOn(Value value, Value target, Set<Value> visited) {
        if (value == target) {
            return true;
        }
        
        // 避免循环访问
        if (visited.contains(value)) {
            return false;
        }
        visited.add(value);
        
        if (value instanceof Instruction inst) {
            for (int i = 0; i < inst.getOperandCount(); i++) {
                if (dependsOn(inst.getOperand(i), target, visited)) {
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
        return containsVariable(value, target, new HashSet<>());
    }
    
    /**
     * 检查值中是否包含特定变量（带访问集合避免循环）
     */
    private boolean containsVariable(Value value, Value target, Set<Value> visited) {
        if (value == target) {
            return true;
        }
        
        // 避免循环访问
        if (visited.contains(value)) {
            return false;
        }
        visited.add(value);
        
        if (value instanceof Instruction inst) {
            for (int i = 0; i < inst.getOperandCount(); i++) {
                if (containsVariable(inst.getOperand(i), target, visited)) {
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
            
            Value outerCondition = outerBr.getCondition();
            Value innerCondition = innerBr.getCondition();
            
            // 检查条件是否为比较指令
            if (!(outerCondition instanceof CompareInstruction outerCmp) ||
                !(innerCondition instanceof CompareInstruction innerCmp)) {
                return false;
            }
            
            // 验证比较指令在正确的块中
            if (outerCmp.getParent() != outerHeader || innerCmp.getParent() != innerHeader) {
                return false;
            }
            
            // 检查比较指令的左操作数是否为对应的归纳变量
            Value outerIndVar = outer.getInductionVariable();
            Value innerIndVar = inner.getInductionVariable();
            
            if (outerCmp.getOperand(0) != outerIndVar || innerCmp.getOperand(0) != innerIndVar) {
                return false;
            }
            
            // 获取新的边界值（已在interchangeLoopBounds中交换）
            Value newOuterEnd = outer.getEndValue();
            Value newInnerEnd = inner.getEndValue();
            
            // 检查新边界值的支配关系
            if (!isValueSafeToUse(newOuterEnd, outerHeader) || 
                !isValueSafeToUse(newInnerEnd, innerHeader)) {
                return false;
            }
            
            // 更新比较指令的右操作数（边界值）
            outerCmp.setOperand(1, newOuterEnd);
            innerCmp.setOperand(1, newInnerEnd);
            
            return true;
            
        } catch (Exception e) {
            if (debug) {
                System.out.println("[LoopInterchange] 重构循环条件时出错: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 检查值是否可以安全地在指定块中使用
     */
    private boolean isValueSafeToUse(Value value, BasicBlock block) {
        // 常量和参数总是安全的
        if (value instanceof ConstantInt || value instanceof Argument) {
            return true;
        }
        
        // 指令必须支配使用点
        if (value instanceof Instruction inst) {
            BasicBlock defBlock = inst.getParent();
            if (defBlock == null) {
                return false;
            }
            
            // 如果定义在同一个块中，需要检查指令顺序
            if (defBlock == block) {
                // 简化检查：如果是同一个块，假设定义在使用之前
                return true;
            }
            
            // 对于不同块，进行简单的支配检查
            // 这里使用启发式方法：检查定义块是否在使用块的前驱路径上
            return isBlockDominating(defBlock, block);
        }
        
        return false;
    }
    
    /**
     * 简单的支配关系检查
     */
    private boolean isBlockDominating(BasicBlock dominator, BasicBlock dominated) {
        if (dominator == dominated) {
            return true;
        }
        
        // 使用BFS检查是否存在从函数入口到dominated的路径不经过dominator
        Function function = dominated.getParentFunction();
        Set<BasicBlock> visited = new HashSet<>();
        Queue<BasicBlock> queue = new LinkedList<>();
        
        // 从函数入口开始
        BasicBlock entry = function.getBasicBlocks().get(0);
        queue.offer(entry);
        visited.add(entry);
        
        while (!queue.isEmpty()) {
            BasicBlock current = queue.poll();
            
            // 如果到达目标块且没有经过支配者，则不支配
            if (current == dominated) {
                return false;
            }
            
            // 如果到达支配者，跳过其后继（强制经过）
            if (current == dominator) {
                continue;
            }
            
            // 继续搜索
            for (BasicBlock succ : current.getSuccessors()) {
                if (!visited.contains(succ)) {
                    visited.add(succ);
                    queue.offer(succ);
                }
            }
        }
        
        // 如果没有找到不经过dominator的路径，则dominator支配dominated
        return true;
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
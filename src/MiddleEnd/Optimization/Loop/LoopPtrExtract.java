package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.List;

/**
 * 循环指针访问优化：
 * 将循环中使用循环归纳变量作为数组索引的访问模式转换为直接的指针递增操作，
 * 这样可以减少每次迭代中的地址计算开销。
 */
public class LoopPtrExtract implements Optimizer.ModuleOptimizer {
    
    // 设置基本块数量阈值，超过此阈值的函数将被跳过优化
    private static final int MAX_BLOCK_THRESHOLD = 1000;

    /**
     * 对模块中的所有函数应用优化
     */
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            // 跳过外部函数
            if (function.isExternal()) {
                continue;
            }
            
            // 处理单个函数
            if (processFunction(function)) {
                changed = true;
            }
        }
        
        return changed;
    }

    /**
     * 处理单个函数的循环指针优化
     */
    private boolean processFunction(Function function) {
        // 对于过大的函数直接跳过，避免分析耗时过长
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BLOCK_THRESHOLD) {
            System.out.println("[LoopPtrExtract] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        // 分析循环信息
        LoopAnalysis.runLoopInfo(function);
        // 分析循环变量信息
        LoopAnalysis.runLoopIndVarInfo(function);
        
        // 获取所有循环的DFS遍历顺序（从内到外）
        List<Loop> dfsOrderLoops = LoopAnalysis.getAllLoopsInDFSOrder(function);
        
        // 如果没有循环，直接返回
        if (isEmpty(dfsOrderLoops)) {
            return false;
        }
        
        boolean changed = false;
        // 对每个循环应用指针提取优化
        for (Loop loop : dfsOrderLoops) {
            if (loop != null && processLoopPtrExtract(loop)) {
                changed = true;
            }
        }
        
        return changed;
    }

    /**
     * 对单个循环应用指针提取优化
     * @return 如果发生了优化返回true，否则返回false
     */
    private boolean processLoopPtrExtract(Loop loop) {
        // 如果循环没有归纳变量信息，则无法进行优化
        if (!loop.hasInductionVariable()) {
            return false;
        }
        
        // 验证循环结构是否适合优化
        if (!validateLoopStructure(loop)) {
            return false;
        }
        
        // 获取循环头和循环边信息
        BasicBlock header = loop.getHeader();
        BasicBlock latch = loop.getLatchBlocks().get(0);
        int latchIdx = header.getPredecessors().indexOf(latch);
        BasicBlock preHeader = header.getPredecessors().get(1 - latchIdx);
        
        // 获取循环归纳变量相关信息
        Value indVar = loop.getInductionVariable();
        Value initValue = loop.getInitValue();
        Value endValue = loop.getEndValue();
        Value stepValue = loop.getStepValue();
        Instruction updateInst = loop.getUpdateInstruction();
        
        // 确保更新指令是二元运算
        if (!(updateInst instanceof BinaryInstruction binUpdate)) {
            return false;
        }
        
        // 查找使用归纳变量作为索引的GetElementPtr指令
        GetElementPtrInstruction gepInst = findGEPUsingInductionVar(loop, indVar);
        if (gepInst == null) {
            return false;
        }
        
        // 创建优化后的指针操作指令
        if (createOptimizedPointerInstructions(loop, preHeader, header, latch, 
                gepInst, indVar, initValue, stepValue, updateInst, binUpdate)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 验证循环结构是否适合优化
     */
    private boolean validateLoopStructure(Loop loop) {
        BasicBlock header = loop.getHeader();
        
        // 检查循环头是否有两个前驱（一个是循环外的前驱，一个是循环内的回边）
        if (header.getPredecessors().size() != 2) {
            return false;
        }
        
        // 检查循环是否只有一个回边块
        if (loop.getLatchBlocks().size() != 1) {
            return false;
        }
        
        return true;
    }

    /**
     * 创建优化后的指针操作指令
     */
    private boolean createOptimizedPointerInstructions(Loop loop, BasicBlock preHeader, 
            BasicBlock header, BasicBlock latch, GetElementPtrInstruction gepInst,
            Value indVar, Value initValue, Value stepValue, Instruction updateInst,
            BinaryInstruction binUpdate) {
        
        // 获取GEP的基地址
        Value basePointer = gepInst.getPointer();
        
        // 在循环前创建初始指针
        // 创建初始GEP指令，在循环前置头的末尾插入
        Instruction terminator = preHeader.getTerminator();
        GetElementPtrInstruction initGEP = IRBuilder.createGetElementPtr(basePointer, initValue, null);
        initGEP.insertBefore(terminator);
        
        // 创建PHI节点，用于保存每次迭代的指针值
        PhiInstruction ptrPhi = IRBuilder.createPhi(basePointer.getType(), null);
        header.addInstructionFirst(ptrPhi);
        
        // 设置PHI节点的输入值
        ptrPhi.addIncoming(initGEP, preHeader);
        
        // 创建循环内的指针更新指令
        Value updatePtr = createPointerUpdateInstruction(binUpdate, stepValue, ptrPhi, updateInst);
        
        // 插入指针更新指令到归纳变量更新之后
        ((Instruction)updatePtr).insertAfter(updateInst);
        
        // 完成PHI节点的设置
        ptrPhi.addIncoming(updatePtr, latch);
        
        // 用新的指针PHI节点替换所有使用原GEP指令的地方
        gepInst.replaceAllUsesWith(ptrPhi, ptrPhi);
        
        // 移除原始GEP指令
        gepInst.removeFromParent();
        
        return true;
    }

    /**
     * 创建指针更新指令
     */
    private Value createPointerUpdateInstruction(BinaryInstruction binUpdate, Value stepValue, 
            PhiInstruction ptrPhi, Instruction updateInst) {
        
        // 根据归纳变量的更新方式选择指针更新方式
        if (binUpdate.getOpCode() == OpCode.ADD) {
            // 如果是加法，直接使用步长创建GEP
            return IRBuilder.createGetElementPtr(ptrPhi, stepValue, null);
        } else {
            // 如果是减法，需要创建负的步长
            Value negativeStep = createNegativeStepValue(stepValue, updateInst);
            
            // 使用负步长创建GEP
            return IRBuilder.createGetElementPtr(ptrPhi, negativeStep, null);
        }
    }

    /**
     * 创建负步长值
     */
    private Value createNegativeStepValue(Value stepValue, Instruction updateInst) {
        if (stepValue instanceof ConstantInt constStep) {
            return new ConstantInt(-constStep.getValue());
        } else {
            // 创建乘法指令：step * -1
            Value negativeStep = IRBuilder.createBinaryInst(
                OpCode.MUL, 
                stepValue, 
                new ConstantInt(-1), 
                null
            );
            // 插入乘法指令到更新指令之后
            ((Instruction)negativeStep).insertAfter(updateInst);
            return negativeStep;
        }
    }
    
    /**
     * 查找循环中使用归纳变量作为索引的GetElementPtr指令
     */
    private GetElementPtrInstruction findGEPUsingInductionVar(Loop loop, Value indVar) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (Instruction inst : bb.getInstructions()) {
                if (inst instanceof GetElementPtrInstruction gep) {
                    // 检查是否使用归纳变量作为索引
                    if (gep.getOperandCount() >= 2 && gep.getOperand(1) == indVar) {
                        return gep;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * 检查集合是否为空
     */
    private <T> boolean isEmpty(List<T> list) {
        return list == null || list.isEmpty();
    }

    @Override
    public String getName() {
        return "LoopPtrExtract";
    }
} 
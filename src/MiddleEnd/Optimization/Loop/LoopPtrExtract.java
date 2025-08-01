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
    
    private static final int MAX_BLOCK_THRESHOLD = 1000;

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            if (processFunction(function)) {
                changed = true;
            }
        }
        
        return changed;
    }

    private boolean processFunction(Function function) {
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BLOCK_THRESHOLD) {
            System.out.println("[LoopPtrExtract] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        LoopAnalysis.runLoopInfo(function);
        LoopAnalysis.runLoopIndVarInfo(function);
        
        List<Loop> dfsOrderLoops = LoopAnalysis.getAllLoopsInDFSOrder(function);
        
        if (isEmpty(dfsOrderLoops)) {
            return false;
        }
        
        boolean changed = false;
        for (Loop loop : dfsOrderLoops) {
            if (loop != null && processLoopPtrExtract(loop)) {
                changed = true;
            }
        }
        
        return changed;
    }

    private boolean processLoopPtrExtract(Loop loop) {
        if (!loop.hasInductionVariable()) {
            return false;
        }
        
        if (!validateLoopStructure(loop)) {
            return false;
        }
        
        BasicBlock header = loop.getHeader();
        BasicBlock latch = loop.getLatchBlocks().get(0);
        int latchIdx = header.getPredecessors().indexOf(latch);
        BasicBlock preHeader = header.getPredecessors().get(1 - latchIdx);
        
        Value indVar = loop.getInductionVariable();
        Value initValue = loop.getInitValue();
        Value endValue = loop.getEndValue();
        Value stepValue = loop.getStepValue();
        Instruction updateInst = loop.getUpdateInstruction();

        if (!(updateInst instanceof BinaryInstruction binUpdate)) {
            return false;
        }
        
        GetElementPtrInstruction gepInst = findGEPUsingInductionVar(loop, indVar);
        if (gepInst == null) {
            return false;
        }
        
        if (createOptimizedPointerInstructions(loop, preHeader, header, latch, 
                gepInst, indVar, initValue, stepValue, updateInst, binUpdate)) {
            return true;
        }
        
        return false;
    }
    
    private boolean validateLoopStructure(Loop loop) {
        BasicBlock header = loop.getHeader();
        
        if (header.getPredecessors().size() != 2) {
            return false;
        }
        
        if (loop.getLatchBlocks().size() != 1) {
            return false;
        }
        
        return true;
    }

    private boolean createOptimizedPointerInstructions(Loop loop, BasicBlock preHeader, 
            BasicBlock header, BasicBlock latch, GetElementPtrInstruction gepInst,
            Value indVar, Value initValue, Value stepValue, Instruction updateInst,
            BinaryInstruction binUpdate) {
        
        Value basePointer = gepInst.getPointer();
        
        Instruction terminator = preHeader.getTerminator();
        GetElementPtrInstruction initGEP = IRBuilder.createGetElementPtr(basePointer, initValue, null);
        initGEP.insertBefore(terminator);
        
        PhiInstruction ptrPhi = IRBuilder.createPhi(basePointer.getType(), null);
        header.addInstructionFirst(ptrPhi);
        
        ptrPhi.addIncoming(initGEP, preHeader);
        
        Value updatePtr = createPointerUpdateInstruction(binUpdate, stepValue, ptrPhi, updateInst);
        
        ((Instruction)updatePtr).insertAfter(updateInst);
        
        ptrPhi.addIncoming(updatePtr, latch);
        
        gepInst.replaceAllUsesWith(ptrPhi, ptrPhi);
        
        gepInst.removeFromParent();
        
        return true;
    }

    private Value createPointerUpdateInstruction(BinaryInstruction binUpdate, Value stepValue, 
            PhiInstruction ptrPhi, Instruction updateInst) {
        
        if (binUpdate.getOpCode() == OpCode.ADD) {
            return IRBuilder.createGetElementPtr(ptrPhi, stepValue, null);
        } else {
            Value negativeStep = createNegativeStepValue(stepValue, updateInst);
            
            return IRBuilder.createGetElementPtr(ptrPhi, negativeStep, null);
        }
    }

    private Value createNegativeStepValue(Value stepValue, Instruction updateInst) {
        if (stepValue instanceof ConstantInt constStep) {
            return new ConstantInt(-constStep.getValue());
        } else {
            Value negativeStep = IRBuilder.createBinaryInst(
                OpCode.MUL, 
                stepValue, 
                new ConstantInt(-1), 
                null
            );
            ((Instruction)negativeStep).insertAfter(updateInst);
            return negativeStep;
        }
    }
    
    private GetElementPtrInstruction findGEPUsingInductionVar(Loop loop, Value indVar) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (Instruction inst : bb.getInstructions()) {
                if (inst instanceof GetElementPtrInstruction gep) {
                    if (gep.getOperandCount() >= 2 && gep.getOperand(1) == indVar) {
                        return gep;
                    }
                }
            }
        }
        return null;
    }
    
    private <T> boolean isEmpty(List<T> list) {
        return list == null || list.isEmpty();
    }

    @Override
    public String getName() {
        return "LoopPtrExtract";
    }
} 
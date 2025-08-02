package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.IR.Value.Instructions.BinaryInstruction;
import MiddleEnd.IR.Value.Instructions.CompareInstruction;
import MiddleEnd.IR.Value.Instructions.BranchInstruction;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.OpCode;

import java.util.*;

/**
 * 循环分析工具类
 * 用于识别和分析IR中的循环结构
 */
public class LoopAnalysis {
    
    private static final Map<Function, List<Loop>> functionLoops = new HashMap<>();
    
    private static final Map<BasicBlock, Loop> blockToLoop = new HashMap<>();
    
    public static void runLoopInfo(Function function) {
        try {
            analyzeLoops(function);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static List<Loop> analyzeLoops(Function function) {
        try {
            functionLoops.remove(function);
            
            DominatorAnalysis.computeDominatorTree(function);
            
            List<Loop> allLoops = identifyLoops(function);
            
            List<Loop> topLevelLoops = buildLoopTree(allLoops);
            
            functionLoops.put(function, topLevelLoops);
            
            updateLoopDepths(allLoops);
            
            return topLevelLoops;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    public static void analyzeInductionVariables(Function function) {
        List<Loop> topLoops = getTopLevelLoops(function);
        if (topLoops.isEmpty()) {
            topLoops = analyzeLoops(function);
        }
        
        List<Loop> allLoops = getAllLoopsInDFSOrder(function);
        
        for (Loop loop : allLoops) {
            identifyInductionVariable(loop);
        }
    }
    
    private static void identifyInductionVariable(Loop loop) {
        BasicBlock header = loop.getHeader();
        
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                if (isPotentialInductionVariablePhi(phi, loop)) {
                    Value indVar = phi;
                    Value init = findPhiInitValue(phi, loop);
                    Instruction update = findInductionUpdate(phi, loop);
                    
                    if (update instanceof BinaryInstruction binInst) {
                        Value step = getStepValue(binInst, indVar);
                        Value end = findEndCondition(loop, indVar);
                        
                        if (init != null && step != null) {
                            loop.setInductionVariableInfo(indVar, init, end, step, update);
                            return;
                        }
                    }
                }
            }
        }
    }
    
    private static boolean isPotentialInductionVariablePhi(PhiInstruction phi, Loop loop) {
        if (phi.getOperandCount() != 2) {
            return false;
        }
        
        // 一个输入应来自循环外（初始值），一个来自循环内（更新值）
        boolean hasOutsideInput = false;
        boolean hasInsideInput = false;
        
        for (BasicBlock incomingBlock : phi.getIncomingBlocks()) {
            if (loop.contains(incomingBlock)) {
                hasInsideInput = true;
            } else {
                hasOutsideInput = true;
            }
        }
        
        return hasOutsideInput && hasInsideInput;
    }
    
    private static Value findPhiInitValue(PhiInstruction phi, Loop loop) {
        for (int i = 0; i < phi.getOperandCount(); i++) {
            BasicBlock incomingBlock = phi.getIncomingBlocks().get(i);
            if (!loop.contains(incomingBlock)) {
                return phi.getOperand(i);
            }
        }
        return null;
    }
    
    private static Instruction findInductionUpdate(PhiInstruction phi, Loop loop) {
        for (int i = 0; i < phi.getOperandCount(); i++) {
            BasicBlock incomingBlock = phi.getIncomingBlocks().get(i);
            if (loop.contains(incomingBlock)) {
                Value updateValue = phi.getOperand(i);
                if (updateValue instanceof Instruction inst) {
                    if (inst instanceof BinaryInstruction binInst) {
                        if (binInst.getOpCode() == OpCode.ADD || binInst.getOpCode() == OpCode.SUB) {
                            if (binInst.getLeft() == phi || binInst.getRight() == phi) {
                                return binInst;
                            }
                        }
                    }
                    return inst;
                }
            }
        }
        return null;
    }
    
    private static Value getStepValue(BinaryInstruction binInst, Value indVar) {
        if (binInst.getOpCode() == OpCode.ADD) {
            if (binInst.getLeft() == indVar) {
                return binInst.getRight();
            }
            else if (binInst.getRight() == indVar) {
                return binInst.getLeft();
            }
        } else if (binInst.getOpCode() == OpCode.SUB) {
            if (binInst.getLeft() == indVar) {
                if (binInst.getRight() instanceof ConstantInt constInt) {
                    return new ConstantInt(-constInt.getValue());
                }
                return binInst.getRight();
            }
        }
        return null;
    }
    
    private static Value findEndCondition(Loop loop, Value indVar) {
        BasicBlock header = loop.getHeader();
        Instruction terminator = header.getTerminator();
        
        if (terminator instanceof BranchInstruction brInst && !brInst.isUnconditional()) {
            Value condition = brInst.getCondition();
            if (condition instanceof CompareInstruction cmpInst) {
                if (cmpInst.getLeft() == indVar) {
                    return cmpInst.getRight();
                } else if (cmpInst.getRight() == indVar) {
                    return cmpInst.getLeft();
                }
            }
        }
        
        for (BasicBlock block : loop.getBlocks()) {
            if (block != header) {
                Instruction blockTerminator = block.getTerminator();
                if (blockTerminator instanceof BranchInstruction brInst && !brInst.isUnconditional()) {
                    Value condition = brInst.getCondition();
                    if (condition instanceof CompareInstruction cmpInst) {
                        if (cmpInst.getLeft() == indVar) {
                            return cmpInst.getRight();
                        } else if (cmpInst.getRight() == indVar) {
                            return cmpInst.getLeft();
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    public static List<Loop> getTopLevelLoops(Function function) {
        if (!functionLoops.containsKey(function)) {
            return analyzeLoops(function);
        }
        return functionLoops.get(function);
    }
    
    public static Loop getLoopFor(BasicBlock block) {
        return blockToLoop.get(block);
    }
    
    private static List<Loop> identifyLoops(Function function) {
        List<Loop> loops = new ArrayList<>();
        blockToLoop.clear();
        
        for (BasicBlock header : function.getBasicBlocks()) {
            if (header == null) {
                continue;
            }
            
            List<BasicBlock> predecessors = header.getPredecessors();
            if (predecessors == null) {
                continue;
            }
            
            for (BasicBlock latch : predecessors) {
                if (latch == null) {
                    continue;
                }
                
                try {
                    if (isDominated(latch, header)) {
                        Loop loop = findOrCreateLoop(header, loops);
                        
                        loop.addLatchBlock(latch);
                        
                        collectLoopBlocks(loop, header, latch);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        
        for (Loop loop : loops) {
            try {
                loop.computeExitBlocks();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        return loops;
    }
    
    private static Loop findOrCreateLoop(BasicBlock header, List<Loop> loops) {
        for (Loop loop : loops) {
            if (loop.getHeader() == header) {
                return loop;
            }
        }
        
        Loop newLoop = new Loop(header);
        loops.add(newLoop);
        return newLoop;
    }
    
    private static void collectLoopBlocks(Loop loop, BasicBlock header, BasicBlock latch) {
        Set<BasicBlock> loopBlocks = new HashSet<>();
        Stack<BasicBlock> workList = new Stack<>();
        
        loopBlocks.add(header);
        loop.addBlock(header);
        
        if (latch != header) {
            workList.push(latch);
            loopBlocks.add(latch);
        }
        
        while (!workList.isEmpty()) {
            BasicBlock current = workList.pop();
            loop.addBlock(current);
            
            for (BasicBlock pred : current.getPredecessors()) {
                if (!loopBlocks.contains(pred)) {
                    loopBlocks.add(pred);
                    workList.push(pred);
                }
            }
        }
    }
    
    private static List<Loop> buildLoopTree(List<Loop> allLoops) {
        List<Loop> topLevelLoops = new ArrayList<>();
        
        topLevelLoops.addAll(allLoops);
        
        for (Loop outerLoop : allLoops) {
            for (Loop innerLoop : allLoops) {
                if (outerLoop != innerLoop && isNestedLoop(innerLoop, outerLoop)) {
                    outerLoop.addSubLoop(innerLoop);
                    topLevelLoops.remove(innerLoop);
                }
            }
        }
        
        for (Loop loop : allLoops) {
            for (BasicBlock block : loop.getBlocks()) {
                Loop existingLoop = blockToLoop.get(block);
                if (existingLoop == null || loop.getDepth() > existingLoop.getDepth()) {
                    blockToLoop.put(block, loop);
                }
            }
        }
        
        return topLevelLoops;
    }
    
    private static boolean isNestedLoop(Loop inner, Loop outer) {
        for (BasicBlock block : inner.getBlocks()) {
            if (!outer.contains(block)) {
                return false;
            }
        }
        return !inner.getHeader().equals(outer.getHeader());
    }
    
    private static void updateLoopDepths(List<Loop> loops) {
        for (Loop loop : loops) {
            for (BasicBlock block : loop.getBlocks()) {
                block.setLoopDepth(0);
            }
        }
        
        for (Loop loop : loops) {
            for (BasicBlock block : loop.getBlocks()) {
                block.setLoopDepth(Math.max(block.getLoopDepth(), loop.getDepth() + 1));
            }
        }
    }
    
    private static boolean isDominated(BasicBlock block, BasicBlock header) {
        return isDominatedWithDepth(block, header, 0);
    }
    
    private static boolean isDominatedWithDepth(BasicBlock block, BasicBlock header, int depth) {
        if (depth > 1000) {
            return false;
        }
        
        // 如果block是header本身，则被支配
        if (block == header) {
            return true;
        }
        
        // 如果block的直接支配者是header，则被支配
        BasicBlock idom = block.getIdominator();
        if (idom == header) {
            return true;
        }
        
        // 检查支配者链是否形成循环
        if (idom == block) {
            return false;
        }
        
        // 递归检查block的直接支配者是否被header支配
        if (idom != null) {
            return isDominatedWithDepth(idom, header, depth + 1);
        }
        
        return false;
    }
    
    public static List<Loop> getLoopsInDFSOrder(Loop loop) {
        List<Loop> result = new ArrayList<>();
        dfsTraverseLoops(loop, result);
        return result;
    }
    
    private static void dfsTraverseLoops(Loop loop, List<Loop> result) {
        for (Loop subLoop : loop.getSubLoops()) {
            dfsTraverseLoops(subLoop, result);
        }
        result.add(loop);
    }
    
    public static List<Loop> getAllLoopsInDFSOrder(Function function) {
        List<Loop> topLoops = getTopLevelLoops(function);
        List<Loop> allLoops = new ArrayList<>();
        
        for (Loop topLoop : topLoops) {
            allLoops.addAll(getLoopsInDFSOrder(topLoop));
        }
        
        return allLoops;
    }
    
    public static void runLoopIndVarInfo(Function function) {
        analyzeLoops(function);
        analyzeInductionVariables(function);
    }
} 
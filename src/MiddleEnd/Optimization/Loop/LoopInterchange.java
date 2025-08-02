package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 循环交换优化Pass
 * 将嵌套循环的执行顺序交换，以提高缓存局部性和性能
 */
public class LoopInterchange implements Optimizer.ModuleOptimizer {
    
    private final Map<Value, Value> iterVarToEndMap = new HashMap<>();
    private final Map<Value, Set<Loop>> endToLoopMap = new HashMap<>();
    
    private final IRBuilder builder = new IRBuilder();
    
    private static final int MAX_BLOCK_THRESHOLD = 1000;

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        iterVarToEndMap.clear();
        endToLoopMap.clear();
        
        for (Function function : module.functions()) {
            if (!function.isExternal()) {
                changed |= processFunction(function);
            }
        }
        
        return changed;
    }
    
    private boolean processFunction(Function function) {
        if (function == null) {
            return false;
        }
        
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BLOCK_THRESHOLD) {
            System.out.println("[LoopInterchange] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        runRequiredAnalysis(function);
        
        List<Loop> dfsOrderLoops = getAllLoopsDFS(function);
        if (isEmpty(dfsOrderLoops)) {
            return false;
        }
        
        for (Loop loop : dfsOrderLoops) {
            if (loop != null) {
                analyzeLoopVariables(loop);
            }
        }
        
        boolean changed = false;
        for (Loop loop : dfsOrderLoops) {
            if (loop != null) {
                changed |= tryLoopInterchange(loop);
            }
        }
        
        return changed;
    }
    
    private void runRequiredAnalysis(Function function) {
        LoopAnalysis.runLoopInfo(function);
    }
    
    private List<Loop> getAllLoopsDFS(Function function) {
        List<Loop> dfsOrderLoops = new ArrayList<>();
        List<Loop> topLoops = LoopAnalysis.getTopLevelLoops(function);
        
        if (!isEmpty(topLoops)) {
            for (Loop loop : topLoops) {
                if (loop != null) {
                    dfsOrderLoops.addAll(getDFSLoops(loop));
                }
            }
        }
        
        return dfsOrderLoops;
    }
    
    private List<Loop> getDFSLoops(Loop loop) {
        List<Loop> allLoops = new ArrayList<>();
        for (Loop subLoop : loop.getSubLoops()) {
            if (subLoop != null) {
                allLoops.addAll(getDFSLoops(subLoop));
            }
        }
        allLoops.add(loop);
        return allLoops;
    }
    
    private void analyzeLoopVariables(Loop loop) {
        if (!isSimpleForLoop(loop)) {
            return;
        }
        
        BasicBlock header = loop.getHeader();
        if (header == null || header.getPredecessors().size() != 2) {
            return;
        }
        
        List<BasicBlock> latchBlocks = loop.getLatchBlocks();
        if (isEmpty(latchBlocks) || latchBlocks.size() != 1) {
            return;
        }
        
        BranchInstruction headerBr = findTerminator(header);
        if (headerBr == null || headerBr.isUnconditional()) {
            return;
        }
        
        Value condition = headerBr.getCondition();
        if (!(condition instanceof BinaryInstruction)) {
            return;
        }
        
        BinaryInstruction condInst = (BinaryInstruction) condition;
        if (!isComparisonOp(condInst.getOpCode())) {
            return;
        }
        
        PhiInstruction indVar = null;
        Value endValue = null;
        
        Value left = condInst.getLeft();
        Value right = condInst.getRight();
        
        if (left instanceof PhiInstruction && header.getInstructions().contains(left)) {
            indVar = (PhiInstruction) left;
            endValue = right;
        }
        else if (right instanceof PhiInstruction && header.getInstructions().contains(right)) {
            indVar = (PhiInstruction) right;
            endValue = left;
        }
        
        if (indVar == null || endValue == null) {
            return;
        }
        
        if (indVar.getOperandCount() != 2) {
            return;
        }
        
        iterVarToEndMap.put(indVar, endValue);
        
        if (!endToLoopMap.containsKey(endValue)) {
            endToLoopMap.put(endValue, new HashSet<>());
        }
        endToLoopMap.get(endValue).add(loop);
    }
    
    private boolean isSimpleForLoop(Loop loop) {
        if (loop.getHeader() == null) {
            return false;
        }
        
        if (isEmpty(loop.getLatchBlocks())) {
            return false;
        }
        
        if (isEmpty(loop.getExitBlocks()) || loop.getExitBlocks().size() != 1) {
            return false;
        }
        
        return true;
    }
    
    private BranchInstruction findTerminator(BasicBlock block) {
        List<Instruction> instructions = block.getInstructions();
        if (isEmpty(instructions)) {
            return null;
        }
        
        Instruction lastInst = instructions.get(instructions.size() - 1);
        if (lastInst instanceof BranchInstruction) {
            return (BranchInstruction) lastInst;
        }
        
        return null;
    }
    
    private boolean isComparisonOp(OpCode op) {
        return op == OpCode.ICMP || op == OpCode.FCMP ||
               op == OpCode.EQ || op == OpCode.NE ||
               op == OpCode.SGT || op == OpCode.SGE ||
               op == OpCode.SLT || op == OpCode.SLE ||
               op == OpCode.UEQ || op == OpCode.UNE ||
               op == OpCode.UGT || op == OpCode.UGE ||
               op == OpCode.ULT || op == OpCode.ULE;
    }
    
    private boolean tryLoopInterchange(Loop loop) {
        PhiInstruction indVar = getInductionVar(loop);
        
        if (indVar == null || !iterVarToEndMap.containsKey(indVar)) {
            return false;
        }
        
        BasicBlock header = loop.getHeader();
        if (header == null || header.getPredecessors().size() != 2) {
            return false;
        }
        
        List<BasicBlock> latchBlocks = loop.getLatchBlocks();
        if (isEmpty(latchBlocks) || latchBlocks.size() != 1) {
            return false;
        }
        BasicBlock latch = latchBlocks.get(0);
        
        int latchIdx = header.getPredecessors().indexOf(latch);
        if (latchIdx < 0) {
            return false;
        }
        BasicBlock preHeader = header.getPredecessors().get(1 - latchIdx);
        
        Value initValue = getInitValue(indVar, header, latch);
        if (initValue == null || initValue instanceof Constant) {
            return false;
        }
        
        if (!iterVarToEndMap.containsKey(initValue)) {
            return false;
        }
        
        Set<Loop> outerLoops = endToLoopMap.get(initValue);
        if (isEmpty(outerLoops)) {
            return false;
        }
        
        for (Loop outerLoop : outerLoops) {
            if (outerLoop == null) continue;
            
            if (canInterchangeLoops(loop, outerLoop, preHeader)) {
                return performInterchange(loop, outerLoop);
            }
        }
        
        return false;
    }
    
    private boolean canInterchangeLoops(Loop innerLoop, Loop outerLoop, BasicBlock preHeader) {
        if (isEmpty(outerLoop.getExitBlocks()) || outerLoop.getExitBlocks().size() != 1) {
            return false;
        }
        
        BasicBlock outerExitBlock = outerLoop.getExitBlocks().iterator().next();
        if (outerExitBlock != preHeader) {
            return false;
        }
        
        Value outerStep = getStepValue(outerLoop);
        Value innerStep = getStepValue(innerLoop);
        return areStepValuesCompatible(outerStep, innerStep);
    }
    
    private PhiInstruction getInductionVar(Loop loop) {
        BasicBlock header = loop.getHeader();
        if (header == null) {
            return null;
        }
        
        BranchInstruction br = findTerminator(header);
        if (br == null || br.isUnconditional()) {
            return null;
        }
        
        Value condition = br.getCondition();
        if (!(condition instanceof BinaryInstruction)) {
            return null;
        }
        
        BinaryInstruction condInst = (BinaryInstruction) condition;
        Value left = condInst.getLeft();
        Value right = condInst.getRight();
        
        if (left instanceof PhiInstruction && header.getInstructions().contains(left)) {
            return (PhiInstruction) left;
        } else if (right instanceof PhiInstruction && header.getInstructions().contains(right)) {
            return (PhiInstruction) right;
        }
        
        return null;
    }
    
    private Value getInitValue(PhiInstruction phi, BasicBlock header, BasicBlock latch) {
        if (phi == null || phi.getOperandCount() != 2) {
            return null;
        }
        
        List<BasicBlock> incomingBlocks = phi.getIncomingBlocks();
        if (isEmpty(incomingBlocks)) {
            return null;
        }
        
        int latchIndex = incomingBlocks.indexOf(latch);
        if (latchIndex >= 0) {
            return phi.getOperand(1 - latchIndex);
        }
        
        return null;
    }
    
    private Value getStepValue(Loop loop) {
        PhiInstruction indVar = getInductionVar(loop);
        if (indVar == null) {
            return null;
        }
        
        BasicBlock header = loop.getHeader();
        List<BasicBlock> latchBlocks = loop.getLatchBlocks();
        if (header == null || isEmpty(latchBlocks)) {
            return null;
        }
        
        BasicBlock latch = latchBlocks.get(0);
        if (latch == null) {
            return null;
        }
        
        List<BasicBlock> incomingBlocks = indVar.getIncomingBlocks();
        if (isEmpty(incomingBlocks)) {
            return null;
        }
        
        int latchIndex = incomingBlocks.indexOf(latch);
        if (latchIndex < 0) {
            return null;
        }
        
        Value stepExpr = indVar.getOperand(latchIndex);
        if (!(stepExpr instanceof BinaryInstruction)) {
            return null;
        }
        
        BinaryInstruction binInst = (BinaryInstruction) stepExpr;
        if (binInst.getOpCode() != OpCode.ADD) {
            return null;
        }
        
        if (binInst.getLeft() == indVar) {
            return binInst.getRight();
        } else if (binInst.getRight() == indVar) {
            return binInst.getLeft();
        }
        
        return null;
    }
    
    private boolean areStepValuesCompatible(Value step1, Value step2) {
        if (step1 == null || step2 == null) {
            return false;
        }
        
        if (step1 instanceof ConstantInt && step2 instanceof ConstantInt) {
            return ((ConstantInt) step1).getValue() == ((ConstantInt) step2).getValue();
        }
        
        return step1 == step2;
    }
    
    private boolean performInterchange(Loop innerLoop, Loop outerLoop) {
        PhiInstruction innerVar = getInductionVar(innerLoop);
        PhiInstruction outerVar = getInductionVar(outerLoop);
        if (innerVar == null || outerVar == null) {
            return false;
        }
        
        Value innerEnd = iterVarToEndMap.get(innerVar);
        Value outerEnd = iterVarToEndMap.get(outerVar);
        if (innerEnd == null || outerEnd == null) {
            return false;
        }
        
        BasicBlock innerHeader = innerLoop.getHeader();
        BasicBlock outerHeader = outerLoop.getHeader();
        if (innerHeader == null || outerHeader == null) {
            return false;
        }
        
        BranchInstruction innerBr = findTerminator(innerHeader);
        BranchInstruction outerBr = findTerminator(outerHeader);
        if (innerBr == null || outerBr == null) {
            return false;
        }
        
        Value innerCond = innerBr.getCondition();
        Value outerCond = outerBr.getCondition();
        if (!(innerCond instanceof BinaryInstruction) || !(outerCond instanceof BinaryInstruction)) {
            return false;
        }
        
        BinaryInstruction innerCondInst = (BinaryInstruction) innerCond;
        BinaryInstruction outerCondInst = (BinaryInstruction) outerCond;
        
        return swapLoopBounds(innerLoop, outerLoop, innerVar, outerVar, innerCondInst, outerEnd);
    }
    
    private boolean swapLoopBounds(Loop innerLoop, Loop outerLoop, 
                                 PhiInstruction innerVar, PhiInstruction outerVar, 
                                 BinaryInstruction innerCondInst, Value outerEnd) {
        BasicBlock innerHeader = innerLoop.getHeader();
        BasicBlock outerHeader = outerLoop.getHeader();
        
        Value outerInit = getInitValue(outerVar, outerHeader, outerLoop.getLatchBlocks().get(0));
        Value innerInit = getInitValue(innerVar, innerHeader, innerLoop.getLatchBlocks().get(0));
        
        if (outerInit == null || innerInit == null) {
            return false;
        }
        
        ConstantInt zeroInit = ConstantInt.getZero(IntegerType.I32);
        
        if (innerCondInst.getLeft() == innerVar) {
            innerCondInst.setOperand(1, outerEnd);
        } else {
            innerCondInst.setOperand(0, outerEnd);
        }
        
        for (int i = 0; i < innerVar.getOperandCount(); i++) {
            if (innerVar.getOperand(i) == innerInit) {
                innerVar.setOperand(i, zeroInit);
                break;
            }
        }
        
        System.out.println("[LoopInterchange] Interchanged loops in " + innerHeader.getParentFunction().getName());
        return true;
    }
    
    private <T> boolean isEmpty(List<T> list) {
        return list == null || list.isEmpty();
    }
    
    private <T> boolean isEmpty(Set<T> set) {
        return set == null || set.isEmpty();
    }

    @Override
    public String getName() {
        return "LoopInterchange";
    }
} 
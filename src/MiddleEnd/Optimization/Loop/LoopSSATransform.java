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
 * 循环SSA形式转换
 * 该优化在循环出口处添加Phi节点，确保循环内定义但在循环外使用的变量
 * 通过适当的Phi节点传递，以便于后续的循环优化
 */
public class LoopSSATransform implements Optimizer.ModuleOptimizer {
    
    private static final int MAX_BLOCK_THRESHOLD = 1000;
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
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
            System.out.println("[LoopSSA] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        runRequiredAnalysis(function);
        
        List<Loop> topLoops = LoopAnalysis.getTopLevelLoops(function);
        if (isEmpty(topLoops)) {
            return false;
        }
        
        boolean changed = false;
        for (Loop loop : topLoops) {
            if (loop != null) {
                changed |= processLoop(loop);
            }
        }
        
        return changed;
    }
    
    private void runRequiredAnalysis(Function function) {
        LoopAnalysis.runLoopInfo(function);
        DominatorAnalysis.computeDominatorTree(function);
    }
    
    private boolean processLoop(Loop loop) {
        if (loop == null) {
            return false;
        }
        
        boolean changed = false;
        
        changed |= processSubLoops(loop);
        
        Set<BasicBlock> exitBlocks = loop.getExitBlocks();
        if (isEmpty(exitBlocks)) {
            return changed;
        }
        
        changed |= processLoopInstructions(loop);
        
        return changed;
    }
    
    private boolean processSubLoops(Loop loop) {
        boolean changed = false;
        List<Loop> subLoops = loop.getSubLoops();
        if (!isEmpty(subLoops)) {
            for (Loop subloop : subLoops) {
                if (subloop != null) {
                    changed |= processLoop(subloop);
                }
            }
        }
        return changed;
    }
    
    private boolean processLoopInstructions(Loop loop) {
        boolean changed = false;
        Set<BasicBlock> blocks = loop.getBlocks();
        if (isEmpty(blocks)) {
            return false;
        }
        
        for (BasicBlock bb : blocks) {
            if (bb == null) continue;
            
            List<Instruction> instructions = bb.getInstructions();
            if (isEmpty(instructions)) {
                continue;
            }
            
            for (Instruction inst : instructions) {
                if (inst == null) continue;
                
                if (isUsedOutsideLoop(inst, loop)) {
                    changed |= addPhiNodesAtExits(inst, loop);
                }
            }
        }
        
        return changed;
    }
    
    private boolean isUsedOutsideLoop(Instruction inst, Loop loop) {
        if (inst == null || loop == null || isEmpty(inst.getUsers())) {
            return false;
        }
        
        for (User user : inst.getUsers()) {
            if (user instanceof Instruction userInst) {
                BasicBlock userBlock = getUserBlock(inst, userInst);
                
                if (userBlock != null && !loop.contains(userBlock)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private BasicBlock getUserBlock(Instruction inst, Instruction userInst) {
        if (userInst == null) {
            return null;
        }
        
        BasicBlock userBlock = userInst.getParent();
        if (userBlock == null) {
            return null;
        }
        
        if (userInst instanceof PhiInstruction phi) {
            userBlock = getPhiUserBlock(inst, phi, userBlock);
        }
        
        return userBlock;
    }
    
    private BasicBlock getPhiUserBlock(Instruction inst, PhiInstruction phi, BasicBlock userBlock) {
        int index = -1;
        for (int i = 0; i < phi.getOperandCount(); i++) {
            if (phi.getOperand(i) == inst) {
                index = i;
                break;
            }
        }
        
        if (index >= 0 && !isEmpty(userBlock.getPredecessors()) && 
            index < userBlock.getPredecessors().size()) {
            BasicBlock predBlock = userBlock.getPredecessors().get(index);
            if (predBlock != null) {
                return predBlock;
            }
        }
        
        return userBlock;
    }
    
    private boolean addPhiNodesAtExits(Instruction inst, Loop loop) {
        boolean changed = false;
        
        Map<BasicBlock, PhiInstruction> exitPhiMap = new HashMap<>();
        BasicBlock instBlock = inst.getParent();
        
        if (instBlock == null) {
            return false;
        }
        
        Set<PhiInstruction> newPhis = new HashSet<>();
        
        changed |= createPhiNodesAtExits(inst, loop, instBlock, exitPhiMap, newPhis);
        
        changed |= replaceUsesOutsideLoop(inst, loop, instBlock, exitPhiMap, newPhis);
        
        return changed;
    }
    
    private boolean createPhiNodesAtExits(Instruction inst, Loop loop, 
            BasicBlock instBlock, Map<BasicBlock, PhiInstruction> exitPhiMap, 
            Set<PhiInstruction> newPhis) {
        boolean changed = false;
        
        for (BasicBlock exitBlock : loop.getExitBlocks()) {
            if (exitBlock == null) {
                continue;
            }
            
            if (!exitPhiMap.containsKey(exitBlock) && isDominating(instBlock, exitBlock)) {
                PhiInstruction phi = IRBuilder.createPhi(inst.getType(), exitBlock);
                
                addIncomingFromLoopPreds(phi, inst, exitBlock, loop);
                
                exitPhiMap.put(exitBlock, phi);
                newPhis.add(phi);
                changed = true;
            }
        }
        
        return changed;
    }
    
    private void addIncomingFromLoopPreds(PhiInstruction phi, Instruction value, 
            BasicBlock block, Loop loop) {
        if (isEmpty(block.getPredecessors())) {
            return;
        }
        
        for (BasicBlock pred : block.getPredecessors()) {
            if (pred != null && loop.contains(pred)) {
                phi.addIncoming(value, pred);
            }
        }
    }
    
    private boolean replaceUsesOutsideLoop(Instruction inst, Loop loop, 
            BasicBlock instBlock, Map<BasicBlock, PhiInstruction> exitPhiMap, 
            Set<PhiInstruction> newPhis) {
        boolean changed = false;
        
        List<User> userList = new ArrayList<>(inst.getUsers());
        
        for (User user : userList) {
            if (user instanceof Instruction userInst) {
                BasicBlock userBlock = getUserBlock(inst, userInst);
                
                if (userBlock == null || userBlock == instBlock || 
                    loop.contains(userBlock) || newPhis.contains(user)) {
                    continue;
                }
                
                PhiInstruction phi = getValueInBlock(userBlock, loop, exitPhiMap, inst);
                if (phi != null) {
                    for (int i = 0; i < userInst.getOperandCount(); i++) {
                        if (userInst.getOperand(i) == inst) {
                            userInst.setOperand(i, phi);
                            changed = true;
                        }
                    }
                }
            }
        }
        
        return changed;
    }
    
    private boolean isDominating(BasicBlock dominator, BasicBlock block) {
        if (dominator == null || block == null) {
            return false;
        }
        
        if (dominator == block) {
            return true;
        }
        
        BasicBlock idom = block.getIdominator();
        if (idom == dominator) {
            return true;
        }
        
        return (idom != null && idom != block) && isDominating(dominator, idom);
    }
    
    private PhiInstruction getValueInBlock(BasicBlock block, Loop loop, 
            Map<BasicBlock, PhiInstruction> exitPhiMap, Instruction originalInst) {
        if (block == null) {
            return null;
        }
        
        if (exitPhiMap.containsKey(block)) {
            return exitPhiMap.get(block);
        }
        
        BasicBlock idom = block.getIdominator();
        if (idom == null) {
            return null;
        }
        
        if (!loop.contains(idom)) {
            PhiInstruction value = getValueInBlock(idom, loop, exitPhiMap, originalInst);
            if (value != null) {
                exitPhiMap.put(block, value);
                return value;
            }
            return null;
        }
        
        return createPhiNodeForBlock(block, loop, exitPhiMap, originalInst);
    }
    
    private PhiInstruction createPhiNodeForBlock(BasicBlock block, Loop loop, 
            Map<BasicBlock, PhiInstruction> exitPhiMap, Instruction originalInst) {
        List<Value> values = collectValuesFromPredecessors(block, loop, exitPhiMap, originalInst);
        
        if (isEmpty(values)) {
            return null;
        }
        
        PhiInstruction phi = IRBuilder.createPhi(originalInst.getType(), block);
        
        addIncomingFromPredecessors(phi, block, values);
        
        exitPhiMap.put(block, phi);
        return phi;
    }
    
    private List<Value> collectValuesFromPredecessors(BasicBlock block, Loop loop, 
            Map<BasicBlock, PhiInstruction> exitPhiMap, Instruction originalInst) {
        List<Value> values = new ArrayList<>();
        
        if (isEmpty(block.getPredecessors())) {
            return values;
        }
        
        for (BasicBlock pred : block.getPredecessors()) {
            if (pred == null) {
                continue;
            }
            
            PhiInstruction predValue = getValueInBlock(pred, loop, exitPhiMap, originalInst);
            values.add(predValue != null ? predValue : originalInst);
        }
        
        return values;
    }
    
    private void addIncomingFromPredecessors(PhiInstruction phi, BasicBlock block, List<Value> values) {
        for (int i = 0; i < block.getPredecessors().size(); i++) {
            BasicBlock pred = block.getPredecessors().get(i);
            if (pred == null || i >= values.size()) {
                continue;
            }
            
            phi.addIncoming(values.get(i), pred);
        }
    }

    private <T> boolean isEmpty(List<T> list) {
        return list == null || list.isEmpty();
    }
    
    private <T> boolean isEmpty(Set<T> set) {
        return set == null || set.isEmpty();
    }
    
    @Override
    public String getName() {
        return "loop ssa transform";
    }
} 
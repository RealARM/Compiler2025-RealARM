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
    
    /**
     * 处理单个函数的循环SSA转换
     */
    private boolean processFunction(Function function) {
        if (function == null) {
            return false;
        }
        
        // 对于过大的函数直接跳过，避免支配树分析耗时过长
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BLOCK_THRESHOLD) {
            System.out.println("[LoopSSA] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        // 运行必要的分析
        runRequiredAnalysis(function);
        
        // 获取函数中的顶层循环
        List<Loop> topLoops = LoopAnalysis.getTopLevelLoops(function);
        if (isEmpty(topLoops)) {
            return false;
        }
        
        // 处理每个循环
        boolean changed = false;
        for (Loop loop : topLoops) {
            if (loop != null) {
                changed |= processLoop(loop);
            }
        }
        
        return changed;
    }
    
    /**
     * 运行必要的分析
     */
    private void runRequiredAnalysis(Function function) {
        LoopAnalysis.runLoopInfo(function);
        DominatorAnalysis.computeDominatorTree(function);
    }
    
    /**
     * 为单个循环添加必要的Phi节点
     */
    private boolean processLoop(Loop loop) {
        if (loop == null) {
            return false;
        }
        
        boolean changed = false;
        
        // 先处理所有子循环
        changed |= processSubLoops(loop);
        
        // 获取循环的出口块
        Set<BasicBlock> exitBlocks = loop.getExitBlocks();
        if (isEmpty(exitBlocks)) {
            return changed;  // 没有出口块，不需要添加Phi节点
        }
        
        // 处理循环内的指令
        changed |= processLoopInstructions(loop);
        
        return changed;
    }
    
    /**
     * 处理子循环
     */
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
    
    /**
     * 处理循环内的指令
     */
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
                
                // 检查指令是否在循环外被使用
                if (isUsedOutsideLoop(inst, loop)) {
                    // 在循环出口处添加Phi节点
                    changed |= addPhiNodesAtExits(inst, loop);
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 判断指令是否在循环外被使用
     */
    private boolean isUsedOutsideLoop(Instruction inst, Loop loop) {
        if (inst == null || loop == null || isEmpty(inst.getUsers())) {
            return false;
        }
        
        for (User user : inst.getUsers()) {
            if (user instanceof Instruction userInst) {
                // 获取使用者所在的基本块
                BasicBlock userBlock = getUserBlock(inst, userInst);
                
                // 如果用户块不为空且不在循环内，则该指令在循环外被使用
                if (userBlock != null && !loop.contains(userBlock)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 获取使用者指令所在的实际基本块
     * 对于Phi指令，需要特殊处理，因为操作数实际上是从前驱基本块传递过来的
     */
    private BasicBlock getUserBlock(Instruction inst, Instruction userInst) {
        if (userInst == null) {
            return null;
        }
        
        // 获取使用者所在的基本块
        BasicBlock userBlock = userInst.getParent();
        if (userBlock == null) {
            return null;
        }
        
        // 如果使用者是Phi指令，需要特殊处理
        if (userInst instanceof PhiInstruction phi) {
            userBlock = getPhiUserBlock(inst, phi, userBlock);
        }
        
        return userBlock;
    }
    
    /**
     * 获取Phi指令使用者所在的实际基本块
     */
    private BasicBlock getPhiUserBlock(Instruction inst, PhiInstruction phi, BasicBlock userBlock) {
        // 找到被使用指令在Phi操作数中的索引
        int index = -1;
        for (int i = 0; i < phi.getOperandCount(); i++) {
            if (phi.getOperand(i) == inst) {
                index = i;
                break;
            }
        }
        
        // 如果找到了索引，并且索引有效且前驱列表不为空
        if (index >= 0 && !isEmpty(userBlock.getPredecessors()) && 
            index < userBlock.getPredecessors().size()) {
            BasicBlock predBlock = userBlock.getPredecessors().get(index);
            // 确保前驱块不为null
            if (predBlock != null) {
                return predBlock;
            }
        }
        
        return userBlock;
    }
    
    /**
     * 在循环出口处添加Phi节点
     */
    private boolean addPhiNodesAtExits(Instruction inst, Loop loop) {
        boolean changed = false;
        
        // 存储每个出口块创建的Phi节点，避免重复创建
        Map<BasicBlock, PhiInstruction> exitPhiMap = new HashMap<>();
        // 获取指令所在的基本块
        BasicBlock instBlock = inst.getParent();
        
        if (instBlock == null) {
            return false;
        }
        
        // 记录新创建的Phi指令，避免递归替换
        Set<PhiInstruction> newPhis = new HashSet<>();
        
        // 在每个循环出口处添加Phi节点
        changed |= createPhiNodesAtExits(inst, loop, instBlock, exitPhiMap, newPhis);
        
        // 替换循环外的使用
        changed |= replaceUsesOutsideLoop(inst, loop, instBlock, exitPhiMap, newPhis);
        
        return changed;
    }
    
    /**
     * 在循环出口处创建Phi节点
     */
    private boolean createPhiNodesAtExits(Instruction inst, Loop loop, 
            BasicBlock instBlock, Map<BasicBlock, PhiInstruction> exitPhiMap, 
            Set<PhiInstruction> newPhis) {
        boolean changed = false;
        
        for (BasicBlock exitBlock : loop.getExitBlocks()) {
            if (exitBlock == null) {
                continue;
            }
            
            // 检查指令是否支配出口块
            if (!exitPhiMap.containsKey(exitBlock) && isDominating(instBlock, exitBlock)) {
                // 创建出口块的Phi指令
                PhiInstruction phi = IRBuilder.createPhi(inst.getType(), exitBlock);
                
                // 添加来自循环内所有前驱的输入
                addIncomingFromLoopPreds(phi, inst, exitBlock, loop);
                
                // 记录创建的Phi节点
                exitPhiMap.put(exitBlock, phi);
                newPhis.add(phi);
                changed = true;
            }
        }
        
        return changed;
    }
    
    /**
     * 添加来自循环内前驱的输入
     */
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
    
    /**
     * 替换循环外的使用
     */
    private boolean replaceUsesOutsideLoop(Instruction inst, Loop loop, 
            BasicBlock instBlock, Map<BasicBlock, PhiInstruction> exitPhiMap, 
            Set<PhiInstruction> newPhis) {
        boolean changed = false;
        
        // 创建一个使用者列表的副本，因为我们会修改使用关系
        List<User> userList = new ArrayList<>(inst.getUsers());
        
        for (User user : userList) {
            if (user instanceof Instruction userInst) {
                BasicBlock userBlock = getUserBlock(inst, userInst);
                
                if (userBlock == null || userBlock == instBlock || 
                    loop.contains(userBlock) || newPhis.contains(user)) {
                    continue;
                }
                
                // 找到合适的Phi节点替换原始指令
                PhiInstruction phi = getValueInBlock(userBlock, loop, exitPhiMap, inst);
                if (phi != null) {
                    // 替换使用关系
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
    
    /**
     * 检查块A是否支配块B
     */
    private boolean isDominating(BasicBlock dominator, BasicBlock block) {
        if (dominator == null || block == null) {
            return false;
        }
        
        // 如果两个块相同，则支配关系成立
        if (dominator == block) {
            return true;
        }
        
        // 如果块的直接支配者是dominator，则支配关系成立
        BasicBlock idom = block.getIdominator();
        if (idom == dominator) {
            return true;
        }
        
        // 递归检查块的直接支配者是否被dominator支配
        return (idom != null && idom != block) && isDominating(dominator, idom);
    }
    
    /**
     * 在给定块中获取用于替换原始指令的Phi节点
     */
    private PhiInstruction getValueInBlock(BasicBlock block, Loop loop, 
            Map<BasicBlock, PhiInstruction> exitPhiMap, Instruction originalInst) {
        if (block == null) {
            return null;
        }
        
        // 如果块是出口块，直接返回对应的Phi节点
        if (exitPhiMap.containsKey(block)) {
            return exitPhiMap.get(block);
        }
        
        // 获取块的直接支配者
        BasicBlock idom = block.getIdominator();
        if (idom == null) {
            return null;
        }
        
        // 如果直接支配者不在循环中，递归查找
        if (!loop.contains(idom)) {
            PhiInstruction value = getValueInBlock(idom, loop, exitPhiMap, originalInst);
            if (value != null) {
                exitPhiMap.put(block, value);
                return value;
            }
            return null;
        }
        
        // 如果直接支配者在循环中，需要创建新的Phi节点
        return createPhiNodeForBlock(block, loop, exitPhiMap, originalInst);
    }
    
    /**
     * 为块创建新的Phi节点
     */
    private PhiInstruction createPhiNodeForBlock(BasicBlock block, Loop loop, 
            Map<BasicBlock, PhiInstruction> exitPhiMap, Instruction originalInst) {
        List<Value> values = collectValuesFromPredecessors(block, loop, exitPhiMap, originalInst);
        
        if (isEmpty(values)) {
            return null;
        }
        
        // 创建新的Phi节点
        PhiInstruction phi = IRBuilder.createPhi(originalInst.getType(), block);
        
        // 添加前驱输入
        addIncomingFromPredecessors(phi, block, values);
        
        exitPhiMap.put(block, phi);
        return phi;
    }
    
    /**
     * 收集前驱块的值
     */
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
    
    /**
     * 添加来自前驱的输入
     */
    private void addIncomingFromPredecessors(PhiInstruction phi, BasicBlock block, List<Value> values) {
        for (int i = 0; i < block.getPredecessors().size(); i++) {
            BasicBlock pred = block.getPredecessors().get(i);
            if (pred == null || i >= values.size()) {
                continue;
            }
            
            phi.addIncoming(values.get(i), pred);
        }
    }
    
    /**
     * 检查集合是否为空
     */
    private <T> boolean isEmpty(List<T> list) {
        return list == null || list.isEmpty();
    }
    
    /**
     * 检查集合是否为空
     */
    private <T> boolean isEmpty(Set<T> set) {
        return set == null || set.isEmpty();
    }
    
    @Override
    public String getName() {
        return "loop ssa transform";
    }
} 
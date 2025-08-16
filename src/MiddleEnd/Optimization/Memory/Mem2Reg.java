package MiddleEnd.Optimization.Memory;

import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Type.*;

import java.util.*;

/**
 * Mem2Reg优化Pass - 将内存访问转换为寄存器操作
 * 参考SSA构造算法，将alloca/load/store序列转换为直接的值传递
 */
public class Mem2Reg implements Optimizer.ModuleOptimizer {
    
    private static final boolean DEBUG = false;
    private static final int MAX_BASIC_BLOCKS = 1000;
    
    private OptimizationContext context;
    
    /**
     * Mem2Reg相关数据结构
     */
    private static class OptimizationContext {
        Function currentFunction;
        
        Map<BasicBlock, Set<BasicBlock>> dominators;
        Map<BasicBlock, BasicBlock> idoms;
        Map<BasicBlock, Set<BasicBlock>> dominanceFrontiers;
        
        Map<AllocaInstruction, List<BasicBlock>> defBlocks;
        List<AllocaInstruction> allocsToPromote;
        Map<PhiInstruction, AllocaInstruction> phiToAlloca;
        Set<Instruction> instructionsToDelete;
        
        OptimizationContext(Function function) {
            this.currentFunction = function;
            initializeDataStructures();
        }
        
        private void initializeDataStructures() {
            defBlocks = new LinkedHashMap<>();
            allocsToPromote = new ArrayList<>();
            phiToAlloca = new LinkedHashMap<>();
            instructionsToDelete = new LinkedHashSet<>();
        }
        
        void reset() {
            initializeDataStructures();
            dominators = null;
            idoms = null;
            dominanceFrontiers = null;
        }
    }
    
    @Override
    public String getName() {
        return "Mem2Reg";
    }

    @Override
    public boolean run(Module module) {
        debug("=== 开始运行 Mem2Reg 优化 ===");
        
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            debug("处理函数: " + function.getName());
            
            boolean functionChanged = runOnFunction(function);
            changed |= functionChanged;
        }
        
        debug("=== Mem2Reg 优化完成，模块" + (changed ? "已修改" : "未修改") + " ===");
        return changed;
    }
    
    private boolean runOnFunction(Function function) {
        if (!shouldOptimizeFunction(function)) {
            return false;
        }
        
        // 首先提升所有alloca到入口块，确保支配关系
        hoistAllocasToEntry(function);
        
        context = new OptimizationContext(function);
        
        try {
            return executeOptimizationSteps();
        } finally {
            context = null;
        }
    }
    
    private boolean shouldOptimizeFunction(Function function) {
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BASIC_BLOCKS) {
            debug("  跳过函数 " + function.getName() + " 的Mem2Reg优化，基本块数量过多: " + blockCount);
            return false;
        }
        
        debug("  函数 " + function.getName() + " 有 " + blockCount + " 个基本块，继续Mem2Reg优化");
        return true;
    }
    
    private boolean executeOptimizationSteps() {
        performDominanceAnalysis();
        
        collectPromotableAllocas();
        
        if (context.allocsToPromote.isEmpty()) {
            debug("  函数 " + context.currentFunction.getName() + " 中没有可优化的alloca指令");
            return false;
        }
        
        debug("  找到 " + context.allocsToPromote.size() + " 个可优化的alloca指令");
        
        collectDefBlocks();
        
        insertPhiInstructions();
        
        renameVariables();
        
        cleanupInstructions();
        
        debug("  函数 " + context.currentFunction.getName() + " 的Mem2Reg优化完成");
        return true;
    }
    
    private void debug(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }
    
    private void performDominanceAnalysis() {
        debug("    运行支配分析...");
        
        Function function = context.currentFunction;
        
        context.dominators = DominatorAnalysis.computeDominators(function);
        context.idoms = DominatorAnalysis.computeImmediateDominators(context.dominators);
        context.dominanceFrontiers = DominatorAnalysis.computeDominanceFrontiers(
            function, context.dominators, context.idoms);
        
        updateBasicBlockDominanceInfo();
    }

    private void updateBasicBlockDominanceInfo() {
        for (Map.Entry<BasicBlock, BasicBlock> entry : context.idoms.entrySet()) {
            entry.getKey().setIdominator(entry.getValue());
        }
    }
    
    private void collectPromotableAllocas() {
        debug("    收集可提升的alloca指令...");
        
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof AllocaInstruction alloca && isPromotableAlloca(alloca)) {
                    addPromotableAlloca(alloca);
                }
            }
        }
    }
    
    private boolean isPromotableAlloca(AllocaInstruction alloca) {
        // 只处理简单的int和float类型，不处理数组
        return !alloca.isArrayAllocation() && 
               (alloca.getAllocatedType() instanceof IntegerType || 
                alloca.getAllocatedType() instanceof FloatType);
    }
    
    private void addPromotableAlloca(AllocaInstruction alloca) {
        context.defBlocks.put(alloca, new ArrayList<>());
        context.allocsToPromote.add(alloca);
        
        debug("      发现可提升的alloca: " + alloca.getName() + 
              " (类型: " + alloca.getAllocatedType() + ")");
    }
    
    private void collectDefBlocks() {
        debug("    收集定义基本块信息...");
        
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof StoreInstruction store) {
                    Value pointer = store.getPointer();
                    if (pointer instanceof AllocaInstruction alloca && 
                        context.defBlocks.containsKey(alloca)) {
                        
                        context.defBlocks.get(alloca).add(block);
                        
                        debug("      alloca " + alloca.getName() + 
                             " 在基本块 " + block.getName() + " 中被定义");
                    }
                }
            }
        }
        
        context.allocsToPromote.removeIf(alloca -> context.defBlocks.get(alloca).isEmpty());
        if (DEBUG && context.allocsToPromote.size() < context.defBlocks.size()) {
            debug("    删除了 " + (context.defBlocks.size() - context.allocsToPromote.size()) + " 个无定义的alloca");
        }
    }
    
    private void insertPhiInstructions() {
        debug("    插入Phi指令...");
        
        for (AllocaInstruction alloca : context.allocsToPromote) {
            debug("      为alloca " + alloca.getName() + " 插入Phi指令");
            
            Queue<BasicBlock> workList = new LinkedList<>(context.defBlocks.get(alloca));
            Set<BasicBlock> phiInserted = new HashSet<>();
            
            while (!workList.isEmpty()) {
                BasicBlock defBlock = workList.poll();
                
                Set<BasicBlock> frontiers = context.dominanceFrontiers.get(defBlock);
                if (frontiers == null) {
                    continue;
                }
                
                for (BasicBlock frontier : frontiers) {
                    if (!phiInserted.contains(frontier)) {
                        PhiInstruction phi = IRBuilder.createPhi(alloca.getAllocatedType(), frontier);

                        List<BasicBlock> predecessors = frontier.getPredecessors();
                        debug("          创建Phi指令 " + phi.getName() + " 在 " + frontier.getName());
                        debug("          前驱基本块数量: " + predecessors.size());
                        
                        for (int i = 0; i < predecessors.size(); i++) {
                            BasicBlock pred = predecessors.get(i);
                            Value initialValue;
                            if (alloca.getAllocatedType() instanceof IntegerType) {
                                initialValue = new ConstantInt(0, (IntegerType) alloca.getAllocatedType());
                            } else {
                                initialValue = new ConstantFloat(0.0);
                            }
                            
                            phi.addOperand(initialValue);
                            
                            debug("          为前驱 " + pred.getName() + " (索引" + i + ") 添加初始值 " + initialValue.getName());
                        }
                        
                        context.phiToAlloca.put(phi, alloca);
                        phiInserted.add(frontier);
                        
                        if (!context.defBlocks.get(alloca).contains(frontier)) {
                            workList.add(frontier);
                        }
                        
                        debug("        在基本块 " + frontier.getName() + 
                             " 中插入Phi指令: " + phi.getName());
                    }
                }
            }
        }
    }
    
    private void renameVariables() {
        debug("    进行变量重命名...");
        
        Map<AllocaInstruction, Value> currentValues = initializeCurrentValues();
        
        BasicBlock entryBlock = context.currentFunction.getEntryBlock();
        if (entryBlock != null) {
            Set<BasicBlock> visited = new HashSet<>();
            renameInBlock(entryBlock, currentValues, visited);
        }
    }
    
    private Map<AllocaInstruction, Value> initializeCurrentValues() {
        Map<AllocaInstruction, Value> currentValues = new LinkedHashMap<>();
        
        for (AllocaInstruction alloca : context.allocsToPromote) {
            Value initialValue = createInitialValue(alloca.getAllocatedType());
            if (initialValue != null) {
                currentValues.put(alloca, initialValue);
            }
        }
        
        return currentValues;
    }
    
    private Value createInitialValue(Type type) {
        if (type instanceof IntegerType) {
            return new ConstantInt(0, (IntegerType) type);
        } else if (type instanceof FloatType) {
            return new ConstantFloat(0.0);
        } else {
            return null;
        }
    }
    
    private void renameInBlock(BasicBlock block, Map<AllocaInstruction, Value> currentValues, 
                              Set<BasicBlock> visited) {
        
        if (visited.contains(block)) {
            return;
        }
        visited.add(block);
        
        Map<AllocaInstruction, Value> savedValues = new LinkedHashMap<>(currentValues);
        
        debug("      在基本块 " + block.getName() + " 中进行重命名");

        processInstructionsInBlock(block, currentValues);
        
        updatePhiInSuccessors(block, currentValues);
        
        for (BasicBlock child : getImmediateDominatedBlocks(block)) {
            renameInBlock(child, currentValues, visited);
        }
        
        currentValues.clear();
        currentValues.putAll(savedValues);
    }
    
    private void processInstructionsInBlock(BasicBlock block, Map<AllocaInstruction, Value> currentValues) {
        List<Instruction> instructions = new ArrayList<>(block.getInstructions());
        for (Instruction inst : instructions) {
            processInstruction(inst, currentValues);
        }
    }
    
    private void processInstruction(Instruction inst, Map<AllocaInstruction, Value> currentValues) {
        if (inst instanceof AllocaInstruction alloca) {
            processAllocaInstruction(alloca);
        } else if (inst instanceof LoadInstruction load) {
            processLoadInstruction(load, currentValues);
        } else if (inst instanceof StoreInstruction store) {
            processStoreInstruction(store, currentValues);
        } else if (inst instanceof PhiInstruction phi) {
            processPhiInstruction(phi, currentValues);
        }
    }
    
    private void processAllocaInstruction(AllocaInstruction alloca) {
        if (context.allocsToPromote.contains(alloca)) {
            context.instructionsToDelete.add(alloca);
            debug("        标记删除alloca: " + alloca.getName());
        }
    }
    
    private void processLoadInstruction(LoadInstruction load, Map<AllocaInstruction, Value> currentValues) {
        Value pointer = load.getPointer();
        if (isPromotableAllocaUse(pointer)) {
            AllocaInstruction alloca = (AllocaInstruction) pointer;
            Value currentValue = currentValues.get(alloca);
            if (currentValue != null) {
                replaceAllUses(load, currentValue);
                context.instructionsToDelete.add(load);
                debug("        替换load " + load.getName() + " 为 " + currentValue.getName());
            }
        }
    }
    
    private void processStoreInstruction(StoreInstruction store, Map<AllocaInstruction, Value> currentValues) {
        Value pointer = store.getPointer();
        if (isPromotableAllocaUse(pointer)) {
            AllocaInstruction alloca = (AllocaInstruction) pointer;
            Value newValue = store.getValue();
            currentValues.put(alloca, newValue);
            context.instructionsToDelete.add(store);
            debug("        更新 " + alloca.getName() + " 的当前值为 " + newValue.getName());
        }
    }
    
    private void processPhiInstruction(PhiInstruction phi, Map<AllocaInstruction, Value> currentValues) {
        AllocaInstruction alloca = context.phiToAlloca.get(phi);
        if (alloca != null) {
            currentValues.put(alloca, phi);
            debug("        Phi指令 " + phi.getName() + " 成为 " + alloca.getName() + " 的当前值");
        }
    }
    
    private boolean isPromotableAllocaUse(Value pointer) {
        return pointer instanceof AllocaInstruction alloca && context.allocsToPromote.contains(alloca);
    }
    
    private void updatePhiInSuccessors(BasicBlock block, Map<AllocaInstruction, Value> currentValues) {
        for (BasicBlock successor : block.getSuccessors()) {
            for (Instruction inst : successor.getInstructions()) {
                if (inst instanceof PhiInstruction phi) {
                    AllocaInstruction alloca = context.phiToAlloca.get(phi);
                    if (alloca != null) {
                        Value currentValue = currentValues.get(alloca);
                        if (currentValue != null) { 
                            List<BasicBlock> predecessors = successor.getPredecessors();
                            int preBbIndex = predecessors.indexOf(block);
                            
                            if (preBbIndex >= 0 && preBbIndex < phi.getOperandCount()) {
                                phi.setOperand(preBbIndex, currentValue);
                                
                                try {
                                    Map<BasicBlock, Value> incomingValues = phi.getIncomingValues();
                                    incomingValues.put(block, currentValue);
                                } catch (Exception e) {
                                }
                                
                                debug("        为Phi指令 " + phi.getName() + 
                                     " 设置索引 " + preBbIndex + " (来自基本块 " + block.getName() + 
                                     ") 的值为 " + currentValue.getName());
                            }
                        }
                    }
                }
            }
        }
    }
    
    private List<BasicBlock> getImmediateDominatedBlocks(BasicBlock dominator) {
        List<BasicBlock> dominated = new ArrayList<>();
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            if (block.getIdominator() == dominator) {
                dominated.add(block);
            }
        }
        return dominated;
    }
    
    private void cleanupInstructions() {
        debug("    清理无用指令...");
        debug("    需要删除 " + context.instructionsToDelete.size() + " 条指令");
        
        for (Instruction inst : context.instructionsToDelete) {
            debug("      删除指令: " + inst.toString());
            
            for (int i = 0; i < inst.getOperandCount(); i++) {
                Value operand = inst.getOperand(i);
                if (operand != null) {
                    operand.getUsers().remove(inst);
                    if (DEBUG && (operand.getName().contains("add_result") || operand.getName().contains("sub_result"))) {
                        debug("        从 " + operand.getName() + 
                             " 的用户列表中移除指令 " + inst.toString());
                        debug("        移除后 " + operand.getName() + 
                             " 的用户数量: " + operand.getUsers().size());
                    }
                }
            }
            
            inst.removeFromParent();
        }
        
        debug("    指令清理完成");
        
        debug("    清理后检查关键值的Use-Def关系:");
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getName().contains("add_result") || inst.getName().contains("sub_result")) {
                    debug("      " + inst.getName() + " 用户数量: " + inst.getUsers().size());
                    debug("      " + inst.getName() + " 用户列表: " + 
                         inst.getUsers().stream().map(u -> u.getClass().getSimpleName() + ":" + u.toString()).toList());
                }
            }
        }
        
        debug("    最终Phi指令状态:");
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof PhiInstruction phi && context.phiToAlloca.containsKey(phi)) {
                    debug("      " + phi.getName() + " 在 " + block.getName() + ": " + phi.toString());
                }
            }
        }

        debug("    检查关键值的Use-Def关系:");
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getName().contains("add_result") || inst.getName().contains("sub_result")) {
                    debug("      " + inst.getName() + " 用户数量: " + inst.getUsers().size());
                    debug("      " + inst.getName() + " 用户列表: " + 
                         inst.getUsers().stream().map(u -> u.getClass().getSimpleName() + ":" + u.toString()).toList());
                }
            }
        }
    }
    
    private void replaceAllUses(Value oldValue, Value newValue) {
        List<User> users = new ArrayList<>(oldValue.getUsers());
        
        debug("        替换 " + oldValue.getName() + " 的所有使用为 " + newValue.getName());
        debug("        使用者数量: " + users.size());
        
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldValue) {
                    debug("          替换在 " + user.toString() + " 中的使用");
                    user.setOperand(i, newValue);
                }
            }
        }
    }
    
    /**
     * 将函数中所有的alloca指令提升到入口块开头，确保支配关系
     */
    private void hoistAllocasToEntry(Function func) {
        BasicBlock entryBlock = func.getEntryBlock();
        if (entryBlock == null) return;
        
        debug("    提升函数 " + func.getName() + " 中的alloca");
        
        List<AllocaInstruction> allocasToHoist = new ArrayList<>();
        
        // 收集所有非入口块中的alloca指令
        for (BasicBlock block : func.getBasicBlocks()) {
            if (block == entryBlock) continue;
            
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            for (Instruction inst : instructions) {
                if (inst instanceof AllocaInstruction allocaInst) {
                    debug("      发现需要提升的alloca: " + allocaInst.getName() + 
                                     " (在块: " + block.getName() + ")");
                    allocasToHoist.add(allocaInst);
                    block.removeInstruction(allocaInst);
                }
            }
        }
        
        // 将alloca指令移动到入口块开头
        for (AllocaInstruction alloca : allocasToHoist) {
            entryBlock.addInstructionFirst(alloca);
            debug("      提升alloca到入口块: " + alloca.getName());
        }
        
        // 确保入口块中所有alloca指令排在最前面（包括原本就在入口块中的alloca）
        List<Instruction> reordered = new ArrayList<>();
        List<Instruction> others = new ArrayList<>();
        for (Instruction inst : new ArrayList<>(entryBlock.getInstructions())) {
            if (inst instanceof AllocaInstruction) {
                reordered.add(inst);
            } else {
                others.add(inst);
            }
        }
        // 清空并重新插入按 allocas -> others 的顺序
        entryBlock.getInstructions().clear();
        for (Instruction inst : reordered) {
            entryBlock.addInstruction(inst);
        }
        for (Instruction inst : others) {
            entryBlock.addInstruction(inst);
        }
        
        if (!allocasToHoist.isEmpty()) {
            debug("    函数 " + func.getName() + " 提升了 " + allocasToHoist.size() + " 个alloca");
        }
    }
}
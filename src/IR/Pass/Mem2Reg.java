package IR.Pass;

import IR.IRBuilder;
import IR.Module;
import IR.Type.IntegerType;
import IR.Type.FloatType;
import IR.Value.*;
import IR.Value.Instructions.*;
import IR.Pass.Utils.DominatorAnalysis;

import java.util.*;

/**
 * Mem2Reg优化Pass - 将内存访问转换为寄存器操作
 * 参考SSA构造算法，将alloca/load/store序列转换为直接的值传递
 */
public class Mem2Reg implements Pass.IRPass {
    
    // 配置常量
    private static final boolean DEBUG = false;
    private static final int MAX_BASIC_BLOCKS = 1000; // 最大处理的基本块数量
    
    // 优化状态管理
    private OptimizationContext context;
    
    /**
     * Mem2Reg相关数据结构
     */
    private static class OptimizationContext {
        // 当前处理的函数
        Function currentFunction;
        
        // 支配分析结果
        Map<BasicBlock, Set<BasicBlock>> dominators;
        Map<BasicBlock, BasicBlock> idoms;
        Map<BasicBlock, Set<BasicBlock>> dominanceFrontiers;
        
        // Mem2Reg相关数据结构
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
        
        // 对每个非库函数运行Mem2Reg
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue; // 跳过库函数
            }
            
            debug("处理函数: " + function.getName());
            
            boolean functionChanged = runOnFunction(function);
            changed |= functionChanged;
        }
        
        debug("=== Mem2Reg 优化完成，模块" + (changed ? "已修改" : "未修改") + " ===");
        return changed;
    }
    
    /**
     * 对单个函数运行Mem2Reg优化
     */
    private boolean runOnFunction(Function function) {
        // 性能检查：跳过过大的函数
        if (!shouldOptimizeFunction(function)) {
            return false;
        }
        
        // 初始化优化上下文
        context = new OptimizationContext(function);
        
        try {
            // 执行优化流程
            return executeOptimizationSteps();
        } finally {
            // 清理上下文
            context = null;
        }
    }
    
    /**
     * 检查是否应该优化该函数
     */
    private boolean shouldOptimizeFunction(Function function) {
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BASIC_BLOCKS) {
            debug("  跳过函数 " + function.getName() + " 的Mem2Reg优化，基本块数量过多: " + blockCount);
            return false;
        }
        
        debug("  函数 " + function.getName() + " 有 " + blockCount + " 个基本块，继续Mem2Reg优化");
        return true;
    }
    
    /**
     * 执行优化步骤
     */
    private boolean executeOptimizationSteps() {
        // 第一步：进行支配分析
        performDominanceAnalysis();
        
        // 第二步：收集可以优化的alloca指令
        collectPromotableAllocas();
        
        // 检查是否有可优化的alloca
        if (context.allocsToPromote.isEmpty()) {
            debug("  函数 " + context.currentFunction.getName() + " 中没有可优化的alloca指令");
            return false;
        }
        
        debug("  找到 " + context.allocsToPromote.size() + " 个可优化的alloca指令");
        
        // 第三步：收集定义信息
        collectDefBlocks();
        
        // 第四步：插入Phi指令
        insertPhiInstructions();
        
        // 第五步：变量重命名
        renameVariables();
        
        // 第六步：清理无用指令
        cleanupInstructions();
        
        debug("  函数 " + context.currentFunction.getName() + " 的Mem2Reg优化完成");
        return true;
    }
    
    /**
     * 统一的调试输出方法
     */
    private void debug(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }
    
    /**
     * 运行支配分析
     */
    private void performDominanceAnalysis() {
        debug("    运行支配分析...");
        
        Function function = context.currentFunction;
        
        // 计算支配关系
        context.dominators = DominatorAnalysis.computeDominators(function);
        
        // 计算直接支配关系
        context.idoms = DominatorAnalysis.computeImmediateDominators(context.dominators);
        
        // 计算支配边界
        context.dominanceFrontiers = DominatorAnalysis.computeDominanceFrontiers(
            function, context.dominators, context.idoms);
        
        // 设置BasicBlock的idominator信息
        updateBasicBlockDominanceInfo();
    }
    
    /**
     * 更新基本块的支配信息
     */
    private void updateBasicBlockDominanceInfo() {
        for (Map.Entry<BasicBlock, BasicBlock> entry : context.idoms.entrySet()) {
            entry.getKey().setIdominator(entry.getValue());
        }
    }
    
    /**
     * 收集可以提升的alloca指令
     * 只处理简单的int和float类型变量，不处理数组
     */
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
    
    /**
     * 判断alloca是否可以提升
     */
    private boolean isPromotableAlloca(AllocaInstruction alloca) {
        // 只处理简单的int和float类型，不处理数组
        return !alloca.isArrayAllocation() && 
               (alloca.getAllocatedType() instanceof IntegerType || 
                alloca.getAllocatedType() instanceof FloatType);
    }
    
    /**
     * 添加可提升的alloca
     */
    private void addPromotableAlloca(AllocaInstruction alloca) {
        context.defBlocks.put(alloca, new ArrayList<>());
        context.allocsToPromote.add(alloca);
        
        debug("      发现可提升的alloca: " + alloca.getName() + 
              " (类型: " + alloca.getAllocatedType() + ")");
    }
    
    /**
     * 收集定义基本块信息
     * 找出每个alloca在哪些基本块中被store指令定义
     */
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
        
        // 删除没有定义的alloca（这些是无用的）
        context.allocsToPromote.removeIf(alloca -> context.defBlocks.get(alloca).isEmpty());
        if (DEBUG && context.allocsToPromote.size() < context.defBlocks.size()) {
            debug("    删除了 " + (context.defBlocks.size() - context.allocsToPromote.size()) + " 个无定义的alloca");
        }
    }
    
    /**
     * 插入Phi指令
     * 使用支配边界算法确定Phi指令的插入位置
     */
    private void insertPhiInstructions() {
        debug("    插入Phi指令...");
        
        // 对每个需要提升的alloca指令
        for (AllocaInstruction alloca : context.allocsToPromote) {
            debug("      为alloca " + alloca.getName() + " 插入Phi指令");
            
            // 使用工作列表算法插入Phi指令
            Queue<BasicBlock> workList = new LinkedList<>(context.defBlocks.get(alloca));
            Set<BasicBlock> phiInserted = new HashSet<>();
            
            while (!workList.isEmpty()) {
                BasicBlock defBlock = workList.poll();
                
                // 获取此定义块的支配边界
                Set<BasicBlock> frontiers = context.dominanceFrontiers.get(defBlock);
                if (frontiers == null) {
                    continue;
                }
                
                // 在支配边界的每个块中插入Phi指令
                for (BasicBlock frontier : frontiers) {
                    if (!phiInserted.contains(frontier)) {
                        // 创建Phi指令，为每个前驱基本块设置初始值
                        PhiInstruction phi = IRBuilder.createPhi(alloca.getAllocatedType(), frontier);

                        // 为每个前驱基本块使用addIncoming正确添加映射关系
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
                            
                            // 直接使用addOperand来确保建立Use-Def关系
                            phi.addOperand(initialValue);
                            
                            debug("          为前驱 " + pred.getName() + " (索引" + i + ") 添加初始值 " + initialValue.getName());
                        }
                        
                        // 建立Phi到alloca的映射
                        context.phiToAlloca.put(phi, alloca);
                        phiInserted.add(frontier);
                        
                        // 如果此边界块不在原始定义块列表中，添加到工作列表
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
    
    /**
     * 变量重命名
     * 实现SSA形式的变量重命名
     */
    private void renameVariables() {
        debug("    进行变量重命名...");
        
        // 初始化当前值映射
        Map<AllocaInstruction, Value> currentValues = initializeCurrentValues();
        
        // 从入口基本块开始深度优先遍历支配树
        BasicBlock entryBlock = context.currentFunction.getEntryBlock();
        if (entryBlock != null) {
            Set<BasicBlock> visited = new HashSet<>();
            renameInBlock(entryBlock, currentValues, visited);
        }
    }
    
    /**
     * 初始化当前值映射
     */
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
    
    /**
     * 根据类型创建初始值
     */
    private Value createInitialValue(IR.Type.Type type) {
        if (type instanceof IntegerType) {
            return new ConstantInt(0, (IntegerType) type);
        } else if (type instanceof FloatType) {
            return new ConstantFloat(0.0);
        } else {
            // 这不应该发生，因为我们在collectPromotableAllocas中已经过滤了
            return null;
        }
    }
    
    /**
     * 在单个基本块中进行变量重命名
     */
    private void renameInBlock(BasicBlock block, Map<AllocaInstruction, Value> currentValues, 
                              Set<BasicBlock> visited) {
        
        if (visited.contains(block)) {
            return;
        }
        visited.add(block);
        
        // 保存当前值的副本，用于回溯
        Map<AllocaInstruction, Value> savedValues = new LinkedHashMap<>(currentValues);
        
        debug("      在基本块 " + block.getName() + " 中进行重命名");
        
        // 处理当前基本块中的所有指令
        processInstructionsInBlock(block, currentValues);
        
        // 更新后继基本块中的phi指令
        updatePhiInSuccessors(block, currentValues);
        
        // 递归处理支配树中的子节点
        for (BasicBlock child : getImmediateDominatedBlocks(block)) {
            renameInBlock(child, currentValues, visited);
        }
        
        // 恢复保存的值（回溯）
        currentValues.clear();
        currentValues.putAll(savedValues);
    }
    
    /**
     * 处理基本块中的所有指令
     */
    private void processInstructionsInBlock(BasicBlock block, Map<AllocaInstruction, Value> currentValues) {
        List<Instruction> instructions = new ArrayList<>(block.getInstructions());
        for (Instruction inst : instructions) {
            processInstruction(inst, currentValues);
        }
    }
    
    /**
     * 处理单个指令
     */
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
    
    /**
     * 处理 Alloca 指令
     */
    private void processAllocaInstruction(AllocaInstruction alloca) {
        if (context.allocsToPromote.contains(alloca)) {
            context.instructionsToDelete.add(alloca);
            debug("        标记删除alloca: " + alloca.getName());
        }
    }
    
    /**
     * 处理 Load 指令
     */
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
    
    /**
     * 处理 Store 指令
     */
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
    
    /**
     * 处理 Phi 指令
     */
    private void processPhiInstruction(PhiInstruction phi, Map<AllocaInstruction, Value> currentValues) {
        AllocaInstruction alloca = context.phiToAlloca.get(phi);
        if (alloca != null) {
            currentValues.put(alloca, phi);
            debug("        Phi指令 " + phi.getName() + " 成为 " + alloca.getName() + " 的当前值");
        }
    }
    
    /**
     * 检查是否为可提升的 alloca 使用
     */
    private boolean isPromotableAllocaUse(Value pointer) {
        return pointer instanceof AllocaInstruction alloca && context.allocsToPromote.contains(alloca);
    }
    
    /**
     * 更新后继基本块中的phi指令
     */
    private void updatePhiInSuccessors(BasicBlock block, Map<AllocaInstruction, Value> currentValues) {
        for (BasicBlock successor : block.getSuccessors()) {
            for (Instruction inst : successor.getInstructions()) {
                if (inst instanceof PhiInstruction phi) {
                    AllocaInstruction alloca = context.phiToAlloca.get(phi);
                    if (alloca != null) {
                        Value currentValue = currentValues.get(alloca);
                        if (currentValue != null) {
                            // 使用低级API直接设置操作数，确保建立正确的Use-Def关系
                            List<BasicBlock> predecessors = successor.getPredecessors();
                            int preBbIndex = predecessors.indexOf(block);
                            
                            if (preBbIndex >= 0 && preBbIndex < phi.getOperandCount()) {
                                // 直接设置操作数，这会自动维护Use-Def关系
                                phi.setOperand(preBbIndex, currentValue);
                                
                                // 同时更新Phi的内部映射（如果存在的话）
                                try {
                                    // 通过反射或者其他方式更新incomingValues映射
                                    Map<BasicBlock, Value> incomingValues = phi.getIncomingValues();
                                    incomingValues.put(block, currentValue);
                                } catch (Exception e) {
                                    // 如果更新映射失败，至少操作数是正确的
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
    
    /**
     * 获取被指定基本块直接支配的基本块列表
     */
    private List<BasicBlock> getImmediateDominatedBlocks(BasicBlock dominator) {
        List<BasicBlock> dominated = new ArrayList<>();
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            if (block.getIdominator() == dominator) {
                dominated.add(block);
            }
        }
        return dominated;
    }
    
    /**
     * 清理无用指令
     * 删除alloca、load、store指令
     */
    private void cleanupInstructions() {
        debug("    清理无用指令...");
        debug("    需要删除 " + context.instructionsToDelete.size() + " 条指令");
        
        // 删除所有标记的指令
        for (Instruction inst : context.instructionsToDelete) {
            debug("      删除指令: " + inst.toString());
            
            // 在删除指令之前，清理Use-Def关系
            // 遍历所有操作数，从它们的用户列表中移除这个指令
            for (int i = 0; i < inst.getOperandCount(); i++) {
                Value operand = inst.getOperand(i);
                if (operand != null) {
                    // 从操作数的用户列表中移除这个指令
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
        
        // 清理后立即检查关键值的Use-Def关系
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
        
        // 输出最终的Phi指令状态
        debug("    最终Phi指令状态:");
        for (BasicBlock block : context.currentFunction.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof PhiInstruction phi && context.phiToAlloca.containsKey(phi)) {
                    debug("      " + phi.getName() + " 在 " + block.getName() + ": " + phi.toString());
                }
            }
        }
        
        // 检查关键值的Use-Def关系
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
    
    /**
     * 替换所有使用oldValue的地方为newValue
     */
    private void replaceAllUses(Value oldValue, Value newValue) {
        // 获取所有使用oldValue的用户
        List<User> users = new ArrayList<>(oldValue.getUsers());
        
        debug("        替换 " + oldValue.getName() + " 的所有使用为 " + newValue.getName());
        debug("        使用者数量: " + users.size());
        
        // 遍历所有用户，替换使用
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldValue) {
                    debug("          替换在 " + user.toString() + " 中的使用");
                    // 使用setOperand会自动维护Use-Def链
                    user.setOperand(i, newValue);
                }
            }
        }
    }
}
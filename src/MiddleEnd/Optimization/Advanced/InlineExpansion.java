package MiddleEnd.Optimization.Advanced;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.IRBuilder;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Type.*;

import java.util.*;
import java.util.LinkedList;

/**
 * 函数内联展开优化器
 * 将符合条件的函数调用展开为内联代码，提升程序性能
 */
public class InlineExpansion implements Optimizer.ModuleOptimizer {
    
    private static final int MAX_EXPANSION_SIZE = 25;  // 最大展开函数大小
    private static final int MAX_EXPANSION_ROUNDS = 5; // 最大展开轮数
    
    private Module targetModule;
    private boolean hasExpanded;
    private Map<Function, Set<Function>> callGraph;
    private Map<Function, Integer> functionComplexity;
    private Set<Function> expansionCandidates;
    private static int inlineCounter = 0;  // 为每次内联生成唯一标识
    
    @Override
    public String getName() {
        return "InlineExpansion";
    }
    
    @Override
    public boolean run(Module module) {
        this.targetModule = module;
        this.hasExpanded = false;
        this.callGraph = new HashMap<>();
        this.functionComplexity = new HashMap<>();
        this.expansionCandidates = new HashSet<>();
        
        System.out.println("[InlineExpansion] Starting function inline expansion...");
        
        // 多轮内联展开，直到无法继续
        for (int round = 0; round < MAX_EXPANSION_ROUNDS; round++) {
            boolean roundChanged = performExpansionRound();
            if (!roundChanged) {
                System.out.println("[InlineExpansion] Converged after " + (round + 1) + " rounds");
                break;
            }
            hasExpanded = true;
        }
        
        // 清理无用函数
        if (hasExpanded) {
            cleanupOrphanedFunctions();
        }
        
        // 最后一步：提升所有函数中的alloca到入口块，确保支配关系
        for (Function func : module.functions()) {
            if (!func.isExternal()) {
                System.out.println("[DEBUG] 准备处理函数: " + func.getName());
                
                // 先检查函数中所有的alloca位置
                System.out.println("[DEBUG] 检查函数 " + func.getName() + " 中的alloca分布:");
                for (int i = 0; i < func.getBasicBlocks().size(); i++) {
                    BasicBlock block = func.getBasicBlocks().get(i);
                    for (Instruction inst : block.getInstructions()) {
                        if (inst instanceof AllocaInstruction allocaInst) {
                            System.out.println("[DEBUG]   alloca " + allocaInst.getName() + 
                                             " 在块 " + block.getName() + " (块索引: " + i + ")");
                        }
                    }
                }
                
                hoistAllocasToEntry(func);
                
                // 再次检查提升后的结果
                System.out.println("[DEBUG] 提升后检查函数 " + func.getName() + " 中的alloca分布:");
                for (int i = 0; i < func.getBasicBlocks().size(); i++) {
                    BasicBlock block = func.getBasicBlocks().get(i);
                    for (Instruction inst : block.getInstructions()) {
                        if (inst instanceof AllocaInstruction allocaInst) {
                            System.out.println("[DEBUG]   alloca " + allocaInst.getName() + 
                                             " 在块 " + block.getName() + " (块索引: " + i + ")");
                        }
                    }
                }
            }
        }
        
        System.out.println("[InlineExpansion] Inline expansion " + 
                          (hasExpanded ? "completed with changes" : "found no expansion opportunities"));
        
        return hasExpanded;
    }
    
    /**
     * 执行一轮内联展开
     */
    private boolean performExpansionRound() {
        // 重新构建调用图和复杂度分析
        buildCallGraphAndComplexity();
        
        // 选择展开候选函数
        selectExpansionCandidates();
        
        if (expansionCandidates.isEmpty()) {
            return false;
        }
        
        System.out.println("[InlineExpansion] Found " + expansionCandidates.size() + 
                          " expansion candidates: " + 
                          expansionCandidates.stream().map(Function::getName).toList());
        
        // 执行内联展开
        boolean roundChanged = false;
        for (Function candidate : new ArrayList<>(expansionCandidates)) {
            if (expandFunction(candidate)) {
                roundChanged = true;
            }
        }
        
        return roundChanged;
    }
    
    /**
     * 构建调用图和计算函数复杂度
     */
    private void buildCallGraphAndComplexity() {
        callGraph.clear();
        functionComplexity.clear();
        
        System.out.println("[InlineExpansion] Analyzing functions:");
        for (Function func : targetModule.functions()) {
            callGraph.put(func, new HashSet<>());
            functionComplexity.put(func, 0);
            System.out.println("  - Function: " + func.getName() + ", External: " + func.isExternal());
        }
        
        // 构建调用关系和计算复杂度
        for (Function caller : targetModule.functions()) {
            if (caller.isExternal()) {
                continue;
            }
            
            int complexity = 0;
            for (BasicBlock block : caller.getBasicBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    complexity++;
                    
                    if (inst instanceof CallInstruction callInst) {
                        Function callee = callInst.getCallee();
                        System.out.println("[InlineExpansion] Found call: " + caller.getName() + " -> " + callee.getName());
                        if (!callee.isExternal()) {
                            callGraph.get(caller).add(callee);
                            callee.addCaller(caller);
                            caller.addCallee(callee);
                        }
                    }
                }
            }
            functionComplexity.put(caller, complexity);
            System.out.println("[InlineExpansion] Function " + caller.getName() + " complexity: " + complexity);
        }
    }
    
    /**
     * 选择内联展开候选函数
     */
    private void selectExpansionCandidates() {
        expansionCandidates.clear();
        
        for (Function func : targetModule.functions()) {
            if (isExpansionCandidate(func)) {
                expansionCandidates.add(func);
            }
        }
    }
    
    /**
     * 判断函数是否适合内联展开
     */
    private boolean isExpansionCandidate(Function func) {
        // 跳过main函数和外部函数
        if (func.getName().equals("main") || func.getName().equals("@main") || func.isExternal()) {
            return false;
        }
        
        // 必须有调用者
        if (func.getCallers().isEmpty()) {
            return false;
        }
        
        // 检查函数复杂度
        Integer complexity = functionComplexity.get(func);
        if (complexity == null || complexity > MAX_EXPANSION_SIZE || complexity < 2) {
            return false;
        }
        
        // 避免递归函数
        if (hasDirectRecursion(func) || hasIndirectRecursion(func)) {
            return false;
        }
        
        // 优先内联叶子函数（不调用其他非库函数）
        Set<Function> callees = callGraph.get(func);
        for (Function callee : callees) {
            if (!callee.isExternal()) {
                // 允许调用已经被标记为候选的简单函数
                Integer calleeComplexity = functionComplexity.get(callee);
                if (calleeComplexity == null || calleeComplexity > 10) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * 检查直接递归
     */
    private boolean hasDirectRecursion(Function func) {
        return func.getCallees().contains(func);
    }
    
    /**
     * 检查间接递归
     */
    private boolean hasIndirectRecursion(Function func) {
        return hasIndirectRecursionHelper(func, new HashSet<>());
    }
    
    private boolean hasIndirectRecursionHelper(Function func, Set<Function> visited) {
        if (visited.contains(func)) {
            return true;
        }
        
        visited.add(func);
        Set<Function> callees = callGraph.get(func);
        for (Function callee : callees) {
            if (hasIndirectRecursionHelper(callee, visited)) {
                return true;
            }
        }
        visited.remove(func);
        
        return false;
    }
    
    /**
     * 展开指定函数的所有调用
     */
    private boolean expandFunction(Function function) {
        List<CallInstruction> callSites = findCallSites(function);
        
        if (callSites.isEmpty()) {
            return false;
        }
        
        System.out.println("[InlineExpansion] Expanding " + callSites.size() + 
                          " call sites for function: " + function.getName());
        
        boolean expanded = false;
        for (CallInstruction callSite : callSites) {
            if (expandCallSite(callSite, function)) {
                expanded = true;
            }
        }
        
        // 内联完成后，清空被内联函数的调用者列表，但不要删除函数本身
        if (expanded) {
            function.getCallers().clear();
        }
        
        return expanded;
    }
    
    /**
     * 找到函数的所有调用点
     */
    private List<CallInstruction> findCallSites(Function target) {
        List<CallInstruction> callSites = new ArrayList<>();
        
        for (Function caller : target.getCallers()) {
            if (caller.equals(target)) {
                continue; // 跳过自递归
            }
            
            for (BasicBlock block : caller.getBasicBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst instanceof CallInstruction callInst && 
                        callInst.getCallee().equals(target)) {
                        callSites.add(callInst);
                    }
                }
            }
        }
        
        return callSites;
    }
    
    /**
     * 展开单个调用点 - 参考example的方法
     */
    private boolean expandCallSite(CallInstruction callSite, Function targetFunc) {
        try {
            BasicBlock callBlock = callSite.getParent();
            Function callerFunc = callBlock.getParentFunction();
            
            // 为这次内联生成唯一标识
            int currentInlineId = ++inlineCounter;
            String inlinePrefix = "inline" + currentInlineId + "_";
            
            System.out.println("[DEBUG] 开始内联 " + targetFunc.getName() + " 到 " + callerFunc.getName());
            
            // 1. 创建临时函数副本（参考example第137行）
            FunctionReplicator replicator = new FunctionReplicator(callerFunc, targetFunc, inlinePrefix);
            ReplicatedFunction replica = replicator.createReplica();
            
            // 2. 创建继续块并分割调用块（参考example第141-152行）
            BasicBlock continuationBlock = splitBlockAtCall(callBlock, callSite, currentInlineId);
            
            // 3. 建立参数映射（参考example第179-187行）
            List<Value> arguments = callSite.getArguments();
            List<Argument> parameters = targetFunc.getArguments();
            Map<Value, Value> parameterMap = new HashMap<>();
            
            for (int i = 0; i < Math.min(arguments.size(), parameters.size()); i++) {
                parameterMap.put(parameters.get(i), arguments.get(i));
                System.out.println("[DEBUG] 参数映射: " + parameters.get(i).getName() + " -> " + arguments.get(i));
            }
            
            // 4. 应用参数映射
            replicator.applyValueMapping(parameterMap);
            
            // 5. 移除调用指令（参考example第155行）
            callBlock.removeInstruction(callSite);
            
            // 6. 连接控制流（参考example第175-251行）
            connectInlineBlocksLikeExample(callBlock, replica, continuationBlock, callSite);
            
            return true;
            
        } catch (Exception e) {
            System.err.println("[InlineExpansion] Failed to expand call site: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 在调用指令处分割基本块
     */
    private BasicBlock splitBlockAtCall(BasicBlock originalBlock, CallInstruction callInst, int inlineId) {
        Function parentFunc = originalBlock.getParentFunction();
        BasicBlock continuationBlock = IRBuilder.createBasicBlock("continue" + inlineId, parentFunc);
        
        // 将调用指令后的所有指令移动到继续块
        List<Instruction> instructions = new ArrayList<>(originalBlock.getInstructions());
        int callIndex = instructions.indexOf(callInst);
        
        for (int i = callIndex + 1; i < instructions.size(); i++) {
            Instruction inst = instructions.get(i);
            originalBlock.removeInstruction(inst);
            continuationBlock.addInstruction(inst);
        }
        
        // 更新后继关系
        List<BasicBlock> successors = new ArrayList<>(originalBlock.getSuccessors());
        for (BasicBlock successor : successors) {
            originalBlock.removeSuccessor(successor);
            continuationBlock.addSuccessor(successor);
        }
        
        return continuationBlock;
    }
    
    /**
     * 连接内联后的基本块 - 完全参考example的方法
     */
    private void connectInlineBlocksLikeExample(BasicBlock callBlock, ReplicatedFunction replica, 
                                              BasicBlock continuationBlock, CallInstruction callSite) {
        
        System.out.println("[DEBUG] 按example方式连接内联块");
        
        // 1. 从调用块跳转到内联函数入口（参考example第175行）
        BranchInstruction entryBranch = new BranchInstruction(replica.entryBlock);
        callBlock.addInstruction(entryBranch);
        callBlock.addSuccessor(replica.entryBlock);
        
        // 2. 将所有内联基本块插入到继续块之前（参考example第251行）
        Function callerFunction = callBlock.getParentFunction();
        for (BasicBlock inlineBlock : replica.allBlocks) {
            callerFunction.removeBasicBlock(inlineBlock);
            callerFunction.addBasicBlockBefore(inlineBlock, continuationBlock);
            System.out.println("[DEBUG] 插入内联块: " + inlineBlock.getName() + " 到继续块之前");
        }
        
        // 3. 处理返回值（参考example第210-248行）
        handleReturnValues(replica, continuationBlock, callSite);
    }
    
    /**
     * 处理返回值 - 参考example的返回值处理逻辑
     */
    private void handleReturnValues(ReplicatedFunction replica, BasicBlock continuationBlock, CallInstruction callSite) {
        if (replica.returnInstructions.isEmpty()) {
            return;
        }
        
        if (callSite.isVoidCall()) {
            // void函数，直接跳转到继续块
            for (int i = 0; i < replica.exitBlocks.size(); i++) {
                BasicBlock exitBlock = replica.exitBlocks.get(i);
                ReturnInstruction returnInst = replica.returnInstructions.get(i);
                
                exitBlock.removeInstruction(returnInst);
                BranchInstruction branch = new BranchInstruction(continuationBlock);
                exitBlock.addInstruction(branch);
                exitBlock.addSuccessor(continuationBlock);
            }
        } else {
            // 非void函数，需要处理返回值
            if (replica.returnInstructions.size() == 1) {
                // 单个返回值（参考example第212-219行）
                ReturnInstruction returnInst = replica.returnInstructions.get(0);
                BasicBlock exitBlock = replica.exitBlocks.get(0);
                
                Value returnValue = returnInst.getReturnValue();
                if (returnValue != null) {
                    replaceAllUsages(callSite, returnValue);
                }
                
                exitBlock.removeInstruction(returnInst);
                BranchInstruction branch = new BranchInstruction(continuationBlock);
                exitBlock.addInstruction(branch);
                exitBlock.addSuccessor(continuationBlock);
                
            } else {
                // 多个返回值，使用Phi指令（参考example第221-238行）
                PhiInstruction resultPhi = new PhiInstruction(callSite.getType(), "inline_result");
                continuationBlock.addInstructionFirst(resultPhi);
                
                for (int i = 0; i < replica.exitBlocks.size(); i++) {
                    BasicBlock exitBlock = replica.exitBlocks.get(i);
                    ReturnInstruction returnInst = replica.returnInstructions.get(i);
                    
                    Value returnValue = returnInst.getReturnValue();
                    if (returnValue != null) {
                        resultPhi.addIncoming(returnValue, exitBlock);
                    }
                    
                    exitBlock.removeInstruction(returnInst);
                    BranchInstruction branch = new BranchInstruction(continuationBlock);
                    exitBlock.addInstruction(branch);
                    exitBlock.addSuccessor(continuationBlock);
                }
                
                replaceAllUsages(callSite, resultPhi);
            }
        }
    }
    
    /**
     * 连接内联后的基本块
     */
    private void connectInlineBlocks(BasicBlock callBlock, ReplicatedFunction replica, 
                                   BasicBlock continuationBlock, CallInstruction callSite) {
        
        System.out.println("[DEBUG] 连接内联块，调用块: " + callBlock.getName() + 
                          ", 继续块: " + continuationBlock.getName());
        System.out.println("[DEBUG] 内联函数有 " + replica.allBlocks.size() + " 个基本块");
        
        // 将内联函数的所有基本块插入到继续块之前（参考example第251行）
        Function callerFunction = callBlock.getParentFunction();
        
        System.out.println("[DEBUG] 调用者函数 " + callerFunction.getName() + " 当前有 " + 
                          callerFunction.getBasicBlocks().size() + " 个基本块");
        
        for (int i = 0; i < replica.allBlocks.size(); i++) {
            BasicBlock inlineBlock = replica.allBlocks.get(i);
            System.out.println("[DEBUG] 处理内联块 " + i + ": " + inlineBlock.getName() + 
                              ", 指令数: " + inlineBlock.getInstructions().size());
            
            // 检查第一个指令是否是alloca
            if (!inlineBlock.getInstructions().isEmpty()) {
                Instruction firstInst = inlineBlock.getInstructions().get(0);
                System.out.println("[DEBUG] 第一个指令类型: " + firstInst.getClass().getSimpleName());
            }
            
            // 从调用者函数中移除，然后重新插入到正确位置
            callerFunction.removeBasicBlock(inlineBlock);
        }
        
        // 按正确顺序重新插入所有内联块到继续块之前
        for (BasicBlock inlineBlock : replica.allBlocks) {
            callerFunction.addBasicBlockBefore(inlineBlock, continuationBlock);
            System.out.println("[DEBUG] 重新插入内联块: " + inlineBlock.getName());
        }
        
        System.out.println("[DEBUG] 重新排列后，调用者函数有 " + 
                          callerFunction.getBasicBlocks().size() + " 个基本块");
        
        // 从调用块跳转到内联函数入口
        BranchInstruction entryBranch = new BranchInstruction(replica.entryBlock);
        callBlock.addInstruction(entryBranch);
        callBlock.addSuccessor(replica.entryBlock);
        
        // 处理返回值合并
        Value resultValue = null;
        
        if (!replica.exitBlocks.isEmpty()) {
            if (replica.exitBlocks.size() == 1) {
                // 单个退出点 - 直接替换
                BasicBlock exitBlock = replica.exitBlocks.get(0);
                ReturnInstruction returnInst = replica.returnInstructions.get(0);
                
                resultValue = returnInst.getReturnValue();
                
                // 移除return指令，添加跳转到继续块
                exitBlock.removeInstruction(returnInst);
                BranchInstruction exitBranch = new BranchInstruction(continuationBlock);
                exitBlock.addInstruction(exitBranch);
                exitBlock.addSuccessor(continuationBlock);
                
            } else {
                // 多个退出点 - 使用Phi指令合并
                if (!callSite.isVoidCall()) {
                    PhiInstruction resultPhi = new PhiInstruction(callSite.getType(), "inline_result");
                    continuationBlock.addInstructionFirst(resultPhi);
                    resultValue = resultPhi;
                    
                    for (int i = 0; i < replica.exitBlocks.size(); i++) {
                        BasicBlock exitBlock = replica.exitBlocks.get(i);
                        ReturnInstruction returnInst = replica.returnInstructions.get(i);
                        Value returnValue = returnInst.getReturnValue();
                        
                        if (returnValue != null) {
                            resultPhi.addIncoming(returnValue, exitBlock);
                        }
                        
                        exitBlock.removeInstruction(returnInst);
                        BranchInstruction exitBranch = new BranchInstruction(continuationBlock);
                        exitBlock.addInstruction(exitBranch);
                        exitBlock.addSuccessor(continuationBlock);
                    }
                } else {
                    // void函数，直接跳转
                    for (BasicBlock exitBlock : replica.exitBlocks) {
                        ReturnInstruction returnInst = (ReturnInstruction) exitBlock.getLastInstruction();
                        if (returnInst != null) {
                            exitBlock.removeInstruction(returnInst);
                        }
                        BranchInstruction exitBranch = new BranchInstruction(continuationBlock);
                        exitBlock.addInstruction(exitBranch);
                        exitBlock.addSuccessor(continuationBlock);
                    }
                }
            }
        }
        
        // 替换调用指令的使用
        if (resultValue != null && !callSite.isVoidCall()) {
            replaceAllUsages(callSite, resultValue);
        }
    }
    
    /**
     * 替换所有使用
     */
    private void replaceAllUsages(Value oldValue, Value newValue) {
        List<User> users = new ArrayList<>(oldValue.getUsers());
        for (User user : users) {
            if (user instanceof Instruction inst) {
                for (int i = 0; i < inst.getOperandCount(); i++) {
                    if (inst.getOperand(i) == oldValue) {
                        inst.setOperand(i, newValue);
                    }
                }
            }
        }
    }
    
    /**
     * 清理孤立函数
     */
    private void cleanupOrphanedFunctions() {
        Set<Function> toRemove = new HashSet<>();
        
        for (Function func : targetModule.functions()) {
            // 保护main函数不被删除
            if (func.getName().equals("main") || func.getName().equals("@main") || func.isExternal()) {
                continue;
            }
            
            // 只删除真正没有调用者的函数
            if (func.getCallers().isEmpty()) {
                toRemove.add(func);
            }
        }
        
        if (!toRemove.isEmpty()) {
            System.out.println("[InlineExpansion] Removing " + toRemove.size() + " orphaned functions: " + 
                              toRemove.stream().map(Function::getName).toList());
            for (Function func : toRemove) {
                targetModule.functions().remove(func);
            }
        }
    }
    
    /**
     * 将函数中所有的alloca指令提升到入口块开头，确保支配关系
     */
    private void hoistAllocasToEntry(Function func) {
        BasicBlock entryBlock = func.getEntryBlock();
        if (entryBlock == null) return;
        
        System.out.println("[DEBUG] 提升函数 " + func.getName() + " 中的alloca");
        
        List<AllocaInstruction> allocasToHoist = new ArrayList<>();
        
        // 收集所有非入口块中的alloca指令
        for (BasicBlock block : func.getBasicBlocks()) {
            if (block == entryBlock) continue;
            
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            for (Instruction inst : instructions) {
                if (inst instanceof AllocaInstruction allocaInst) {
                    System.out.println("[DEBUG] 发现需要提升的alloca: " + allocaInst.getName() + 
                                     " (在块: " + block.getName() + ")");
                    allocasToHoist.add(allocaInst);
                    block.removeInstruction(allocaInst);
                }
            }
        }
        
        // 将alloca指令移动到入口块开头
        for (AllocaInstruction alloca : allocasToHoist) {
            entryBlock.addInstructionFirst(alloca);
            System.out.println("[DEBUG] 提升alloca到入口块: " + alloca.getName());
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
        
        // 调试输出重新排序后前5条指令
        System.out.println("[DEBUG] 重新排序后入口块 " + entryBlock.getName() + " 的前10条指令:");
        List<Instruction> entryInsts2 = entryBlock.getInstructions();
        for (int i = 0; i < Math.min(10, entryInsts2.size()); i++) {
            Instruction inst = entryInsts2.get(i);
            System.out.println("[DEBUG]   " + i + ": " + inst.getClass().getSimpleName() +
                             (inst instanceof AllocaInstruction ? " " + ((AllocaInstruction)inst).getName() : ""));
        }
        
        // 强制验证LinkedList的实际顺序
        System.out.println("[DEBUG] 直接访问LinkedList验证顺序:");
        LinkedList<Instruction> rawList = (LinkedList<Instruction>) entryBlock.getInstructions();
        for (int i = 0; i < Math.min(10, rawList.size()); i++) {
            Instruction inst = rawList.get(i);
            System.out.println("[DEBUG]   LinkedList[" + i + "]: " + inst.getClass().getSimpleName() +
                             (inst instanceof AllocaInstruction ? " " + ((AllocaInstruction)inst).getName() : ""));
        }
        
        if (!allocasToHoist.isEmpty()) {
            System.out.println("[DEBUG] 函数 " + func.getName() + " 提升了 " + allocasToHoist.size() + " 个alloca");
        }
    }

    
    /**
     * 复制函数结果
     */
    private static class ReplicatedFunction {
        final BasicBlock entryBlock;
        final List<BasicBlock> allBlocks;
        final List<BasicBlock> exitBlocks;
        final List<ReturnInstruction> returnInstructions;
        
        ReplicatedFunction(BasicBlock entry, List<BasicBlock> blocks, 
                         List<BasicBlock> exits, List<ReturnInstruction> returns) {
            this.entryBlock = entry;
            this.allBlocks = blocks;
            this.exitBlocks = exits;
            this.returnInstructions = returns;
        }
    }
    
    /**
     * 函数复制器
     */
    private class FunctionReplicator {
        private final Function targetFunction;
        private final Function sourceFunction;
        private final Map<BasicBlock, BasicBlock> blockMap;
        private final Map<Value, Value> valueMap;
        private final String inlinePrefix;
        
        FunctionReplicator(Function target, Function source, String prefix) {
            this.targetFunction = target;
            this.sourceFunction = source;
            this.blockMap = new HashMap<>();
            this.valueMap = new HashMap<>();
            this.inlinePrefix = prefix;
        }
        
        ReplicatedFunction createReplica() {
            List<BasicBlock> replicatedBlocks = new ArrayList<>();
            List<BasicBlock> exitBlocks = new ArrayList<>();
            List<ReturnInstruction> returnInstructions = new ArrayList<>();
            
            // 第一步：创建所有基本块的副本
            for (BasicBlock originalBlock : sourceFunction.getBasicBlocks()) {
                BasicBlock replicatedBlock = IRBuilder.createBasicBlock(
                    inlinePrefix + originalBlock.getName(), targetFunction);
                blockMap.put(originalBlock, replicatedBlock);
                replicatedBlocks.add(replicatedBlock);
            }
            
            // 第二步：收集所有alloca指令，稍后统一处理
            List<AllocaInstruction> allocaInstructions = new ArrayList<>();
            BasicBlock inlineEntryBlock = blockMap.get(sourceFunction.getEntryBlock());
            System.out.println("[DEBUG] 内联函数入口块: " + inlineEntryBlock.getName());
            
            for (BasicBlock originalBlock : sourceFunction.getBasicBlocks()) {
                for (Instruction originalInst : originalBlock.getInstructions()) {
                    if (originalInst instanceof AllocaInstruction allocaInst) {
                        AllocaInstruction replicatedAlloca = new AllocaInstruction(
                            allocaInst.getAllocatedType(),
                            inlinePrefix + allocaInst.getName()
                        );
                        
                        System.out.println("[DEBUG] 收集alloca: " + allocaInst.getName() + 
                                         " -> " + replicatedAlloca.getName() + 
                                         " (原块: " + originalBlock.getName() + ")");
                        
                        allocaInstructions.add(replicatedAlloca);
                        valueMap.put(originalInst, replicatedAlloca);
                    }
                }
            }
            
            // 将所有alloca放到入口块开头
            for (AllocaInstruction alloca : allocaInstructions) {
                inlineEntryBlock.addInstructionFirst(alloca);
            }
            System.out.println("[DEBUG] 总共处理了 " + allocaInstructions.size() + " 个alloca指令");
            
            // 第三步：复制其他指令
            for (BasicBlock originalBlock : sourceFunction.getBasicBlocks()) {
                BasicBlock replicatedBlock = blockMap.get(originalBlock);
                
                for (Instruction originalInst : originalBlock.getInstructions()) {
                    // 跳过已经处理的alloca指令
                    if (originalInst instanceof AllocaInstruction) {
                        continue;
                    }
                    
                    Instruction replicatedInst = replicateInstruction(originalInst);
                    if (replicatedInst != null) {
                        replicatedBlock.addInstruction(replicatedInst);
                        
                        if (replicatedInst instanceof ReturnInstruction retInst) {
                            exitBlocks.add(replicatedBlock);
                            returnInstructions.add(retInst);
                        }
                    }
                }
            }
            
            // 第四步：修复内部引用
            fixInternalReferences();
            
            BasicBlock entryBlock = blockMap.get(sourceFunction.getEntryBlock());
            return new ReplicatedFunction(entryBlock, replicatedBlocks, exitBlocks, returnInstructions);
        }
        
        void applyValueMapping(Map<Value, Value> mapping) {
            valueMap.putAll(mapping);
            
            // 重新映射所有指令中的值引用
            for (BasicBlock block : blockMap.values()) {
                for (Instruction inst : block.getInstructions()) {
                    remapInstructionValues(inst);
                }
            }
        }
        
        private Instruction replicateInstruction(Instruction original) {
            try {
                if (original instanceof BinaryInstruction binInst) {
                    BinaryInstruction replica = new BinaryInstruction(
                        binInst.getOpCode(),
                        getMappedValue(binInst.getLeft()),
                        getMappedValue(binInst.getRight()),
                        binInst.getType()
                    );
                    replica.setName(inlinePrefix + binInst.getName());
                    valueMap.put(original, replica);
                    return replica;
                    
                } else if (original instanceof LoadInstruction loadInst) {
                    LoadInstruction replica = new LoadInstruction(
                        getMappedValue(loadInst.getPointer()),
                        inlinePrefix + loadInst.getName()
                    );
                    valueMap.put(original, replica);
                    return replica;
                    
                } else if (original instanceof StoreInstruction storeInst) {
                    return new StoreInstruction(
                        getMappedValue(storeInst.getValue()),
                        getMappedValue(storeInst.getPointer())
                    );
                    
                } else if (original instanceof AllocaInstruction allocaInst) {
                    // Alloca指令已经在createReplica的第二步中处理了
                    return (Instruction) valueMap.get(original);
                    
                } else if (original instanceof CompareInstruction cmpInst) {
                    CompareInstruction replica = new CompareInstruction(
                        cmpInst.getCompareType(),
                        cmpInst.getPredicate(),
                        getMappedValue(cmpInst.getLeft()),
                        getMappedValue(cmpInst.getRight())
                    );
                    replica.setName(inlinePrefix + cmpInst.getName());
                    valueMap.put(original, replica);
                    return replica;
                    
                } else if (original instanceof CallInstruction callInst) {
                    List<Value> args = new ArrayList<>();
                    for (Value arg : callInst.getArguments()) {
                        args.add(getMappedValue(arg));
                    }
                    CallInstruction replica = new CallInstruction(
                        callInst.getCallee(),
                        args,
                        callInst.isVoidCall() ? null : inlinePrefix + callInst.getName()
                    );
                    if (!callInst.isVoidCall()) {
                        valueMap.put(original, replica);
                    }
                    return replica;
                    
                } else if (original instanceof ReturnInstruction retInst) {
                    if (retInst.isVoidReturn()) {
                        return new ReturnInstruction();
                    } else {
                        return new ReturnInstruction(getMappedValue(retInst.getReturnValue()));
                    }
                    
                } else if (original instanceof BranchInstruction brInst) {
                    if (brInst.isUnconditional()) {
                        return new BranchInstruction(blockMap.get(brInst.getTrueBlock()));
                    } else {
                        return new BranchInstruction(
                            getMappedValue(brInst.getCondition()),
                            blockMap.get(brInst.getTrueBlock()),
                            blockMap.get(brInst.getFalseBlock())
                        );
                    }
                    
                } else if (original instanceof ConversionInstruction convInst) {
                    ConversionInstruction replica = new ConversionInstruction(
                        getMappedValue(convInst.getSource()),
                        convInst.getType(),
                        convInst.getConversionType(),
                        inlinePrefix + convInst.getName()
                    );
                    valueMap.put(original, replica);
                    return replica;
                    
                } else {
                    System.out.println("[InlineExpansion] Warning: Unknown instruction type: " + 
                                     original.getClass().getSimpleName());
                    return null;
                }
                
            } catch (Exception e) {
                System.err.println("[InlineExpansion] Failed to replicate instruction: " + original);
                return null;
            }
        }
        
        private Value getMappedValue(Value original) {
            return valueMap.getOrDefault(original, original);
        }
        
        private void fixInternalReferences() {
            for (BasicBlock block : blockMap.values()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst instanceof BranchInstruction brInst) {
                        updateBranchTargets(block, brInst);
                    }
                }
            }
        }
        
        private void updateBranchTargets(BasicBlock parentBlock, BranchInstruction brInst) {
            if (brInst.isUnconditional()) {
                BasicBlock newTarget = blockMap.get(brInst.getTrueBlock());
                if (newTarget != null) {
                    parentBlock.removeInstruction(brInst);
                    BranchInstruction newBr = new BranchInstruction(newTarget);
                    parentBlock.addInstruction(newBr);
                    parentBlock.addSuccessor(newTarget);
                }
            } else {
                BasicBlock newTrueTarget = blockMap.get(brInst.getTrueBlock());
                BasicBlock newFalseTarget = blockMap.get(brInst.getFalseBlock());
                
                if (newTrueTarget != null && newFalseTarget != null) {
                    parentBlock.removeInstruction(brInst);
                    BranchInstruction newBr = new BranchInstruction(
                        brInst.getCondition(), newTrueTarget, newFalseTarget);
                    parentBlock.addInstruction(newBr);
                    parentBlock.addSuccessor(newTrueTarget);
                    parentBlock.addSuccessor(newFalseTarget);
                }
            }
        }
        
        private void remapInstructionValues(Instruction inst) {
            for (int i = 0; i < inst.getOperandCount(); i++) {
                Value operand = inst.getOperand(i);
                Value mapped = valueMap.get(operand);
                if (mapped != null) {
                    inst.setOperand(i, mapped);
                }
            }
        }
    }
}

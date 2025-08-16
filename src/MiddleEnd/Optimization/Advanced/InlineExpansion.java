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
        
        // 注意：不再在这里提升alloca，而是让后续的Mem2Reg优化来处理
        // 这样可以避免破坏SSA形式和支配关系
        
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
        
        // 避免包含Phi指令的函数（暂时不支持）
        for (BasicBlock block : func.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof PhiInstruction) {
                    System.out.println("[InlineExpansion] Skipping " + func.getName() + " due to Phi instruction: " + inst.getName());
                    return false;
                }
            }
        }
        
        // 检查调用者是否包含复杂控制流（包含Phi指令）
        for (Function caller : func.getCallers()) {
            for (BasicBlock block : caller.getBasicBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst instanceof PhiInstruction) {
                        System.out.println("[InlineExpansion] Skipping " + func.getName() + " due to Phi in caller " + caller.getName());
                        return false;
                    }
                }
            }
        }
        
        // 检查是否参与短路求值 - 如果调用指令的返回值被用在条件跳转或phi指令中，则不内联
        if (hasShortCircuitUsage(func)) {
            System.out.println("[InlineExpansion] Skipping " + func.getName() + " due to short-circuit usage");
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
     * 检查函数是否参与短路求值逻辑
     */
    private boolean hasShortCircuitUsage(Function func) {
        for (Function caller : func.getCallers()) {
            for (BasicBlock block : caller.getBasicBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst instanceof CallInstruction callInst && callInst.getCallee().equals(func)) {
                        // 直接或间接参与控制流（短路）使用检测
                        if (isTransitivelyUsedInControlFlow(callInst, 0, new HashSet<>())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    private boolean isTransitivelyUsedInControlFlow(Value value, int depth, Set<User> visited) {
        if (depth > 6) return false;
        for (User user : value.getUsers()) {
            if (visited.contains(user)) continue;
            visited.add(user);
            if (user instanceof BranchInstruction || user instanceof PhiInstruction) {
                return true;
            }
            if (user instanceof Instruction inst) {
                // 允许穿过的中间节点：比较、类型转换、再次调用、二元运算等
                if (inst instanceof CompareInstruction || inst instanceof ConversionInstruction ||
                    inst instanceof CallInstruction || inst instanceof BinaryInstruction) {
                    if (isTransitivelyUsedInControlFlow(inst, depth + 1, visited)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
     * 展开单个调用点 
     */
    private boolean expandCallSite(CallInstruction callSite, Function targetFunc) {
        try {
            BasicBlock callBlock = callSite.getParent();
            Function callerFunc = callBlock.getParentFunction();
            
            // 为这次内联生成唯一标识
            int currentInlineId = ++inlineCounter;
            String inlinePrefix = "inline" + currentInlineId + "_";
            
            System.out.println("[DEBUG] 开始内联 " + targetFunc.getName() + " 到 " + callerFunc.getName());
            
            // 0. 不再需要特殊处理调用者的alloca，正常复制即可
            
            // 1. 创建临时函数副本
            FunctionReplicator replicator = new FunctionReplicator(callerFunc, targetFunc, inlinePrefix);
            ReplicatedFunction replica = replicator.createReplica();
            
            // 2. 创建继续块并分割调用块
            BasicBlock continuationBlock = splitBlockAtCall(callBlock, callSite, currentInlineId);
            
            // 3. 建立参数映射
            List<Value> arguments = callSite.getArguments();
            List<Argument> parameters = targetFunc.getArguments();
            Map<Value, Value> parameterMap = new HashMap<>();
            
            for (int i = 0; i < Math.min(arguments.size(), parameters.size()); i++) {
                parameterMap.put(parameters.get(i), arguments.get(i));
                System.out.println("[DEBUG] 参数映射: " + parameters.get(i).getName() + " -> " + arguments.get(i));
            }
            
            // 4. 应用参数映射
            replicator.applyValueMapping(parameterMap);
            
            // 5. 连接控制流
            // 注意：必须在移除调用指令之前处理返回值，因为replaceAllUsages需要访问callSite的用户列表
            connectInlineBlocksLikeExample(callBlock, replica, continuationBlock, callSite, inlinePrefix);
            
            // 6. 移除调用指令（在返回值处理完成后）
            callBlock.removeInstruction(callSite);
            
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
        
        // 更新后继关系和前驱关系
        List<BasicBlock> successors = new ArrayList<>(originalBlock.getSuccessors());
        for (BasicBlock successor : successors) {
            originalBlock.removeSuccessor(successor);
            continuationBlock.addSuccessor(successor);
            
            // 重要：更新后继块的前驱关系
            successor.removePredecessor(originalBlock);
            successor.addPredecessor(continuationBlock);
            
            // 修复后继块中Phi指令的来边：将 originalBlock 替换为 continuationBlock
            for (Instruction succInst : new ArrayList<>(successor.getInstructions())) {
                if (succInst instanceof PhiInstruction phi) {
                    // 检查Phi指令的operands，确保它们与incoming映射一致
                    Map<BasicBlock, Value> incomingValues = phi.getIncomingValues();
                    Value incomingFromOriginal = incomingValues.remove(originalBlock);
                    if (incomingFromOriginal != null) {
                        incomingValues.put(continuationBlock, incomingFromOriginal);
                        
                        // 同时更新operands列表中的对应关系
                        List<BasicBlock> predecessors = successor.getPredecessors();
                        for (int i = 0; i < predecessors.size(); i++) {
                            if (predecessors.get(i) == continuationBlock && i < phi.getOperandCount()) {
                                // 确保operand与incoming value一致
                                phi.setOperand(i, incomingFromOriginal);
                                break;
                            }
                        }
                    }
                } else {
                    // Phi应位于基本块首部，遇到非Phi可提前结束
                    break;
                }
            }
        }
        
        return continuationBlock;
    }
    
    /**
     * 连接内联后的基本块
     */
    private void connectInlineBlocksLikeExample(BasicBlock callBlock, ReplicatedFunction replica, 
                                              BasicBlock continuationBlock, CallInstruction callSite, String inlinePrefix) {
        
        // 1. 从调用块跳转到内联函数入口
        BranchInstruction entryBranch = new BranchInstruction(replica.entryBlock);
        callBlock.addInstruction(entryBranch);
        callBlock.addSuccessor(replica.entryBlock);
        
        // 2. 将所有内联基本块插入到继续块之前
        Function callerFunction = callBlock.getParentFunction();
        
        // 先从调用者函数中移除所有内联块（它们在创建时已经添加到函数中）
        for (BasicBlock inlineBlock : replica.allBlocks) {
            if (callerFunction.getBasicBlocks().contains(inlineBlock)) {
                callerFunction.removeBasicBlock(inlineBlock);
            }
        }
        
        // 按顺序将内联块插入到继续块之前
        for (BasicBlock inlineBlock : replica.allBlocks) {
            callerFunction.addBasicBlockBefore(inlineBlock, continuationBlock);
            System.out.println("[DEBUG] 插入内联块: " + inlineBlock.getName() + " 到继续块之前");
        }
        
        // 3. 处理返回值
        handleReturnValues(replica, continuationBlock, callSite, inlinePrefix);
        
        // 4. 修复前驱关系
        for (BasicBlock exitBlock : replica.exitBlocks) {
            continuationBlock.addPredecessor(exitBlock);
        }
    }
    
    /**
     * 处理返回值 
     */
    private void handleReturnValues(ReplicatedFunction replica, BasicBlock continuationBlock, CallInstruction callSite, String inlinePrefix) {
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
                // 确保前驱关系
                if (!continuationBlock.getPredecessors().contains(exitBlock)) {
                    continuationBlock.addPredecessor(exitBlock);
                }
            }
        } else {
            // 非void函数，需要处理返回值
            if (replica.returnInstructions.size() == 1) {
                // 单个返回值
                ReturnInstruction returnInst = replica.returnInstructions.get(0);
                BasicBlock exitBlock = replica.exitBlocks.get(0);
                
                Value returnValue = returnInst.getReturnValue();
                if (returnValue != null) {
                    System.out.println("[DEBUG] 替换调用指令 " + callSite + " 为返回值 " + returnValue);
                    System.out.println("[DEBUG] 调用指令的用户数量: " + callSite.getUsers().size());
                    replaceAllUsages(callSite, returnValue);
                } else {
                    System.out.println("[DEBUG] 警告: 返回值为null!");
                }
                
                exitBlock.removeInstruction(returnInst);
                BranchInstruction branch = new BranchInstruction(continuationBlock);
                exitBlock.addInstruction(branch);
                exitBlock.addSuccessor(continuationBlock);
                // 确保前驱关系
                if (!continuationBlock.getPredecessors().contains(exitBlock)) {
                    continuationBlock.addPredecessor(exitBlock);
                }
                
            } else {
                // 多个返回值，使用Phi指令
                PhiInstruction resultPhi = new PhiInstruction(callSite.getType(), inlinePrefix + "result");
                continuationBlock.addInstructionFirst(resultPhi);
                
                // 首先保存所有的返回值，避免在移除指令后丢失
                List<Value> returnValues = new ArrayList<>();
                for (int i = 0; i < replica.returnInstructions.size(); i++) {
                    ReturnInstruction returnInst = replica.returnInstructions.get(i);
                    Value returnValue = returnInst.getReturnValue();
                    returnValues.add(returnValue);
                    System.out.println("[DEBUG] 保存返回值[" + i + "]: " + returnValue + 
                                     " from block " + replica.exitBlocks.get(i).getName());
                }
                
                // 建立基本块的连接关系
                for (int i = 0; i < replica.exitBlocks.size(); i++) {
                    BasicBlock exitBlock = replica.exitBlocks.get(i);
                    ReturnInstruction returnInst = replica.returnInstructions.get(i);
                    
                    // 移除return指令，建立跳转关系
                    exitBlock.removeInstruction(returnInst);
                    BranchInstruction branch = new BranchInstruction(continuationBlock);
                    exitBlock.addInstruction(branch);
                    exitBlock.addSuccessor(continuationBlock);
                }
                
                // 现在基本块连接关系已经建立，安全地添加phi的incoming values
                for (int i = 0; i < replica.exitBlocks.size(); i++) {
                    BasicBlock exitBlock = replica.exitBlocks.get(i);
                    Value returnValue = returnValues.get(i);
                    
                    if (returnValue != null) {
                        System.out.println("[DEBUG] 添加phi incoming: " + returnValue + " from block " + exitBlock.getName());
                        // 使用标准的addIncoming方法，确保正确的前驱关系
                        try {
                            // 确保前驱关系存在
                            if (!continuationBlock.getPredecessors().contains(exitBlock)) {
                                continuationBlock.addPredecessor(exitBlock);
                            }
                            resultPhi.addIncoming(returnValue, exitBlock);
                        } catch (Exception e) {
                            // 如果标准方法失败，使用直接操作
                        resultPhi.getIncomingValues().put(exitBlock, returnValue);
                        resultPhi.addOperand(returnValue);
                        }
                    } else {
                        System.out.println("[DEBUG] 警告: 返回值为null for block " + exitBlock.getName());
                    }
                }
                
                replaceAllUsages(callSite, resultPhi);
            }
        }
    }
    
    /**
     * 连接内联后的基本块
     */
    private void connectInlineBlocks(BasicBlock callBlock, ReplicatedFunction replica, 
                                   BasicBlock continuationBlock, CallInstruction callSite, String inlinePrefix) {
        
        System.out.println("[DEBUG] 连接内联块，调用块: " + callBlock.getName() + 
                          ", 继续块: " + continuationBlock.getName());
        System.out.println("[DEBUG] 内联函数有 " + replica.allBlocks.size() + " 个基本块");
        
        // 将内联函数的所有基本块插入到继续块之前
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
                    PhiInstruction resultPhi = new PhiInstruction(callSite.getType(), inlinePrefix + "result");
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
            
            // 第二步：正常复制所有指令（包括alloca）到对应的基本块中
            // 不再特殊处理alloca，让它们在正确的基本块中保持原有位置
            
            // 第三步：复制所有指令到对应的基本块中
            for (BasicBlock originalBlock : sourceFunction.getBasicBlocks()) {
                BasicBlock replicatedBlock = blockMap.get(originalBlock);
                
                for (Instruction originalInst : originalBlock.getInstructions()) {
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
                    AllocaInstruction replica = new AllocaInstruction(
                        allocaInst.getAllocatedType(),
                        inlinePrefix + allocaInst.getName()
                    );
                    valueMap.put(original, replica);
                    return replica;
                    
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
                    
                } else if (original instanceof GetElementPtrInstruction gepInst) {
                    List<Value> mappedIndices = new ArrayList<>();
                    for (Value index : gepInst.getIndices()) {
                        mappedIndices.add(getMappedValue(index));
                    }
                    GetElementPtrInstruction replica = new GetElementPtrInstruction(
                        getMappedValue(gepInst.getPointer()),
                        mappedIndices,
                        inlinePrefix + gepInst.getName()
                    );
                    valueMap.put(original, replica);
                    return replica;
                    
                } else if (original instanceof PhiInstruction phiInst) {
                    // 暂时跳过Phi指令的复制，这需要特殊处理
                    // 在实际编译器中，内联通常会避免包含复杂Phi指令的函数
                    System.out.println("[InlineExpansion] Warning: Skipping PhiInstruction in inline: " + phiInst.getName());
                    return null;
                    
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
                    // 清除原有的后继关系
                    for (BasicBlock successor : new ArrayList<>(parentBlock.getSuccessors())) {
                        parentBlock.removeSuccessor(successor);
                    }
                    
                    parentBlock.removeInstruction(brInst);
                    BranchInstruction newBr = new BranchInstruction(newTarget);
                    parentBlock.addInstruction(newBr);
                    parentBlock.addSuccessor(newTarget);
                }
            } else {
                BasicBlock newTrueTarget = blockMap.get(brInst.getTrueBlock());
                BasicBlock newFalseTarget = blockMap.get(brInst.getFalseBlock());
                
                if (newTrueTarget != null && newFalseTarget != null) {
                    // 清除原有的后继关系
                    for (BasicBlock successor : new ArrayList<>(parentBlock.getSuccessors())) {
                        parentBlock.removeSuccessor(successor);
                    }
                    
                    parentBlock.removeInstruction(brInst);
                    // 重要：条件值也需要重新映射
                    Value mappedCondition = getMappedValue(brInst.getCondition());
                    BranchInstruction newBr = new BranchInstruction(
                        mappedCondition, newTrueTarget, newFalseTarget);
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

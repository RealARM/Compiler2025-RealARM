package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.GlobalVariable;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Value.User;
import java.util.Map;
import java.util.HashMap;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.LoopAnalysis;
import MiddleEnd.Optimization.Analysis.Loop;

import java.util.*;

/**
 * 循环并行化优化器
 * 识别可以并行化的循环，并对其进行并行化转换
 */
public class LoopParallelizer implements Optimizer.ModuleOptimizer {
    
    private final boolean debug = true;
    private static final int MAX_BLOCK_THRESHOLD = 1000;
    private static final int DEFAULT_PARALLEL_NUM = 2;
    
    @Override
    public String getName() {
        return "LoopParallelizer";
    }
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        if (debug) {
            System.out.println("[LoopParallelizer] 开始循环并行化优化");
        }
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            // 跳过过大的函数以避免性能问题
            int blockCount = function.getBasicBlocks().size();
            if (blockCount > MAX_BLOCK_THRESHOLD) {
                if (debug) {
                    System.out.println("[LoopParallelizer] 函数 " + function.getName() + 
                        " 有 " + blockCount + " 个基本块，超过阈值 " + MAX_BLOCK_THRESHOLD + "，跳过");
                }
                continue;
            }
            
            changed |= runForFunction(function, module);
        }
        
        if (debug) {
            System.out.println("[LoopParallelizer] 并行化优化完成，是否有改动: " + changed);
        }
        
        return changed;
    }
    
    /**
     * 对单个函数进行并行化处理
     */
    private boolean runForFunction(Function function, Module module) {
        boolean changed = false;
        
        if (debug) {
            System.out.println("[LoopParallelizer] 分析函数: " + function.getName());
        }
        
        // 分析函数中的循环结构
        List<Loop> topLoops = LoopAnalysis.analyzeLoops(function);
        LoopAnalysis.analyzeInductionVariables(function);
        
        if (debug) {
            System.out.println("[LoopParallelizer] 找到 " + topLoops.size() + " 个顶层循环");
        }
        
        // 获取所有循环的DFS顺序（从内到外）
        List<Loop> allLoops = getAllLoopsInDFSOrder(topLoops);
        
        for (Loop loop : allLoops) {
            if (debug) {
                System.out.println("[LoopParallelizer] 检查循环，头块: " + loop.getHeader().getName());
            }
            
            // 尝试对循环进行并行化
            boolean success = tryParallelizeLoop(loop, module, DEFAULT_PARALLEL_NUM);
            if (success) {
                changed = true;
                if (debug) {
                    System.out.println("[LoopParallelizer] 成功并行化循环: " + loop.getHeader().getName());
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 获取所有循环的DFS顺序（内层循环优先）
     */
    private List<Loop> getAllLoopsInDFSOrder(List<Loop> topLoops) {
        List<Loop> allLoops = new ArrayList<>();
        for (Loop loop : topLoops) {
            addLoopsInDFSOrder(loop, allLoops);
        }
        return allLoops;
    }
    
    private void addLoopsInDFSOrder(Loop loop, List<Loop> allLoops) {
        // 先处理子循环
        for (Loop subLoop : loop.getSubLoops()) {
            addLoopsInDFSOrder(subLoop, allLoops);
        }
        // 再添加当前循环
        allLoops.add(loop);
    }
    
    /**
     * 尝试对循环进行并行化
     */
    private boolean tryParallelizeLoop(Loop loop, Module module, int parallelNum) {
        try {
            // 第一步：检查循环是否适合并行化
            if (!isLoopEligibleForParallelization(loop)) {
                if (debug) {
                    System.out.println("[LoopParallelizer] 循环不适合并行化: " + loop.getHeader().getName());
                }
                return false;
            }
            
            // 执行实际的并行化转换
            boolean success = performParallelization(loop, module, DEFAULT_PARALLEL_NUM);
            if (debug) {
                if (success) {
                    System.out.println("[LoopParallelizer] 成功并行化循环: " + loop.getHeader().getName());
                } else {
                    System.out.println("[LoopParallelizer] 并行化转换失败: " + loop.getHeader().getName());
                }
            }
            
            return success;
            
        } catch (Exception e) {
            if (debug) {
                System.out.println("[LoopParallelizer] 处理循环时出错: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }
    }
    
    /**
     * 检查循环是否适合并行化
     */
    private boolean isLoopEligibleForParallelization(Loop loop) {
        // 1. 检查是否有归纳变量
        if (!loop.hasInductionVariable()) {
            if (debug) {
                System.out.println("[LoopParallelizer] 循环没有归纳变量");
            }
            return false;
        }
        
        // 2. 检查循环结构的简单性
        if (!hasSimpleLoopStructure(loop)) {
            if (debug) {
                System.out.println("[LoopParallelizer] 循环结构太复杂");
            }
            return false;
        }
        
        // 3. 检查是否有简单的步长（应该是常数）
        if (!hasSimpleStepValue(loop)) {
            if (debug) {
                System.out.println("[LoopParallelizer] 循环步长不是简单常数");
            }
            return false;
        }
        
        // 4. 检查循环深度（避免过深嵌套）
        if (loop.getDepth() >= 3) {
            if (debug) {
                System.out.println("[LoopParallelizer] 循环嵌套过深: " + loop.getDepth());
            }
            return false;
        }
        
        // 5. 检查内存依赖
        if (!hasNoMemoryDependencies(loop)) {
            if (debug) {
                System.out.println("[LoopParallelizer] 循环存在内存依赖");
            }
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查循环是否有内存依赖
     */
    private boolean hasNoMemoryDependencies(Loop loop) {
        // 收集循环中的所有Load和Store指令
        List<LoadInstruction> loads = new ArrayList<>();
        List<StoreInstruction> stores = new ArrayList<>();
        List<CallInstruction> calls = new ArrayList<>();
        
        for (BasicBlock block : loop.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof LoadInstruction) {
                    loads.add((LoadInstruction) inst);
                } else if (inst instanceof StoreInstruction) {
                    stores.add((StoreInstruction) inst);
                } else if (inst instanceof CallInstruction) {
                    calls.add((CallInstruction) inst);
                }
            }
        }
        
        // 检查是否有函数调用（除了特定的安全函数）
        for (CallInstruction call : calls) {
            if (!isSafeCallForParallelization(call)) {
                if (debug) {
                    System.out.println("[LoopParallelizer] 发现不安全的函数调用: " + call.getCallee().getName());
                }
                return false;
            }
        }
        
        // 检查Load-Store冲突
        for (LoadInstruction load : loads) {
            for (StoreInstruction store : stores) {
                if (mayAlias(load.getPointer(), store.getPointer(), loop)) {
                    if (!isPhiRelatedAccess(load.getPointer(), loop) || 
                        !isPhiRelatedAccess(store.getPointer(), loop)) {
                        if (debug) {
                            System.out.println("[LoopParallelizer] 发现非phi相关的内存冲突");
                        }
                        return false;
                    }
                }
            }
        }
        
        // 检查Store-Store冲突
        for (int i = 0; i < stores.size(); i++) {
            for (int j = i + 1; j < stores.size(); j++) {
                StoreInstruction store1 = stores.get(i);
                StoreInstruction store2 = stores.get(j);
                if (mayAlias(store1.getPointer(), store2.getPointer(), loop)) {
                    if (!isPhiRelatedAccess(store1.getPointer(), loop) || 
                        !isPhiRelatedAccess(store2.getPointer(), loop)) {
                        if (debug) {
                            System.out.println("[LoopParallelizer] 发现Store-Store冲突");
                        }
                        return false;
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * 检查函数调用是否对并行化安全
     */
    private boolean isSafeCallForParallelization(CallInstruction call) {
        String funcName = call.getCallee().getName();
        
        // 允许的安全函数（只读或无副作用）
        Set<String> safeFunctions = Set.of(
            "@putint", "@putch", "@putfloat", "@putarray", "@putfarray"
        );
        
        return safeFunctions.contains(funcName);
    }
    
    /**
     * 简单的别名分析
     */
    private boolean mayAlias(Value ptr1, Value ptr2, Loop loop) {
        // 简化的别名分析
        if (ptr1 == ptr2) {
            return true;
        }
        
        // 如果两个指针都是基于不同的基础变量（alloca），则不冲突
        Value root1 = getPointerRoot(ptr1);
        Value root2 = getPointerRoot(ptr2);
        
        if (root1 != null && root2 != null && root1 != root2) {
            if (root1 instanceof AllocaInstruction && root2 instanceof AllocaInstruction) {
                return false;
            }
            // 不同的全局变量也不会冲突
            if (root1 instanceof GlobalVariable && root2 instanceof GlobalVariable) {
                return false;
            }
        }
        
        // 对于同一个数组的不同GEP访问，检查索引是否基于归纳变量
        if (ptr1 instanceof GetElementPtrInstruction && ptr2 instanceof GetElementPtrInstruction) {
            GetElementPtrInstruction gep1 = (GetElementPtrInstruction) ptr1;
            GetElementPtrInstruction gep2 = (GetElementPtrInstruction) ptr2;
            
            // 如果基指针相同，检查是否为相同的访问模式
            Value basePtr1 = getPointerRoot(gep1.getPointer());
            Value basePtr2 = getPointerRoot(gep2.getPointer());
            
            if (basePtr1 == basePtr2 && basePtr1 != null) {
                // 对于同一数组基于归纳变量的访问，在并行化时是安全的
                Value inductionVar = loop.getInductionVariable();
                if (inductionVar != null && 
                    isPhiRelatedAccess(ptr1, loop) && 
                    isPhiRelatedAccess(ptr2, loop)) {
                    return false; // 基于归纳变量的不同索引访问是安全的
                }
                return true; // 其他情况保守估计有冲突
            }
        }
        
        // 保守估计：其他情况假设可能别名
        return true;
    }
    
    /**
     * 获取指针的根变量
     */
    private Value getPointerRoot(Value ptr) {
        if (ptr instanceof AllocaInstruction || ptr instanceof GlobalVariable) {
            return ptr;
        }
        
        // 处理GEP指令：递归查找基指针
        if (ptr instanceof GetElementPtrInstruction) {
            GetElementPtrInstruction gep = (GetElementPtrInstruction) ptr;
            return getPointerRoot(gep.getPointer());
        }
        
        // 对于其他情况，保守返回原指针
        return ptr;
    }
    
    /**
     * 检查内存访问是否与归纳变量相关
     */
    private boolean isPhiRelatedAccess(Value ptr, Loop loop) {
        Value inductionVar = loop.getInductionVariable();
        if (inductionVar == null) {
            return false;
        }
        
        return containsValue(ptr, inductionVar);
    }
    
    /**
     * 检查值是否包含特定的目标值（使用递归搜索）
     */
    private boolean containsValue(Value value, Value target) {
        if (value == target) {
            return true;
        }
        
        // 避免无限递归的访问集合
        Set<Value> visited = new HashSet<>();
        return containsValueRecursive(value, target, visited);
    }
    
    /**
     * 递归检查值是否包含目标值（带访问记录防止无限递归）
     */
    private boolean containsValueRecursive(Value value, Value target, Set<Value> visited) {
        if (value == target) {
            return true;
        }
        
        if (visited.contains(value)) {
            return false;
        }
        visited.add(value);
        
        if (value instanceof Instruction) {
            Instruction inst = (Instruction) value;
            for (int i = 0; i < inst.getOperandCount(); i++) {
                if (containsValueRecursive(inst.getOperand(i), target, visited)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 检查循环是否有简单的结构
     */
    private boolean hasSimpleLoopStructure(Loop loop) {
        // 检查latch块数量（应该只有一个）
        if (loop.getLatchBlocks().size() != 1) {
            return false;
        }
        
        // 检查循环块数量（不应该太多）
        if (loop.getBlocks().size() > 10) {
            return false;
        }
        
        // 检查是否有子循环（暂时不支持嵌套循环的并行化）
        if (!loop.getSubLoops().isEmpty()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查循环是否有简单的步长值
     */
    private boolean hasSimpleStepValue(Loop loop) {
        Value stepValue = loop.getStepValue();
        if (stepValue == null) {
            return false;
        }
        
        // 检查步长是否为常数
        if (!(stepValue instanceof MiddleEnd.IR.Value.ConstantInt)) {
            return false;
        }
        
        MiddleEnd.IR.Value.ConstantInt stepConst = (MiddleEnd.IR.Value.ConstantInt) stepValue;
        int step = stepConst.getValue();
        
        // 步长应该为正数（暂时只支持递增循环）
        if (step <= 0) {
            return false;
        }
        
        // 步长不应该太大
        if (step > 16) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 执行实际的并行化转换 - 完整多线程版本
     */
    private boolean performParallelization(Loop loop, Module module, int parallelNum) {
        try {
            Function parallelStartFunc = module.getLibFunction("parallelStart");
            Function parallelEndFunc = module.getLibFunction("parallelEnd");
            
            if (parallelStartFunc == null || parallelEndFunc == null) {
                if (debug) {
                    System.out.println("[LoopParallelizer] 找不到并行化库函数");
                }
                return false;
            }
            
            // 获取循环的关键组件
            BasicBlock header = loop.getHeader();
            BasicBlock preheader = loop.getPreheader();
            if (preheader == null) {
                if (debug) {
                    System.out.println("[LoopParallelizer] 循环没有preheader");
                }
                return false;
            }
            
            // 获取归纳变量信息
            Value inductionVar = loop.getInductionVariable();
            Value initValue = loop.getInitValue();
            Value stepValue = loop.getStepValue();
            
            if (inductionVar == null || initValue == null || stepValue == null) {
                if (debug) {
                    System.out.println("[LoopParallelizer] 归纳变量信息不完整");
                }
                return false;
            }
            
            // 获取循环的退出块
            BasicBlock exitBlock = findLoopExitBlock(loop);
            if (exitBlock == null) {
                if (debug) {
                    System.out.println("[LoopParallelizer] 找不到循环退出块");
                }
                return false;
            }
            
            Function function = header.getParentFunction();
            
            // 第一步：创建并行入口块
            BasicBlock parallelEntry = new BasicBlock("parallel_entry", function);
            
            // 在并行入口调用parallelStart()
            CallInstruction parallelStartCall = new CallInstruction(parallelStartFunc, new ArrayList<>(), "parallel_start_call");
            parallelEntry.addInstruction(parallelStartCall);
            
            // 计算新的步长（步长乘以并行数量）
            ConstantInt parallelNumConst = new ConstantInt(parallelNum, IntegerType.I32);
            BinaryInstruction newStep = new BinaryInstruction(
                OpCode.MUL, stepValue, parallelNumConst, IntegerType.I32);
            parallelEntry.addInstruction(newStep);
            
            // 为每个线程计算起始索引
            List<Value> newStartIndexes = new ArrayList<>();
            for (int i = 0; i < parallelNum; i++) {
                ConstantInt threadIdConst = new ConstantInt(i, IntegerType.I32);
                BinaryInstruction startOffset = new BinaryInstruction(
                    OpCode.MUL, threadIdConst, stepValue, IntegerType.I32);
                BinaryInstruction newStartIndex = new BinaryInstruction(
                    OpCode.ADD, initValue, startOffset, IntegerType.I32);
                
                parallelEntry.addInstruction(startOffset);
                parallelEntry.addInstruction(newStartIndex);
                newStartIndexes.add(newStartIndex);
            }
            
            // 第二步：创建多线程分支结构
            BasicBlock currentBranch = parallelEntry;
            List<List<BasicBlock>> copiedLoops = new ArrayList<>();
            
            // 为每个线程（除了第0个）创建循环副本
            for (int i = 0; i < parallelNum - 1; i++) {
                // 创建条件检查：if (threadId == i)
                ConstantInt threadIdConst = new ConstantInt(i, IntegerType.I32);
                BinaryInstruction threadIdCheck = new BinaryInstruction(
                    OpCode.EQ, parallelStartCall, threadIdConst, IntegerType.I1);
                currentBranch.addInstruction(threadIdCheck);
                
                // 创建新的分支块
                BasicBlock nextBranch = new BasicBlock("thread_branch_" + (i + 1), function);
                
                // 复制循环
                List<BasicBlock> copiedLoop = copyLoopForThread(loop, function, i + 1);
                copiedLoops.add(copiedLoop);
                
                // 修改复制的循环的起始索引
                if (!copiedLoop.isEmpty() && copiedLoop.get(0) != null) {
                    modifyLoopStartIndex(copiedLoop.get(0), loop, newStartIndexes.get(i + 1), newStep);
                }
                
                // 创建条件分支指令
                BranchInstruction condBranch = new BranchInstruction(threadIdCheck, copiedLoop.get(0), nextBranch);
                // 移除之前可能添加的跳转（如果存在）
                Instruction lastInst = currentBranch.getLastInstruction();
                if (lastInst instanceof BranchInstruction) {
                    currentBranch.removeInstruction(lastInst);
                }
                currentBranch.addInstruction(condBranch);
                
                currentBranch = nextBranch;
            }
            
            // 最后一个分支跳转到原始循环（线程0）
            BranchInstruction brToOriginal = new BranchInstruction(header);
            currentBranch.addInstruction(brToOriginal);
            
            // 第三步：创建并行结束块
            BasicBlock parallelExit = new BasicBlock("parallel_exit", function);
            
            // 创建phi指令收集所有线程的结果
            PhiInstruction endPhi = new PhiInstruction(IntegerType.I32, "end_phi");
            ConstantInt endZeroConst = new ConstantInt(0, IntegerType.I32);
            endPhi.addIncoming(endZeroConst, header);
            
            // 为每个复制的循环添加phi输入
            for (int i = 0; i < copiedLoops.size(); i++) {
                List<BasicBlock> copiedLoop = copiedLoops.get(i);
                if (!copiedLoop.isEmpty()) {
                    BasicBlock copiedHeader = copiedLoop.get(0);
                    ConstantInt threadResult = new ConstantInt(i + 1, IntegerType.I32);
                    endPhi.addIncoming(threadResult, copiedHeader);
                }
            }
            
            parallelExit.addInstruction(endPhi);
            
            List<Value> endCallArgs = new ArrayList<>();
            endCallArgs.add(endPhi);
            CallInstruction parallelEndCall = new CallInstruction(parallelEndFunc, endCallArgs, "parallel_end_call");
            parallelExit.addInstruction(parallelEndCall);
            
            BranchInstruction brToExit = new BranchInstruction(exitBlock);
            parallelExit.addInstruction(brToExit);
            
            // 第四步：更新控制流
            insertBasicBlockAfter(function, preheader, parallelEntry);
            
            // 插入所有分支块
            BasicBlock lastInserted = parallelEntry;
            for (int i = 1; i < parallelNum; i++) {
                BasicBlock branchBlock = findBranchBlock(function, "thread_branch_" + i);
                if (branchBlock != null) {
                    insertBasicBlockAfter(function, lastInserted, branchBlock);
                    lastInserted = branchBlock;
                }
            }
            
            // 插入所有复制的循环块
            for (List<BasicBlock> copiedLoop : copiedLoops) {
                for (BasicBlock block : copiedLoop) {
                    insertBasicBlockAfter(function, lastInserted, block);
                    lastInserted = block;
                }
            }
            
            insertBasicBlockBefore(function, exitBlock, parallelExit);
            
            // 更新原始循环的控制流
            replaceSuccessor(preheader, header, parallelEntry);
            replaceSuccessor(header, exitBlock, parallelExit);
            
            // 更新前驱后继关系
            header.removePredecessor(preheader);
            header.addPredecessor(currentBranch); // 最后一个分支块指向原始循环
            
            exitBlock.removePredecessor(header);
            exitBlock.addPredecessor(parallelExit);
            parallelExit.addPredecessor(header);
            
            // 为每个复制的循环添加到parallelExit的连接
            for (List<BasicBlock> copiedLoop : copiedLoops) {
                if (!copiedLoop.isEmpty()) {
                    BasicBlock copiedHeader = copiedLoop.get(0);
                    parallelExit.addPredecessor(copiedHeader);
                }
            }
            
            // 第五步：更新原始循环的归纳变量
            if (inductionVar instanceof PhiInstruction) {
                PhiInstruction phi = (PhiInstruction) inductionVar;
                phi.removeIncoming(preheader);
                phi.addIncoming(newStartIndexes.get(0), currentBranch); // 线程0的起始索引
                
                // 更新步长
                Instruction updateInst = loop.getUpdateInstruction();
                if (updateInst instanceof BinaryInstruction) {
                    BinaryInstruction binInst = (BinaryInstruction) updateInst;
                    BinaryInstruction newUpdateInst = new BinaryInstruction(
                        binInst.getOpCode(), inductionVar, newStep, binInst.getType());
                    binInst.getParent().addInstructionBefore(newUpdateInst, binInst);
                    
                    // 替换所有使用
                    for (User user : new ArrayList<>(binInst.getUsers())) {
                        for (int i = 0; i < user.getOperandCount(); i++) {
                            if (user.getOperand(i) == binInst) {
                                user.setOperand(i, newUpdateInst);
                            }
                        }
                    }
                    binInst.removeFromParent();
                }
            }
            
            if (debug) {
                System.out.println("[LoopParallelizer] 多线程并行化转换完成，并行数: " + parallelNum);
            }
            
            return true;
            
        } catch (Exception e) {
            if (debug) {
                System.out.println("[LoopParallelizer] 并行化转换过程中出错: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }
    }
    
    /**
     * 为特定线程复制整个循环 - 完整版本
     */
    private List<BasicBlock> copyLoopForThread(Loop loop, Function function, int threadId) {
        if (debug) {
            System.out.println("[LoopParallelizer] 为线程 " + threadId + " 复制完整循环");
        }
        
        List<BasicBlock> copiedBlocks = new ArrayList<>();
        Map<BasicBlock, BasicBlock> blockMapping = new HashMap<>();
        Map<Value, Value> valueMapping = new HashMap<>();
        
        // 第一步：为所有循环块创建副本
        for (BasicBlock originalBlock : loop.getBlocks()) {
            String newName = originalBlock.getName() + "_thread" + threadId;
            BasicBlock copiedBlock = new BasicBlock(newName, function);
            copiedBlocks.add(copiedBlock);
            blockMapping.put(originalBlock, copiedBlock);
            valueMapping.put(originalBlock, copiedBlock);
            
            if (debug) {
                System.out.println("[LoopParallelizer] 创建循环块副本: " + newName);
            }
        }
        
        // 第二步：复制所有指令并建立值映射
        for (BasicBlock originalBlock : loop.getBlocks()) {
            BasicBlock copiedBlock = blockMapping.get(originalBlock);
            copyInstructionsToBlock(originalBlock, copiedBlock, valueMapping, threadId);
        }
        
        // 第三步：更新所有指令中的引用
        for (BasicBlock copiedBlock : copiedBlocks) {
            updateInstructionReferences(copiedBlock, valueMapping, blockMapping);
        }
        
        // 第四步：建立控制流关系
        establishControlFlowForCopiedLoop(new ArrayList<>(loop.getBlocks()), blockMapping);
        
        if (debug) {
            System.out.println("[LoopParallelizer] 完成线程 " + threadId + " 的循环复制，共 " + copiedBlocks.size() + " 个块");
        }
        
        return copiedBlocks;
    }
    
    /**
     * 复制基本块中的所有指令
     */
    private void copyInstructionsToBlock(BasicBlock originalBlock, BasicBlock copiedBlock, 
                                       Map<Value, Value> valueMapping, int threadId) {
        for (Instruction originalInst : originalBlock.getInstructions()) {
            Instruction copiedInst = copyInstructionComplete(originalInst, valueMapping, threadId);
            if (copiedInst != null) {
                copiedBlock.addInstruction(copiedInst);
                valueMapping.put(originalInst, copiedInst);
                
                if (debug && originalInst instanceof PhiInstruction) {
                    System.out.println("[LoopParallelizer] 复制Phi指令: " + originalInst.getName() + 
                                     " -> " + copiedInst.getName());
                }
            }
        }
    }
    
    /**
     * 完整的指令复制方法
     */
    private Instruction copyInstructionComplete(Instruction original, Map<Value, Value> valueMapping, int threadId) {
        String newName = original.getName() + "_thread" + threadId;
        
        if (original instanceof PhiInstruction) {
            PhiInstruction originalPhi = (PhiInstruction) original;
            PhiInstruction copiedPhi = new PhiInstruction(originalPhi.getType(), newName);
            
            // 先创建空的Phi指令，稍后在更新引用时填充incoming值
            // 这样避免了循环依赖问题
            return copiedPhi;
            
        } else if (original instanceof BinaryInstruction) {
            BinaryInstruction originalBin = (BinaryInstruction) original;
            BinaryInstruction copiedBin = new BinaryInstruction(
                originalBin.getOpCode(),
                originalBin.getLeft(),  // 稍后更新
                originalBin.getRight(), // 稍后更新
                originalBin.getType()
            );
            copiedBin.setName(newName);
            return copiedBin;
            
        } else if (original instanceof LoadInstruction) {
            LoadInstruction originalLoad = (LoadInstruction) original;
            LoadInstruction copiedLoad = new LoadInstruction(
                originalLoad.getPointer(), // 稍后更新
                newName
            );
            return copiedLoad;
            
        } else if (original instanceof StoreInstruction) {
            StoreInstruction originalStore = (StoreInstruction) original;
            StoreInstruction copiedStore = new StoreInstruction(
                originalStore.getValue(),  // 稍后更新
                originalStore.getPointer() // 稍后更新
            );
            return copiedStore;
            
        } else if (original instanceof GetElementPtrInstruction) {
            GetElementPtrInstruction originalGep = (GetElementPtrInstruction) original;
            // 复制索引列表
            List<Value> copiedIndices = new ArrayList<>();
            for (Value index : originalGep.getIndices()) {
                copiedIndices.add(index); // 稍后更新
            }
            
            GetElementPtrInstruction copiedGep = new GetElementPtrInstruction(
                originalGep.getPointer(), // 稍后更新
                copiedIndices,
                newName
            );
            return copiedGep;
            
        } else if (original instanceof CallInstruction) {
            CallInstruction originalCall = (CallInstruction) original;
            // 复制参数列表
            List<Value> copiedArgs = new ArrayList<>();
            for (Value arg : originalCall.getArguments()) {
                copiedArgs.add(arg); // 稍后更新
            }
            
            CallInstruction copiedCall = new CallInstruction(
                originalCall.getCallee(),
                copiedArgs,
                newName
            );
            return copiedCall;
            
        } else if (original instanceof CompareInstruction) {
            CompareInstruction originalCmp = (CompareInstruction) original;
            // CompareInstruction需要特殊处理，让我们检查它的具体实现
            // 暂时跳过这个类型
            if (debug) {
                System.out.println("[LoopParallelizer] 跳过CompareInstruction复制: " + originalCmp.getName());
            }
            return null;
            
        } else if (original instanceof BranchInstruction) {
            BranchInstruction originalBr = (BranchInstruction) original;
            if (originalBr.isUnconditional()) {
                return new BranchInstruction(originalBr.getTrueBlock()); // 稍后更新
            } else {
                return new BranchInstruction(
                    originalBr.getCondition(), // 稍后更新
                    originalBr.getTrueBlock(), // 稍后更新
                    originalBr.getFalseBlock() // 稍后更新
                );
            }
        }
        
        // 对于不支持的指令类型，返回null（但这不应该发生在简单循环中）
        if (debug) {
            System.out.println("[LoopParallelizer] 警告：未处理的指令类型: " + original.getClass().getSimpleName());
        }
        return null;
    }
    
    /**
     * 更新基本块中所有指令的引用
     */
    private void updateInstructionReferences(BasicBlock copiedBlock, Map<Value, Value> valueMapping, 
                                           Map<BasicBlock, BasicBlock> blockMapping) {
        for (Instruction inst : copiedBlock.getInstructions()) {
            updateSingleInstructionReferences(inst, valueMapping, blockMapping);
        }
    }
    
    /**
     * 更新单个指令中的所有引用
     */
    private void updateSingleInstructionReferences(Instruction inst, Map<Value, Value> valueMapping, 
                                                 Map<BasicBlock, BasicBlock> blockMapping) {
        // 更新普通操作数引用
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            Value mappedOperand = valueMapping.getOrDefault(operand, operand);
            inst.setOperand(i, mappedOperand);
        }
        
        // 特殊处理Phi指令的incoming值
        if (inst instanceof PhiInstruction) {
            updatePhiIncomingValues((PhiInstruction) inst, valueMapping, blockMapping);
        }
        
        // 特殊处理分支指令的目标块
        if (inst instanceof BranchInstruction) {
            updateBranchTargets((BranchInstruction) inst, blockMapping);
        }
        
        // 特殊处理GEP指令的索引
        if (inst instanceof GetElementPtrInstruction) {
            updateGepIndices((GetElementPtrInstruction) inst, valueMapping);
        }
        
        // 特殊处理Call指令的参数
        if (inst instanceof CallInstruction) {
            updateCallArguments((CallInstruction) inst, valueMapping);
        }
    }
    
    /**
     * 更新Phi指令的incoming值 - 参考Mem2Reg的实现
     */
    private void updatePhiIncomingValues(PhiInstruction phi, Map<Value, Value> valueMapping, 
                                       Map<BasicBlock, BasicBlock> blockMapping) {
        // 获取原始Phi指令（通过名称找到）
        PhiInstruction originalPhi = findOriginalPhi(phi, valueMapping);
        if (originalPhi == null) {
            if (debug) {
                System.out.println("[LoopParallelizer] 警告：找不到Phi指令的原始版本: " + phi.getName());
            }
            return;
        }
        
        // 清除现有的incoming值
        for (BasicBlock block : new ArrayList<>(originalPhi.getIncomingValues().keySet())) {
            phi.removeIncoming(block);
        }
        
        // 复制原始Phi的incoming值，并更新引用
        Map<BasicBlock, Value> originalIncoming = originalPhi.getIncomingValues();
        for (Map.Entry<BasicBlock, Value> entry : originalIncoming.entrySet()) {
            BasicBlock originalBlock = entry.getKey();
            Value originalValue = entry.getValue();
            
            // 映射基本块
            BasicBlock mappedBlock = blockMapping.getOrDefault(originalBlock, originalBlock);
            
            // 映射值
            Value mappedValue = valueMapping.getOrDefault(originalValue, originalValue);
            
            // 添加新的incoming值
            phi.addIncoming(mappedValue, mappedBlock);
            
            if (debug) {
                System.out.println("[LoopParallelizer] Phi " + phi.getName() + 
                                 " 添加incoming: " + mappedValue.getName() + 
                                 " from " + mappedBlock.getName());
            }
        }
    }
    
    /**
     * 找到Phi指令的原始版本
     */
    private PhiInstruction findOriginalPhi(PhiInstruction copiedPhi, Map<Value, Value> valueMapping) {
        for (Map.Entry<Value, Value> entry : valueMapping.entrySet()) {
            if (entry.getValue() == copiedPhi && entry.getKey() instanceof PhiInstruction) {
                return (PhiInstruction) entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * 更新分支指令的目标块
     */
    private void updateBranchTargets(BranchInstruction branch, Map<BasicBlock, BasicBlock> blockMapping) {
        if (branch.isUnconditional()) {
            BasicBlock target = branch.getTrueBlock();
            BasicBlock mappedTarget = blockMapping.getOrDefault(target, target);
            
            // 如果目标块发生了映射，需要更新分支指令
            if (mappedTarget != target) {
                BasicBlock parent = branch.getParent();
                parent.removeInstruction(branch);
                BranchInstruction newBranch = new BranchInstruction(mappedTarget);
                parent.addInstruction(newBranch);
            }
            
        } else {
            BasicBlock trueTarget = branch.getTrueBlock();
            BasicBlock falseTarget = branch.getFalseBlock();
            
            BasicBlock mappedTrueTarget = blockMapping.getOrDefault(trueTarget, trueTarget);
            BasicBlock mappedFalseTarget = blockMapping.getOrDefault(falseTarget, falseTarget);
            
            // 如果任一目标块发生了映射，需要更新分支指令
            if (mappedTrueTarget != trueTarget || mappedFalseTarget != falseTarget) {
                BasicBlock parent = branch.getParent();
                parent.removeInstruction(branch);
                BranchInstruction newBranch = new BranchInstruction(
                    branch.getCondition(), mappedTrueTarget, mappedFalseTarget);
                parent.addInstruction(newBranch);
            }
        }
    }
    
    /**
     * 更新GEP指令的索引
     */
    private void updateGepIndices(GetElementPtrInstruction gep, Map<Value, Value> valueMapping) {
        List<Value> indices = gep.getIndices();
        for (int i = 0; i < indices.size(); i++) {
            Value index = indices.get(i);
            Value mappedIndex = valueMapping.getOrDefault(index, index);
            indices.set(i, mappedIndex);
        }
    }
    
    /**
     * 更新Call指令的参数
     */
    private void updateCallArguments(CallInstruction call, Map<Value, Value> valueMapping) {
        List<Value> args = call.getArguments();
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            Value mappedArg = valueMapping.getOrDefault(arg, arg);
            args.set(i, mappedArg);
        }
    }
    
    /**
     * 建立复制循环的控制流关系
     */
    private void establishControlFlowForCopiedLoop(List<BasicBlock> originalBlocks, 
                                                 Map<BasicBlock, BasicBlock> blockMapping) {
        for (BasicBlock originalBlock : originalBlocks) {
            BasicBlock copiedBlock = blockMapping.get(originalBlock);
            
            // 复制前驱关系
            for (BasicBlock originalPred : originalBlock.getPredecessors()) {
                BasicBlock mappedPred = blockMapping.get(originalPred);
                if (mappedPred != null) { // 只处理循环内的前驱
                    copiedBlock.addPredecessor(mappedPred);
                    mappedPred.addSuccessor(copiedBlock);
                }
            }
            
            if (debug) {
                System.out.println("[LoopParallelizer] 建立控制流: " + copiedBlock.getName() + 
                                 " 前驱数: " + copiedBlock.getPredecessors().size() + 
                                 " 后继数: " + copiedBlock.getSuccessors().size());
            }
        }
    }
    
    /**
     * 获取循环中所有块的DFS顺序
     */
    private List<BasicBlock> getLoopBlocksInDFSOrder(Loop loop) {
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        Set<BasicBlock> loopBlocks = new HashSet<>(loop.getBlocks());
        
        dfsVisitBlocks(loop.getHeader(), visited, result, loopBlocks);
        return result;
    }
    
    private void dfsVisitBlocks(BasicBlock block, Set<BasicBlock> visited, 
                               List<BasicBlock> result, Set<BasicBlock> loopBlocks) {
        if (visited.contains(block) || !loopBlocks.contains(block)) {
            return;
        }
        
        visited.add(block);
        result.add(block);
        
        for (BasicBlock successor : block.getSuccessors()) {
            if (loopBlocks.contains(successor)) {
                dfsVisitBlocks(successor, visited, result, loopBlocks);
            }
        }
    }
    
    /**
     * 复制单个指令 - 增强版本
     */
    private Instruction copyInstruction(Instruction original, Map<Value, Value> valueMap, 
                                      Map<BasicBlock, BasicBlock> blockMap) {
        return copyInstructionComplete(original, valueMap, 0);
    }
    
    /**
     * 更新指令中的引用
     */
    private void updateInstructionReferences(Instruction inst, Map<Value, Value> valueMap, 
                                           Map<BasicBlock, BasicBlock> blockMap) {
        // 更新操作数引用
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            if (valueMap.containsKey(operand)) {
                inst.setOperand(i, valueMap.get(operand));
            }
        }
        
        // 更新基本块引用
        if (inst instanceof BranchInstruction) {
            BranchInstruction br = (BranchInstruction) inst;
            if (br.isUnconditional()) {
                BasicBlock target = br.getTrueBlock();
                if (blockMap.containsKey(target)) {
                    // 创建新的分支指令
                    BranchInstruction newBr = new BranchInstruction(blockMap.get(target));
                    BasicBlock parent = inst.getParent();
                    if (parent != null) {
                        parent.removeInstruction(inst);
                        parent.addInstruction(newBr);
                    }
                }
            } else {
                BasicBlock trueBlock = br.getTrueBlock();
                BasicBlock falseBlock = br.getFalseBlock();
                boolean changed = false;
                
                if (blockMap.containsKey(trueBlock)) {
                    trueBlock = blockMap.get(trueBlock);
                    changed = true;
                }
                if (blockMap.containsKey(falseBlock)) {
                    falseBlock = blockMap.get(falseBlock);
                    changed = true;
                }
                
                if (changed) {
                    BranchInstruction newBr = new BranchInstruction(br.getCondition(), trueBlock, falseBlock);
                    BasicBlock parent = inst.getParent();
                    if (parent != null) {
                        parent.removeInstruction(inst);
                        parent.addInstruction(newBr);
                    }
                }
            }
        }
        
        // 更新Phi指令的incoming值
        if (inst instanceof PhiInstruction) {
            PhiInstruction phi = (PhiInstruction) inst;
            Map<BasicBlock, Value> oldIncoming = new HashMap<>(phi.getIncomingValues());
            
            // 清除旧的incoming值
            for (BasicBlock block : new ArrayList<>(oldIncoming.keySet())) {
                phi.removeIncoming(block);
            }
            
            // 重新添加更新后的incoming值
            for (Map.Entry<BasicBlock, Value> entry : oldIncoming.entrySet()) {
                Value value = entry.getValue();
                BasicBlock block = entry.getKey();
                
                if (valueMap.containsKey(value)) {
                    value = valueMap.get(value);
                }
                if (blockMap.containsKey(block)) {
                    block = blockMap.get(block);
                }
                
                phi.addIncoming(value, block);
            }
        }
    }
    
    /**
     * 修改复制循环的起始索引
     */
    private void modifyLoopStartIndex(BasicBlock copiedHeader, Loop originalLoop, 
                                    Value newStartIndex, Value newStep) {
        for (Instruction inst : copiedHeader.getInstructions()) {
            if (inst instanceof PhiInstruction) {
                PhiInstruction phi = (PhiInstruction) inst;
                // 假设这是归纳变量的phi
                Map<BasicBlock, Value> incomingValues = phi.getIncomingValues();
                if (incomingValues.size() >= 1) {
                    // 更新来自preheader的值为新的起始索引
                    for (Map.Entry<BasicBlock, Value> entry : new ArrayList<>(incomingValues.entrySet())) {
                        BasicBlock block = entry.getKey();
                        // 如果这是来自循环外部的值，更新为新的起始索引
                        if (!originalLoop.getBlocks().contains(block)) {
                            phi.removeIncoming(block);
                            phi.addIncoming(newStartIndex, block);
                            break;
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 查找特定名称的分支块
     */
    private BasicBlock findBranchBlock(Function function, String name) {
        for (BasicBlock block : function.getBasicBlocks()) {
            if (block.getName().equals(name)) {
                return block;
            }
        }
        return null;
    }
    
    /**
     * 找到循环的退出块
     */
    private BasicBlock findLoopExitBlock(Loop loop) {
        for (BasicBlock block : loop.getExitBlocks()) {
            return block; // 返回第一个退出块
        }
        return null;
    }
    
    /**
     * 替换基本块的后继
     */
    private void replaceSuccessor(BasicBlock block, BasicBlock oldSucc, BasicBlock newSucc) {
        // 这个方法需要根据具体的IR结构实现
        // 更新跳转指令的目标
        if (!block.getInstructions().isEmpty()) {
            Instruction terminator = block.getLastInstruction();
            if (terminator instanceof BranchInstruction) {
                BranchInstruction br = (BranchInstruction) terminator;
                if (br.isUnconditional() && br.getTrueBlock() == oldSucc) {
                    // 创建新的无条件跳转指令
                    block.removeInstruction(br);
                    BranchInstruction newBr = new BranchInstruction(newSucc);
                    block.addInstruction(newBr);
                } else if (!br.isUnconditional()) {
                    // 处理条件跳转
                    BasicBlock trueBlock = br.getTrueBlock();
                    BasicBlock falseBlock = br.getFalseBlock();
                    boolean changed = false;
                    
                    if (trueBlock == oldSucc) {
                        trueBlock = newSucc;
                        changed = true;
                    }
                    if (falseBlock == oldSucc) {
                        falseBlock = newSucc;
                        changed = true;
                    }
                    
                    if (changed) {
                        block.removeInstruction(br);
                        BranchInstruction newBr = new BranchInstruction(br.getCondition(), trueBlock, falseBlock);
                        block.addInstruction(newBr);
                    }
                }
            }
        }
        
        // 更新前驱后继关系
        block.removeSuccessor(oldSucc);
        block.addSuccessor(newSucc);
        oldSucc.removePredecessor(block);
        newSucc.addPredecessor(block);
    }
    

    
    /**
     * 在指定基本块后插入新基本块
     */
    private void insertBasicBlockAfter(Function function, BasicBlock after, BasicBlock newBlock) {
        List<BasicBlock> blocks = function.getBasicBlocks();
        int index = blocks.indexOf(after);
        if (index >= 0) {
            blocks.add(index + 1, newBlock);
        } else {
            blocks.add(newBlock);
        }
    }
    
    /**
     * 在指定基本块前插入新基本块
     */
    private void insertBasicBlockBefore(Function function, BasicBlock before, BasicBlock newBlock) {
        List<BasicBlock> blocks = function.getBasicBlocks();
        int index = blocks.indexOf(before);
        if (index > 0) {
            blocks.add(index, newBlock);
        } else {
            blocks.add(0, newBlock);
        }
    }
} 
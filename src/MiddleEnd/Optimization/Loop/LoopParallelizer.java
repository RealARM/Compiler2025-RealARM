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
            String loopSuffix = header.getName();
            BasicBlock parallelEntry = new BasicBlock("parallel_entry_" + loopSuffix, function);
            
            // 在并行入口调用parallelStart()
            CallInstruction parallelStartCall = new CallInstruction(parallelStartFunc, new ArrayList<>(), "parallel_start_call");
            parallelEntry.addInstruction(parallelStartCall);
            
            // 计算新的步长（步长乘以并行数量）
            ConstantInt parallelNumConst = new ConstantInt(parallelNum, IntegerType.I32);
            BinaryInstruction newStep = new BinaryInstruction(
                OpCode.MUL, stepValue, parallelNumConst, IntegerType.I32);
            newStep.setName("new_step");
            parallelEntry.addInstruction(newStep);
            
            // 为每个线程计算起始索引
            List<Value> newStartIndexes = new ArrayList<>();
            for (int i = 0; i < parallelNum; i++) {
                ConstantInt threadIdConst = new ConstantInt(i, IntegerType.I32);
                BinaryInstruction startOffset = new BinaryInstruction(
                    OpCode.MUL, threadIdConst, stepValue, IntegerType.I32);
                BinaryInstruction newStartIndex = new BinaryInstruction(
                    OpCode.ADD, initValue, startOffset, IntegerType.I32);
                startOffset.setName("start_offset_" + i);
                newStartIndex.setName("new_start_index_" + i);
                
                parallelEntry.addInstruction(startOffset);
                parallelEntry.addInstruction(newStartIndex);
                newStartIndexes.add(newStartIndex);
            }
            
            // 第二步：创建多线程分支结构
            BasicBlock currentBranch = parallelEntry;
            List<List<BasicBlock>> copiedLoops = new ArrayList<>();
            List<BasicBlock> threadBranches = new ArrayList<>();
            
            // 为每个线程（除了第0个）创建循环副本
            for (int i = 0; i < parallelNum - 1; i++) {
                // 创建条件检查：if (threadId == i + 1), because thread 0 is the original loop
                ConstantInt threadIdConst = new ConstantInt(i + 1, IntegerType.I32);
                CompareInstruction threadIdCheck = new CompareInstruction(
                    OpCode.ICMP,
                    OpCode.EQ,
                    parallelStartCall,
                    threadIdConst
                );
                threadIdCheck.setName("thread_id_check_" + (i + 1));
                currentBranch.addInstruction(threadIdCheck);
                
                // 创建新的分支块
                BasicBlock nextBranch = new BasicBlock("thread_branch_" + (i + 1) + "_" + loopSuffix, function);
                threadBranches.add(nextBranch);
                
                // 复制循环
                List<BasicBlock> copiedLoop = copyLoopForThread(loop, function, i + 1);
                copiedLoops.add(copiedLoop);
                
                // 修改复制的循环的起始索引和步长
                if (!copiedLoop.isEmpty() && copiedLoop.get(0) != null) {
                    modifyLoopStartIndex(copiedLoop.get(0), loop, newStartIndexes.get(i + 1), currentBranch);
                    fixInductionVariableUpdate(copiedLoop, loop, newStep);
                    ensureInductionVariableUpdate(copiedLoop, loop, newStep);
                }
                
                // 创建条件分支指令
                BranchInstruction condBranch = new BranchInstruction(threadIdCheck, copiedLoop.get(0), nextBranch);
                Instruction lastInst = currentBranch.getLastInstruction();
                if (lastInst instanceof BranchInstruction) {
                    currentBranch.removeInstruction(lastInst);
                }
                currentBranch.addInstruction(condBranch);
                // Maintain CFG relationships for the new conditional branch
                copiedLoop.get(0).addPredecessor(currentBranch);
                currentBranch.addSuccessor(copiedLoop.get(0));
                nextBranch.addPredecessor(currentBranch);
                currentBranch.addSuccessor(nextBranch);
                
                currentBranch = nextBranch;
            }
            
            // 最后一个分支跳转到原始循环（线程0）
            BranchInstruction brToOriginal = new BranchInstruction(header);
            currentBranch.addInstruction(brToOriginal);
            currentBranch.addSuccessor(header);
            
            // 第三步：创建并行结束块
            BasicBlock parallelExit = new BasicBlock("parallel_exit_" + loopSuffix, function);
            
            // 创建phi指令收集所有线程的结果
            PhiInstruction endPhi = new PhiInstruction(IntegerType.I32, "end_phi");
            
            parallelExit.addInstruction(endPhi);
            
            List<Value> endCallArgs = new ArrayList<>();
            endCallArgs.add(endPhi);
            CallInstruction parallelEndCall = new CallInstruction(parallelEndFunc, endCallArgs, "parallel_end_call");
            parallelExit.addInstruction(parallelEndCall);
            
            BranchInstruction brToExit = new BranchInstruction(exitBlock);
            parallelExit.addInstruction(brToExit);
            
            // 第四步: 批量更新函数的基本块列表
            List<BasicBlock> newBlocks = new ArrayList<>();
            newBlocks.add(parallelEntry);
            newBlocks.addAll(threadBranches);
            for (List<BasicBlock> copied : copiedLoops) {
                newBlocks.addAll(copied);
            }
            newBlocks.add(parallelExit);

            int preheaderIndex = function.getBasicBlocks().indexOf(preheader);
            List<BasicBlock> funcBlocks = function.getBasicBlocks();
            if (preheaderIndex != -1) {
                int insertIndex = preheaderIndex + 1;
                for (BasicBlock nb : newBlocks) {
                    if (!funcBlocks.contains(nb)) {
                        funcBlocks.add(insertIndex, nb);
                        insertIndex++;
                    }
                }
            } else {
                int insertIndex = Math.min(1, funcBlocks.size());
                for (BasicBlock nb : newBlocks) {
                    if (!funcBlocks.contains(nb)) {
                        funcBlocks.add(insertIndex, nb);
                        insertIndex++;
                    }
                }
            }

            // 第五步：更新控制流
            replaceSuccessor(preheader, header, parallelEntry);
            
            // 更新所有循环出口到 parallelExit
            List<BasicBlock> loopExits = new ArrayList<>(loop.getExitBlocks());
            for(BasicBlock latch : loop.getLatchBlocks()) {
                replaceSuccessor(latch, loopExits.get(0), parallelExit);
            }

            for (List<BasicBlock> copiedLoop : copiedLoops) {
                if (copiedLoop.isEmpty()) continue;
                BasicBlock copiedHeader = copiedLoop.get(0);
                
                // 查找复制的循环的latch块
                for(BasicBlock copiedBlock : copiedLoop) {
                     Instruction terminator = copiedBlock.getTerminator();
                     if (terminator instanceof BranchInstruction) {
                         BranchInstruction br = (BranchInstruction) terminator;
                         if (br.isUnconditional() && br.getTrueBlock() == copiedHeader) { // This is a latch
                             // This is not a reliable way to find exits
                         } else if (!br.isUnconditional()) {
                            if(br.getTrueBlock() != copiedHeader && !copiedLoop.contains(br.getTrueBlock())) {
                                replaceSuccessor(copiedBlock, br.getTrueBlock(), parallelExit);
                            }
                             if(br.getFalseBlock() != copiedHeader && !copiedLoop.contains(br.getFalseBlock())) {
                                replaceSuccessor(copiedBlock, br.getFalseBlock(), parallelExit);
                            }
                         }
                     }
                }
                 fixCopiedLoopExits(copiedLoop, loop, function, parallelExit);
            }


            // 更新前驱后继关系
            header.removePredecessor(preheader);
            header.addPredecessor(currentBranch);
            
            exitBlock.removePredecessor(header);
            exitBlock.addPredecessor(parallelExit);
            parallelExit.addPredecessor(header);
            
            // 修复每个复制循环的退出，让它们跳转到parallel_exit
            for (List<BasicBlock> copiedLoop : copiedLoops) {
                if (!copiedLoop.isEmpty()) {
                    BasicBlock copiedHeader = copiedLoop.get(0);
                    parallelExit.addPredecessor(copiedHeader);
                    
                    // 修复复制循环的退出分支
                    fixCopiedLoopExits(copiedLoop, loop, function, parallelExit);
                }
            }
            // Now predecessors are correct; populate endPhi incomings
            endPhi.addIncoming(new ConstantInt(0, IntegerType.I32), header);
            for (int i = 0; i < copiedLoops.size(); i++) {
                List<BasicBlock> copiedLoop = copiedLoops.get(i);
                if (!copiedLoop.isEmpty()) {
                    endPhi.addIncoming(new ConstantInt(i + 1, IntegerType.I32), copiedLoop.get(0));
                }
            }
            
            // 第六步：更新原始循环的归纳变量
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
        List<BasicBlock> originalBlocks = new ArrayList<>(loop.getBlocks());
        List<BasicBlock> copiedBlocks = new ArrayList<>();
        Map<BasicBlock, BasicBlock> blockMapping = new HashMap<>();
        Map<Value, Value> valueMapping = new HashMap<>();

        // Pass 1: Create block clones and populate block mapping.
        for (BasicBlock originalBlock : originalBlocks) {
            BasicBlock copiedBlock = new BasicBlock(originalBlock.getName() + "_thread" + threadId, function);
            copiedBlocks.add(copiedBlock);
            blockMapping.put(originalBlock, copiedBlock);
        }

        // Pass 2: Copy all instructions and populate value mapping.
        for (BasicBlock originalBlock : originalBlocks) {
            BasicBlock copiedBlock = blockMapping.get(originalBlock);
            for (Instruction originalInst : originalBlock.getInstructions()) {
                Instruction copiedInst = copyInstructionComplete(originalInst, valueMapping, threadId);
                copiedInst.setName(originalInst.getName() + "_thread" + threadId);
                copiedBlock.addInstruction(copiedInst);
                valueMapping.put(originalInst, copiedInst);
            }
        }

        // Pass 3: Remap all operands (Values and BasicBlocks).
        for (BasicBlock copiedBlock : copiedBlocks) {
            for (Instruction copiedInst : copiedBlock.getInstructions()) {
                for (int i = 0; i < copiedInst.getOperandCount(); i++) {
                    Value operand = copiedInst.getOperand(i);
                    
                    Value mappedValue = valueMapping.get(operand);
                    if (mappedValue != null) {
                        copiedInst.setOperand(i, mappedValue);
                        continue;
                    }
                    
                    Value mappedBlock = blockMapping.get(operand);
                    if (mappedBlock != null) {
                        copiedInst.setOperand(i, mappedBlock);
                    }
                }
            }
        }
        
        // Pass 3.5: Update references that require block context (e.g., Phi incoming values)
        for (BasicBlock copiedBlock : copiedBlocks) {
            updateInstructionReferences(copiedBlock, valueMapping, blockMapping);
        }
        
        // Pass 4: Re-establish CFG for copied blocks.
        for (BasicBlock originalBlock : originalBlocks) {
            BasicBlock copiedBlock = blockMapping.get(originalBlock);
            copiedBlock.getPredecessors().clear();
            copiedBlock.getSuccessors().clear();

            for (BasicBlock pred : originalBlock.getPredecessors()) {
                BasicBlock mappedPred = blockMapping.get(pred);
                if (mappedPred != null) {
                    copiedBlock.addPredecessor(mappedPred);
                }
            }
            for (BasicBlock succ : originalBlock.getSuccessors()) {
                BasicBlock mappedSucc = blockMapping.get(succ);
                if (mappedSucc != null) {
                    copiedBlock.addSuccessor(mappedSucc);
                }
            }
        }

        return copiedBlocks;
    }
    
    private Value findOriginalValue(Value copied, Map<Value, Value> valueMapping) {
        for (Map.Entry<Value, Value> entry : valueMapping.entrySet()) {
            if (entry.getValue() == copied) {
                return entry.getKey();
            }
        }
        return null;
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
            CompareInstruction copiedCmp = new CompareInstruction(
                originalCmp.getCompareType(),
                originalCmp.getPredicate(),
                originalCmp.getLeft(),  // 稍后更新
                originalCmp.getRight()  // 稍后更新
            );
            copiedCmp.setName(newName);
            return copiedCmp;
            
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
        
        // 清除现有的incoming值（如果有的话）
        Map<BasicBlock, Value> existingIncoming = new HashMap<>(phi.getIncomingValues());
        for (BasicBlock block : new ArrayList<>(existingIncoming.keySet())) {
            phi.removeIncoming(block);
        }
        
        // 复制原始Phi的incoming值，并更新引用
        Map<BasicBlock, Value> originalIncoming = originalPhi.getIncomingValues();
        for (Map.Entry<BasicBlock, Value> entry : originalIncoming.entrySet()) {
            BasicBlock originalBlock = entry.getKey();
            Value originalValue = entry.getValue();
            
            // 映射基本块 - 只映射循环内的块
            BasicBlock mappedBlock = blockMapping.get(originalBlock);
            if (mappedBlock == null) {
                // 如果没有映射（比如来自循环外的preheader），需要特殊处理
                // 这种情况下我们需要找到正确的入口块
                mappedBlock = findCorrectEntryBlock(originalBlock, blockMapping);
                if (mappedBlock == null) {
                    if (debug) {
                        System.out.println("[LoopParallelizer] 警告：无法为Phi指令找到块映射: " + originalBlock.getName());
                    }
                    continue;
                }
            }
            
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
     * 为复制的Phi指令找到正确的入口块
     */
    private BasicBlock findCorrectEntryBlock(BasicBlock originalEntryBlock, Map<BasicBlock, BasicBlock> blockMapping) {
        // 对于来自循环外的块（如preheader），我们需要找到对应的线程分支块
        // 这通常是thread_branch_N块
        for (BasicBlock mappedBlock : blockMapping.values()) {
            // 检查是否是线程分支块
            if (mappedBlock.getName().startsWith("thread_branch_")) {
                return mappedBlock;
            }
        }
        
        // 如果找不到，返回原始块（可能导致错误，但至少程序能继续）
        return originalEntryBlock;
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
     * 修复复制循环的退出，确保它们跳转到parallel_exit
     */
    private void fixCopiedLoopExits(List<BasicBlock> copiedBlocks, Loop originalLoop, Function function, BasicBlock parallelExit) {
        // 获取原始循环的退出块
        BasicBlock originalExitBlock = findLoopExitBlock(originalLoop);
        if (originalExitBlock == null) {
            return;
        }
        
        // 遍历所有复制的块，修复它们的退出分支
        for (BasicBlock copiedBlock : copiedBlocks) {
            Instruction terminator = copiedBlock.getTerminator();
            if (terminator instanceof BranchInstruction) {
                BranchInstruction branch = (BranchInstruction) terminator;
                
                if (branch.isUnconditional()) {
                    // 无条件跳转
                    if (branch.getTrueBlock() == originalExitBlock) {
                        // 替换为跳转到parallel_exit
                        copiedBlock.removeInstruction(branch);
                        BranchInstruction newBranch = new BranchInstruction(parallelExit);
                        copiedBlock.addInstruction(newBranch);
                        
                        if (debug) {
                            System.out.println("[LoopParallelizer] 修复复制块 " + copiedBlock.getName() + 
                                             " 的无条件跳转：" + originalExitBlock.getName() + " -> " + parallelExit.getName());
                        }
                    }
                } else {
                    // 条件跳转
                    boolean changed = false;
                    BasicBlock trueTarget = branch.getTrueBlock();
                    BasicBlock falseTarget = branch.getFalseBlock();
                    
                    if (trueTarget == originalExitBlock) {
                        trueTarget = parallelExit;
                        changed = true;
                    }
                    if (falseTarget == originalExitBlock) {
                        falseTarget = parallelExit;
                        changed = true;
                    }
                    
                    if (changed) {
                        copiedBlock.removeInstruction(branch);
                        BranchInstruction newBranch = new BranchInstruction(
                            branch.getCondition(), trueTarget, falseTarget);
                        copiedBlock.addInstruction(newBranch);
                        
                        if (debug) {
                            System.out.println("[LoopParallelizer] 修复复制块 " + copiedBlock.getName() + 
                                             " 的条件跳转");
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 修复复制循环中PHI指令的初始值
     */
    private void fixCopiedLoopPhiInitialValues(List<BasicBlock> copiedBlocks, int threadId) {
        for (int i = 0; i < copiedBlocks.size(); i++) {
            BasicBlock copiedBlock = copiedBlocks.get(i);
            
            // 检查是否是循环头块（通常是第一个）
            if (i == 0) {
                for (Instruction inst : copiedBlock.getInstructions()) {
                    if (inst instanceof PhiInstruction) {
                        PhiInstruction phi = (PhiInstruction) inst;
                        
                                                 // 查找对应的线程初始值
                         // 使用传入的threadId
                         if (threadId > 0) {
                             // 创建线程的起始索引值（threadId作为起始值）
                             Value threadStartIndex = new ConstantInt(threadId, IntegerType.I32);
                            
                            // 找到来自循环外的入口块
                            BasicBlock entryBlock = findThreadBranchBlock(copiedBlock.getParentFunction(), threadId);
                            if (entryBlock != null) {
                                // 添加初始值
                                phi.addIncoming(threadStartIndex, entryBlock);
                                
                                if (debug) {
                                    System.out.println("[LoopParallelizer] 为PHI " + phi.getName() + 
                                                     " 添加初始值: " + threadStartIndex.getName() + 
                                                     " from " + entryBlock.getName());
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 从块名中提取线程ID
     */
    private int extractThreadIdFromBlockName(String blockName) {
        if (blockName.contains("_thread")) {
            try {
                String threadPart = blockName.substring(blockName.lastIndexOf("_thread") + 7);
                return Integer.parseInt(threadPart);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return 0;
    }
    
    /**
     * 找到对应线程的分支块
     */
    private BasicBlock findThreadBranchBlock(Function function, int threadId) {
        String targetName = "thread_branch_" + threadId;
        for (BasicBlock block : function.getBasicBlocks()) {
            if (block.getName().equals(targetName)) {
                return block;
            }
        }
        return null;
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
     * 修复复制循环中的归纳变量更新指令
     */
    private void fixInductionVariableUpdate(List<BasicBlock> copiedLoop, Loop originalLoop, Value newStep) {
        Instruction originalUpdateInst = originalLoop.getUpdateInstruction();
        if (originalUpdateInst == null) return;

        for (BasicBlock copiedBlock : copiedLoop) {
            for (Instruction inst : copiedBlock.getInstructions()) {
                // This is a heuristic: find an instruction that looks like the original update instruction
                if (inst instanceof BinaryInstruction && originalUpdateInst instanceof BinaryInstruction) {
                    BinaryInstruction binInst = (BinaryInstruction) inst;
                    BinaryInstruction origBinInst = (BinaryInstruction) originalUpdateInst;
                    if (binInst.getOpCode() == origBinInst.getOpCode() && binInst.getName().startsWith(origBinInst.getName())) {
                        binInst.setOperand(1, newStep);
                         if (debug) {
                            System.out.println("[LoopParallelizer] 修复复制循环中的步长更新：" +
                                             copiedBlock.getName() + " 中的 " + binInst.getName());
                        }
                        return;
                    }
                }
            }
        }
    }
    
    /**
     * 确保复制循环中存在归纳变量更新指令
     */
    private void ensureInductionVariableUpdate(List<BasicBlock> copiedLoop, Loop originalLoop, Value newStep) {
        if (copiedLoop.size() < 2) {
            return; // 需要至少有头块和体块
        }
        
        BasicBlock copiedHeader = copiedLoop.get(0);
        BasicBlock copiedBody = null;
        
        // 找到循环体块（通常是除头块外的其他块）
        for (BasicBlock block : copiedLoop) {
            if (block != copiedHeader) {
                copiedBody = block;
                break;
            }
        }
        
        if (copiedBody == null) {
            return;
        }
        
        // 检查复制的循环体中是否有归纳变量更新指令
        boolean hasUpdateInstruction = false;
        PhiInstruction inductionPhi = null;
        
        // 找到头块中的phi指令（归纳变量）
        for (Instruction inst : copiedHeader.getInstructions()) {
            if (inst instanceof PhiInstruction) {
                inductionPhi = (PhiInstruction) inst;
                break;
            }
        }
        
        if (inductionPhi == null) {
            return;
        }
        
        // 检查循环体中是否有更新该phi的指令
        for (Instruction inst : copiedBody.getInstructions()) {
            if (inst instanceof BinaryInstruction) {
                BinaryInstruction binInst = (BinaryInstruction) inst;
                if (binInst.getLeft() == inductionPhi || binInst.getRight() == inductionPhi) {
                    hasUpdateInstruction = true;
                    break;
                }
            }
        }
        
        // 如果没有更新指令，创建一个
        if (!hasUpdateInstruction) {
            BinaryInstruction updateInst = new BinaryInstruction(
                OpCode.ADD, inductionPhi, newStep, IntegerType.I32);
            updateInst.setName("add_result_" + tmpCounter++ + "_thread" + extractThreadIdFromBlockName(copiedHeader.getName()));
            
            // 插入到循环体的合适位置（在分支指令之前）
            Instruction lastInst = copiedBody.getLastInstruction();
            if (lastInst instanceof BranchInstruction) {
                copiedBody.addInstructionBefore(updateInst, lastInst);
            } else {
                copiedBody.addInstruction(updateInst);
            }
            
            // 更新phi指令，让它使用这个更新后的值
            inductionPhi.removeIncoming(copiedBody);
            inductionPhi.addIncoming(updateInst, copiedBody);
            
            if (debug) {
                System.out.println("[LoopParallelizer] 为复制循环添加归纳变量更新指令: " + updateInst.getName());
            }
        }
    }
    
    // 临时计数器
    private static int tmpCounter = 100;
    
    /**
     * 检查是否是对应的步长值
     */
    private boolean isCorrespondingStepValue(Value candidateStep, Value originalStep) {
        // 简单的名称匹配（可能需要更复杂的逻辑）
        if (candidateStep == originalStep) {
            return true;
        }
        
        // 如果都是常量，比较值
        if (candidateStep instanceof ConstantInt && originalStep instanceof ConstantInt) {
            return ((ConstantInt) candidateStep).getValue() == ((ConstantInt) originalStep).getValue();
        }
        
        // 检查名称模式（例如 step_value 对应 step_value_thread1）
        String candidateName = candidateStep.getName();
        String originalName = originalStep.getName();
        
        return candidateName.contains(originalName) || originalName.contains(candidateName);
    }
    
    /**
     * 修改复制循环的起始索引
     */
    private void modifyLoopStartIndex(BasicBlock copiedHeader, Loop originalLoop, 
                                    Value newStartIndex, BasicBlock newPreheader) {
        PhiInstruction ivPhi = null;
        for (Instruction inst : copiedHeader.getInstructions()) {
            if (inst instanceof PhiInstruction) {
                if (inst.getName().startsWith(originalLoop.getInductionVariable().getName())) {
                    ivPhi = (PhiInstruction) inst;
                    break;
                }
            }
        }

        if (ivPhi == null) {
            if (debug) System.out.println("[LoopParallelizer] ERROR: Could not find IV PHI in " + copiedHeader.getName());
            return;
        }

        // Ensure CFG predecessor relationship so Phi can accept incoming from newPreheader
        if (!copiedHeader.getPredecessors().contains(newPreheader)) {
            copiedHeader.addPredecessor(newPreheader);
            newPreheader.addSuccessor(copiedHeader);
        }

        // Update or add the incoming for the new preheader using PhiInstruction API
        ivPhi.addOrUpdateIncoming(newStartIndex, newPreheader);
        if (debug) {
            System.out.println("[LoopParallelizer] Patched PHI " + ivPhi.getName() + " with start value " + newStartIndex.getName() + " from " + newPreheader.getName());
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
        if (block == null || block.getInstructions().isEmpty()) {
            return;
        }
        Instruction terminator = block.getLastInstruction();
        if (terminator instanceof BranchInstruction) {
            BranchInstruction br = (BranchInstruction) terminator;
            if (br.isUnconditional() && br.getTrueBlock() == oldSucc) {
                // 创建新的无条件跳转指令
                br.setOperand(0, newSucc);
            } else if (!br.isUnconditional()) {
                // 处理条件跳转
                if (br.getTrueBlock() == oldSucc) {
                    br.setOperand(1, newSucc);
                }
                if (br.getFalseBlock() == oldSucc) {
                    br.setOperand(2, newSucc);
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
        
        // 检查新块是否已经在函数中，避免重复插入
        if (blocks.contains(newBlock)) {
            return;
        }
        
        int index = blocks.indexOf(after);
        if (index >= 0 && index + 1 <= blocks.size()) {
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
        
        // 检查新块是否已经在函数中，避免重复插入
        if (blocks.contains(newBlock)) {
            return;
        }
        
        int index = blocks.indexOf(before);
        if (index >= 0) {
            blocks.add(index, newBlock);
        } else {
            blocks.add(0, newBlock);
        }
    }
} 
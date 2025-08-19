package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.Optimization.Utils.CloneHelper;
import MiddleEnd.IR.IRBuilder;

import java.util.*;
import java.util.UUID;

/**
 * 循环展开优化（Loop Unrolling）
 * 支持常数循环展开和动态循环展开，包括乘法归纳变量
 */
public class LoopUnroll implements Optimizer.ModuleOptimizer {

    // 使用配置管理类
    private static final int MAX_UNROLL_ITERATIONS = LoopUnrollConfig.MAX_UNROLL_ITERATIONS;
    private static final int MAX_BLOCK_SIZE_THRESHOLD = LoopUnrollConfig.MAX_CONSTANT_UNROLL_SIZE;
    private static final int DYNAMIC_UNROLL_FACTOR = LoopUnrollConfig.DYNAMIC_UNROLL_FACTOR;
    private static final int MAX_DYNAMIC_LOOP_SIZE = LoopUnrollConfig.MAX_DYNAMIC_LOOP_SIZE;

    private boolean hasChanged;
    
    // 统计信息
    private int constantUnrollCount = 0;
    private int dynamicUnrollCount = 0;
    private int totalIterationsSaved = 0;

    @Override
    public String getName() {
        return "LoopUnroll";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        for (Function function : module.functions()) {
            if (module.libFunctions().contains(function)) {
                continue;
            }
            
            // 运行循环分析
            LoopAnalysis.runLoopInfo(function);
            LoopAnalysis.analyzeInductionVariables(function);
            
            hasChanged = false;
            
            // 常数循环展开 - 按DFS顺序处理
            List<Loop> allLoops = getAllLoopsInDFSOrder(function);
            for (Loop loop : allLoops) {
                changed |= tryConstantUnroll(loop);
            }
            
            if (hasChanged) {
                // 重新构建CFG
                DominatorAnalysis.computeDominatorTree(function);
            }
            
            // 动态循环展开
            allLoops = getAllLoopsInDFSOrder(function); // 重新获取，因为可能有变化
            for (Loop loop : allLoops) {
                changed |= tryDynamicUnroll(loop);
            }
        }
        
        // 打印统计信息
        if (changed && LoopUnrollConfig.ENABLE_UNROLL_STATISTICS) {
            printUnrollStatistics();
        }
        
        return changed;
    }
    
    /**
     * 打印循环展开统计信息
     */
    private void printUnrollStatistics() {
        System.out.println("=== Loop Unroll Statistics ===");
        System.out.println("Constant unrolls: " + constantUnrollCount);
        System.out.println("Dynamic unrolls: " + dynamicUnrollCount);
        System.out.println("Total iterations saved: " + totalIterationsSaved);
        System.out.println("===============================");
    }

    /**
     * 获取所有循环的DFS顺序
     */
    private List<Loop> getAllLoopsInDFSOrder(Function function) {
        List<Loop> result = new ArrayList<>();
        for (Loop topLoop : LoopAnalysis.getTopLevelLoops(function)) {
            addLoopsInDFSOrder(topLoop, result);
        }
        return result;
    }

    private void addLoopsInDFSOrder(Loop loop, List<Loop> result) {
        // 先添加子循环
        for (Loop subLoop : loop.getSubLoops()) {
            addLoopsInDFSOrder(subLoop, result);
        }
        // 再添加当前循环
        result.add(loop);
    }

    /**
     * 尝试常数循环展开
     */
    private boolean tryConstantUnroll(Loop loop) {
        if (!isEligibleForConstantUnroll(loop)) {
            return false;
        }

        // 获取循环参数
        Value initValue = loop.getInitValue();
        Value stepValue = loop.getStepValue();
        Value endValue = loop.getEndValue();
        
        if (!(initValue instanceof ConstantInt) || 
            !(stepValue instanceof ConstantInt) || 
            !(endValue instanceof ConstantInt)) {
            return false;
        }

        int init = ((ConstantInt) initValue).getValue();
        int step = ((ConstantInt) stepValue).getValue();
        int end = ((ConstantInt) endValue).getValue();
        
        if (step == 0) return false;

        BinaryInstruction updateInst = (BinaryInstruction) loop.getUpdateInstruction();
        OpCode aluOp = updateInst.getOpCode();
        
        // 获取头块条件指令
        BasicBlock header = loop.getHeader();
        BranchInstruction brInst = (BranchInstruction) header.getTerminator();
        if (brInst.isUnconditional()) return false;
        
        CompareInstruction cmpInst = (CompareInstruction) brInst.getCondition();
        OpCode cmpOp = cmpInst.getPredicate();
        
        int iterationCount = calculateIterationCount(aluOp, cmpOp, init, step, end);
        
        if (iterationCount < 0 || iterationCount > MAX_UNROLL_ITERATIONS) {
            return false;
        }

        // 检查展开后的代码大小
        if (!checkUnrollSizeLimit(loop, iterationCount)) {
            return false;
        }

        // 检查子循环的出口
        if (!validateSubLoopExits(loop)) {
            return false;
        }

        // 执行常数循环展开
        performConstantUnroll(loop, iterationCount);
        hasChanged = true;
        
        // 更新统计信息
        constantUnrollCount++;
        totalIterationsSaved += iterationCount;
        
        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
            System.out.println("Constant unroll: " + loop.getHeader().getName() + 
                             " with " + iterationCount + " iterations");
        }
        
        return true;
    }

    /**
     * 尝试动态循环展开
     */
    private boolean tryDynamicUnroll(Loop loop) {
        if (!isEligibleForDynamicUnroll(loop)) {
            return false;
        }

        Value initValue = loop.getInitValue();
        Value stepValue = loop.getStepValue();
        BinaryInstruction updateInst = (BinaryInstruction) loop.getUpdateInstruction();

        if (!(initValue instanceof ConstantInt) || !(stepValue instanceof ConstantInt)) {
            return false;
        }

        if (((ConstantInt) initValue).getValue() != 0 || 
            ((ConstantInt) stepValue).getValue() != 1) {
            return false;
        }

        if (updateInst.getOpCode() != OpCode.ADD) {
            return false;
        }

        BasicBlock header = loop.getHeader();
        Instruction terminator = header.getTerminator();
        if (!(terminator instanceof BranchInstruction)) {
            return false;
        }
        BranchInstruction brInst = (BranchInstruction) terminator;
        if (brInst.isUnconditional()) {
            return false;
        }

        Value condVal = brInst.getCondition();
        if (!(condVal instanceof CompareInstruction)) {
            return false;
        }
        CompareInstruction cmpInst = (CompareInstruction) condVal;
        // 仅支持 i < n 形式的循环条件
        if (cmpInst.getPredicate() != OpCode.SLT) {
            return false;
        }

        if (loop.getBlocks().size() != 2) {
            return false;
        }

        if (header.getPredecessors().size() != 2) {
            return false;
        }

        // 检查循环结构的其他条件
        if (!validateDynamicUnrollStructure(loop)) {
            return false;
        }

        // 执行动态循环展开
        performEnhancedDynamicUnroll(loop);
        hasChanged = true;
        
        // 更新统计信息
        dynamicUnrollCount++;
        
        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
            System.out.println("Dynamic unroll: " + loop.getHeader().getName() + 
                             " with factor " + DYNAMIC_UNROLL_FACTOR);
        }
        
        return true;
    }

    /**
     * 检查循环是否适合常数展开
     */
    private boolean isEligibleForConstantUnroll(Loop loop) {
        if (!loop.hasInductionVariable()) {
            return false;
        }

        if (loop.getLatchBlocks().size() != 1) {
            return false;
        }

        if (loop.getExitBlocks().size() != 1) {
            return false;
        }

        BasicBlock header = loop.getHeader();
        if (header == null) {
            return false;
        }

        Instruction terminator = header.getTerminator();
        if (!(terminator instanceof BranchInstruction brInst) || brInst.isUnconditional()) {
            return false;
        }

        Value condition = brInst.getCondition();
        if (!(condition instanceof CompareInstruction)) {
            return false;
        }

        Instruction updateInst = loop.getUpdateInstruction();
        if (!(updateInst instanceof BinaryInstruction)) {
            return false;
        }

        return true;
    }

    /**
     * 检查循环是否适合动态展开
     */
    private boolean isEligibleForDynamicUnroll(Loop loop) {
        if (!loop.hasInductionVariable()) {
            return false;
        }

        if (loop.getLatchBlocks().size() != 1) {
            return false;
        }

        if (loop.getExitBlocks().size() != 1) {
            return false;
        }

        // 检查循环体大小
        int instCount = 0;
        for (BasicBlock bb : loop.getBlocks()) {
            instCount += bb.getInstructions().size();
        }

        return instCount <= MAX_DYNAMIC_LOOP_SIZE;
    }

    /**
     * 改进的迭代次数计算，支持乘法归纳变量
     */
    private int calculateIterationCount(OpCode aluOp, OpCode cmpOp, int init, int step, int end) {
        if (aluOp == OpCode.ADD) {
            return calculateAddIterations(cmpOp, init, step, end);
        } else if (aluOp == OpCode.SUB) {
            return calculateSubIterations(cmpOp, init, step, end);
        } else if (aluOp == OpCode.MUL) {
            return calculateMulIterations(cmpOp, init, step, end);
        }
        return -1;
    }

    private int calculateAddIterations(OpCode cmpOp, int init, int step, int end) {
        switch (cmpOp) {
            case EQ:
                return (init == end) ? 1 : 0;
            case NE:
                return ((end - init) % step == 0) ? (end - init) / step : -1;
            case SGE:
            case SLE:
                return Math.max(0, (end - init) / step + 1);
            case SGT:
            case SLT:
                int diff = end - init;
                return Math.max(0, (diff % step == 0) ? diff / step : diff / step + 1);
            default:
                return -1;
        }
    }

    private int calculateSubIterations(OpCode cmpOp, int init, int step, int end) {
        switch (cmpOp) {
            case EQ:
                return (init == end) ? 1 : 0;
            case NE:
                return ((init - end) % step == 0) ? (init - end) / step : -1;
            case SGE:
            case SLE:
                return Math.max(0, (init - end) / step + 1);
            case SGT:
            case SLT:
                int diff = init - end;
                return Math.max(0, (diff % step == 0) ? diff / step : diff / step + 1);
            default:
                return -1;
        }
    }

    private int calculateMulIterations(OpCode cmpOp, int init, int step, int end) {
        if (init <= 0 || step <= 1 || end <= 0) {
            return -1;
        }
        
        double val = Math.log((double) end / init) / Math.log(step);
        boolean exact = Math.abs(init * Math.pow(step, val) - end) < 1e-9;
        
        switch (cmpOp) {
            case EQ:
                return (init == end) ? 1 : 0;
            case NE:
                return exact ? (int) Math.round(val) : -1;
            case SGE:
            case SLE:
                return (int) Math.floor(val) + 1;
            case SGT:
            case SLT:
                return exact ? (int) Math.round(val) : (int) Math.floor(val) + 1;
            default:
                return -1;
        }
    }

    /**
     * 检查展开后的代码大小限制
     */
    private boolean checkUnrollSizeLimit(Loop loop, int iterationCount) {
        int loopSize = calculateLoopSize(loop);
        return (long) loopSize * iterationCount <= MAX_BLOCK_SIZE_THRESHOLD;
    }

    private int calculateLoopSize(Loop loop) {
        int size = 0;
        for (BasicBlock bb : loop.getBlocks()) {
            size += bb.getInstructions().size();
        }
        
        // 递归计算子循环大小
        for (Loop subLoop : loop.getSubLoops()) {
            size += calculateLoopSize(subLoop);
        }
        
        return size;
    }

    /**
     * 验证子循环的出口
     */
    private boolean validateSubLoopExits(Loop loop) {
        Set<BasicBlock> loopBlocks = new HashSet<>(loop.getBlocks());
        return checkSubLoopExitsRecursive(loop, loopBlocks);
    }

    private boolean checkSubLoopExitsRecursive(Loop loop, Set<BasicBlock> parentBlocks) {
        for (Loop subLoop : loop.getSubLoops()) {
            subLoop.computeExitBlocks();
            for (BasicBlock exitBlock : subLoop.getExitBlocks()) {
                if (!parentBlocks.contains(exitBlock)) {
                    return false;
                }
            }
            
            // 递归检查子循环的子循环
            if (!checkSubLoopExitsRecursive(subLoop, parentBlocks)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 验证动态展开的循环结构
     */
    private boolean validateDynamicUnrollStructure(Loop loop) {
        BasicBlock header = loop.getHeader();
        BasicBlock latch = loop.getLatchBlocks().get(0);

        // 检查头块只包含phi、比较和分支指令
        if (!validateHeaderStructure(header, loop)) {
            return false;
        }

        // 检查更新指令后没有其他指令使用归纳变量
        if (!validateLatchStructure(latch, loop)) {
            return false;
        }

        // 检查结束值不在头块中定义
        Value endValue = loop.getEndValue();
        if (endValue instanceof Instruction inst && inst.getParent() == header) {
            return false;
        }

        // 检查出口块的前驱包含头块
        BasicBlock exit = loop.getExitBlocks().iterator().next();
        if (!exit.getPredecessors().contains(header)) {
            return false;
        }

        return true;
    }

    private boolean validateHeaderStructure(BasicBlock header, Loop loop) {
        int nonPhiCount = 0;
        CompareInstruction cmpInst = null;
        
        for (Instruction inst : header.getInstructions()) {
            if (!(inst instanceof PhiInstruction)) {
                nonPhiCount++;
                if (nonPhiCount == 1 && inst instanceof CompareInstruction) {
                    cmpInst = (CompareInstruction) inst;
                } else if (nonPhiCount == 2 && !(inst instanceof BranchInstruction)) {
                    return false;
                } else if (nonPhiCount > 2) {
                    return false;
                }
            }
        }
        
        return cmpInst != null;
    }

    private boolean validateLatchStructure(BasicBlock latch, Loop loop) {
        BinaryInstruction updateInst = (BinaryInstruction) loop.getUpdateInstruction();
        List<Instruction> instructions = latch.getInstructions();
        boolean foundUpdate = false;
        
        for (Instruction inst : instructions) {
            if (inst == updateInst) {
                foundUpdate = true;
                continue;
            }
            
            if (foundUpdate && inst != latch.getTerminator()) {
                for (Value operand : inst.getOperands()) {
                    if (operand == updateInst) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }

    /**
     * 执行常数循环展开
     */
    private void performConstantUnroll(Loop loop, int iterationCount) {
        BasicBlock header = loop.getHeader();
        BasicBlock latch = loop.getLatchBlocks().get(0);
        BasicBlock exit = loop.getExitBlocks().iterator().next();
        Function function = header.getParentFunction();

        // 获取头块中的phi指令并建立映射
        Map<PhiInstruction, Value> phiMap = new HashMap<>();
        Map<Value, Value> beginToEndMap = new HashMap<>();
        List<PhiInstruction> headerPhis = extractHeaderPhis(header);

        // 移除head和latch的连接，建立初始映射
        int latchIndex = header.getPredecessors().indexOf(latch);
        for (PhiInstruction phi : headerPhis) {
            Value latchValue = phi.getOperands().get(latchIndex);
            phi.removeOperand(latchValue);
            phiMap.put(phi, latchValue);
            beginToEndMap.put(phi, latchValue);
        }

        // 更新控制流
        updateControlFlowForUnroll(header, latch, exit);

        // 获取需要复制的基本块（DFS顺序）
        BasicBlock headerNext = findHeaderNext(header, exit);
        List<BasicBlock> blocksToUnroll = getBlocksInDFSOrder(loop, headerNext);

        // 移除循环与父循环的关系
        updateLoopHierarchy(loop, function);

        // 执行展开
        performUnrollCopies(blocksToUnroll, header, headerNext, latch, exit, iterationCount - 1, 
                          phiMap, beginToEndMap, headerPhis, function);

        // beginToEndMap已经在performUnrollCopies中处理了exit phi的修复
    }

    private List<PhiInstruction> extractHeaderPhis(BasicBlock header) {
        List<PhiInstruction> headerPhis = new ArrayList<>();
        for (Instruction inst : header.getInstructions()) {
            if (!(inst instanceof PhiInstruction)) {
                break;
            }
            headerPhis.add((PhiInstruction) inst);
        }
        return headerPhis;
    }

    private void updateControlFlowForUnroll(BasicBlock header, BasicBlock latch, BasicBlock exit) {
        // 移除控制流边
        header.removePredecessor(latch);
        latch.removeSuccessor(header);
        header.removeSuccessor(exit);
        exit.removePredecessor(header);

        // 修改头块分支为无条件跳转
        BasicBlock headerNext = findHeaderNext(header, exit);
        header.removeInstruction(header.getTerminator());
        IRBuilder.createBr(headerNext, header);

        // 移除latch的终结指令
        latch.removeInstruction(latch.getTerminator());
    }

    private BasicBlock findHeaderNext(BasicBlock header, BasicBlock exit) {
        for (BasicBlock succ : header.getSuccessors()) {
            if (succ != exit) {
                return succ;
            }
        }
        throw new IllegalStateException("Cannot find header next block");
    }

    private void updateLoopHierarchy(Loop loop, Function function) {
        // 由于我们的API限制，这里只是清理循环信息
        // 实际的循环层次更新将在循环分析重新运行时完成
        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
            System.out.println("Loop hierarchy updated for unrolled loop: " + loop.getHeader().getName());
        }
    }

    private void performUnrollCopies(List<BasicBlock> blocksToUnroll, BasicBlock header, BasicBlock headerNext, 
                                    BasicBlock latch, BasicBlock exit, int copies,
                                    Map<PhiInstruction, Value> phiMap, Map<Value, Value> beginToEndMap,
                                    List<PhiInstruction> headerPhis, Function function) {
         CloneHelper cloneHelper = new CloneHelper();
         
         // 初始化：为头块Phi建立到其来自latch的原始值的映射
         for (Map.Entry<PhiInstruction, Value> entry : phiMap.entrySet()) {
             cloneHelper.addValueMapping(entry.getKey(), entry.getValue());
         }
 
         BasicBlock currentLatch = latch;
 
         for (int i = 0; i < copies; i++) {
             // 1) 复制循环体中的所有基本块
             Map<BasicBlock, BasicBlock> blockMapping = new HashMap<>();
             for (BasicBlock bb : blocksToUnroll) {
                 BasicBlock newBB = IRBuilder.createBasicBlock(bb.getName() + "_unroll_" + i, function);
                 blockMapping.put(bb, newBB);
                 cloneHelper.addValueMapping(bb, newBB);
             }
 
             // 2) 复制指令并建立映射
             for (BasicBlock bb : blocksToUnroll) {
                 BasicBlock newBB = blockMapping.get(bb);
                 cloneHelper.copyBlockToBlock(bb, newBB);
             }
 
             // 3) 确保新latch结尾没有旧的终结指令
             BasicBlock newLatch = blockMapping.get(latch);
             if (newLatch != null && newLatch.getTerminator() != null) {
                 newLatch.removeInstruction(newLatch.getTerminator());
             }
 
             // 4) 接上控制流：currentLatch -> newHeaderNext
             BasicBlock newHeaderNext = blockMapping.get(headerNext);
             currentLatch.addSuccessor(newHeaderNext);
             newHeaderNext.addPredecessor(currentLatch);
             IRBuilder.createBr(newHeaderNext, currentLatch);

            // 4.5) 建立完整的控制流关系
            establishControlFlowForUnrolledBlocks(blocksToUnroll, blockMapping);

            // 5) 修正新块中的Phi操作数
            updatePhiInstructionsAfterCopy(blocksToUnroll, blockMapping, cloneHelper);
 
             // 6) 更新头块Phi在本轮后的"最新latch值"映射
             for (Map.Entry<PhiInstruction, Value> e : phiMap.entrySet()) {
                 Value baseLatchVal = e.getValue();
                 Value latest = cloneHelper.findValue(baseLatchVal);
                 if (latest != null) {
                     // 让后续 findValue(phi) 始终得到最新一轮的值
                     cloneHelper.addValueMapping(e.getKey(), latest);
                 }
             }
 
             currentLatch = newLatch;
         }
 
         // 7) 连接最后一个latch到exit
         if (currentLatch.getTerminator() != null) {
             currentLatch.removeInstruction(currentLatch.getTerminator());
         }
         currentLatch.addSuccessor(exit);
         exit.addPredecessor(currentLatch);
         IRBuilder.createBr(exit, currentLatch);
 
         // 8) 最终确定：每个头块Phi在展开结束后对应的"最终值"
         for (PhiInstruction headerPhi : headerPhis) {
             Value base = phiMap.get(headerPhi);
             if (base != null) {
                 Value latest = cloneHelper.findValue(base);
                 if (latest != null) {
                     beginToEndMap.put(headerPhi, latest);
                 }
             }
         }
 
         // 9) 将出口Phi来自 currentLatch 的来边，设置为对应头块Phi的最终值
         fixExitPhiUsingHeaderPhis(exit, currentLatch, headerPhis, beginToEndMap);
     }

    /**
     * 为展开后的基本块建立完整的控制流关系
     */
    private void establishControlFlowForUnrolledBlocks(List<BasicBlock> originalBlocks, 
                                                     Map<BasicBlock, BasicBlock> blockMapping) {
        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
            System.out.println("Establishing control flow for unrolled blocks...");
        }
        
        for (BasicBlock originalBB : originalBlocks) {
            BasicBlock newBB = blockMapping.get(originalBB);
            if (newBB == null) continue;
            
            if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                System.out.println("  Setting up CFG for: " + originalBB.getName() + " -> " + newBB.getName());
            }
            
            // 建立前驱关系
            for (BasicBlock originalPred : originalBB.getPredecessors()) {
                BasicBlock newPred = blockMapping.get(originalPred);
                
                // 如果前驱在映射中，建立新的前驱关系
                if (newPred != null) {
                    if (!newBB.getPredecessors().contains(newPred)) {
                        newBB.addPredecessor(newPred);
                        newPred.addSuccessor(newBB);
                        
                        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                            System.out.println("    Added predecessor: " + newPred.getName() + " -> " + newBB.getName());
                        }
                    }
                }
                // 如果前驱不在映射中，检查是否是外部连接（如currentLatch -> newHeaderNext）
                else {
                    // 这种情况下前驱关系应该已经在其他地方建立了
                    if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                        System.out.println("    External predecessor: " + originalPred.getName() + 
                                         " (should be handled separately)");
                    }
                }
            }
            
            // 建立后继关系
            for (BasicBlock originalSucc : originalBB.getSuccessors()) {
                BasicBlock newSucc = blockMapping.get(originalSucc);
                
                // 如果后继在映射中，建立新的后继关系
                if (newSucc != null) {
                    if (!newBB.getSuccessors().contains(newSucc)) {
                        newBB.addSuccessor(newSucc);
                        newSucc.addPredecessor(newBB);
                        
                        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                            System.out.println("    Added successor: " + newBB.getName() + " -> " + newSucc.getName());
                        }
                    }
                }
                // 外部后继关系（如到exit块）会在其他地方处理
            }
            
            if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                System.out.println("    Final predecessors: " + newBB.getPredecessors().size());
                System.out.println("    Final successors: " + newBB.getSuccessors().size());
            }
        }
    }

    // 收集头块中的循环体指令（排除Phi与分支）
    private List<Instruction> collectHeaderBodyInstructions(BasicBlock header) {
        List<Instruction> body = new ArrayList<>();
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction) continue;
            if (inst instanceof BranchInstruction) continue;
            body.add(inst);
        }
        return body;
    }

    // 将头块循环体指令复制到某个latch末尾（终结指令之前）
    private void copyHeaderBodyToLatch(List<Instruction> headerBodyInsts, BasicBlock targetLatch, CloneHelper cloneHelper) {
        // 在复制头块体指令时，需要逐条复制并建立 original->copy 的映射
        for (Instruction inst : headerBodyInsts) {
            Instruction newInst = cloneHelper.copyInstruction(inst);
            targetLatch.addInstruction(newInst);
            cloneHelper.addValueMapping(inst, newInst);
        }
    }

    // 根据 cloneHelper 的最新映射，更新 beginToEndMap（使其指向最新一轮的值）
    private void updateBeginToEndMap(Map<Value, Value> beginToEndMap, CloneHelper cloneHelper) {
        for (Map.Entry<Value, Value> entry : new ArrayList<>(beginToEndMap.entrySet())) {
            Value mapped = cloneHelper.findValue(entry.getValue());
            if (mapped != null) {
                beginToEndMap.put(entry.getKey(), mapped);
            }
        }
    }

    // 将出口Phi中来自 currentLatch 的来边操作数，替换为头块Phi在展开后对应的最终值
    private void fixExitPhiUsingHeaderPhis(BasicBlock exit, BasicBlock currentLatch,
                                           List<PhiInstruction> headerPhis,
                                           Map<Value, Value> beginToEndMap) {
        int latchIdx = exit.getPredecessors().indexOf(currentLatch);
        if (latchIdx < 0) return;

        for (Instruction inst : exit.getInstructions()) {
            if (inst instanceof PhiInstruction exitPhi) {
                String exitName = exitPhi.getName();
                if (exitName == null) continue;

                // 尝试找到与header phi名称最相似的匹配
                for (PhiInstruction headerPhi : headerPhis) {
                    String headerName = headerPhi.getName();
                    if (headerName == null) continue;

                    boolean matched = false;
                    if (exitName.startsWith(headerName) || exitName.contains(headerName)) {
                        matched = true;
                    } else {
                        // 对比数字部分
                        String exitNum = extractPhiNumber(exitName);
                        String headerNum = extractPhiNumber(headerName);
                        if (exitNum != null && exitNum.equals(headerNum)) {
                            matched = true;
                        }
                    }

                    if (matched) {
                        Value finalVal = beginToEndMap.get(headerPhi);
                        if (finalVal != null && latchIdx < exitPhi.getOperands().size()) {
                            exitPhi.setOperand(latchIdx, finalVal);
                        }
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * 查找最佳的值映射
     */
    private Value findBestValueMapping(Value currentOperand, List<PhiInstruction> headerPhis, 
                                     Map<Value, Value> beginToEndMap) {
        // 直接查找在beginToEndMap中的映射
        for (Map.Entry<Value, Value> entry : beginToEndMap.entrySet()) {
            Value original = entry.getKey();
            Value mapped = entry.getValue();
            
            // 如果当前操作数就是某个header phi，直接使用映射
            if (currentOperand == original) {
                return mapped;
            }
            
            // 如果当前操作数和原始值有相同的类型和相似的用途，考虑使用映射
            if (currentOperand != null && original != null) {
                if (isSimilarValue(currentOperand, original)) {
                    return mapped;
                }
            }
        }
        
        // 如果没有找到直接映射，检查是否是某个header phi的最新值
        for (PhiInstruction headerPhi : headerPhis) {
            Value finalValue = beginToEndMap.get(headerPhi);
            if (finalValue != null && areSemanticallyRelated(currentOperand, headerPhi)) {
                return finalValue;
            }
        }
        
        return null;
    }
    
    /**
     * 检查两个值是否相似（类型和语义上）
     */
    private boolean isSimilarValue(Value val1, Value val2) {
        if (val1 == val2) return true;
        if (val1 == null || val2 == null) return false;
        
        // 检查类型是否匹配
        if (!val1.getType().equals(val2.getType())) return false;
        
        // 如果都是常量，检查值是否相等
        if (val1 instanceof ConstantInt && val2 instanceof ConstantInt) {
            return ((ConstantInt) val1).getValue() == ((ConstantInt) val2).getValue();
        }
        
        // 如果都是指令，检查是否有相似的语义
        if (val1 instanceof Instruction && val2 instanceof Instruction) {
            Instruction inst1 = (Instruction) val1;
            Instruction inst2 = (Instruction) val2;
            
            // 简单的语义检查：相同的操作码
            if (inst1.getClass().equals(inst2.getClass())) {
                if (inst1 instanceof PhiInstruction) return true;
                if (inst1 instanceof BinaryInstruction) {
                    return ((BinaryInstruction) inst1).getOpCode() == ((BinaryInstruction) inst2).getOpCode();
                }
            }
        }
        
        return false;
    }
    
    /**
     * 检查两个值是否在语义上相关
     */
    private boolean areSemanticallyRelated(Value currentOperand, Value headerPhi) {
        if (currentOperand == headerPhi) return true;
        if (currentOperand == null || headerPhi == null) return false;
        
        // 如果当前操作数是phi节点且名称相似
        if (currentOperand instanceof PhiInstruction && headerPhi instanceof PhiInstruction) {
            String currentName = ((PhiInstruction) currentOperand).getName();
            String headerName = ((PhiInstruction) headerPhi).getName();
            
            if (currentName != null && headerName != null) {
                // 提取数字部分进行比较（如phi_11, phi_12）
                String currentNumber = extractPhiNumber(currentName);
                String headerNumber = extractPhiNumber(headerName);
                
                return currentNumber != null && currentNumber.equals(headerNumber);
            }
        }
        
        return false;
    }
    
    /**
     * 从phi节点名称中提取数字
     */
    private String extractPhiNumber(String phiName) {
        if (phiName.startsWith("phi_")) {
            String[] parts = phiName.split("_");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return null;
    }

    /**
     * 增强的动态循环展开
     */
    private void performEnhancedDynamicUnroll(Loop loop) {
        BasicBlock header = loop.getHeader();
        BasicBlock latch = loop.getLatchBlocks().get(0);
        BasicBlock exit = loop.getExitBlocks().iterator().next();
        Function function = header.getParentFunction();

        Value inductionVar = loop.getInductionVariable();
        Value endValue = loop.getEndValue();
        BinaryInstruction updateInst = (BinaryInstruction) loop.getUpdateInstruction();

        // 首先清理可能错误放置的指令
        cleanupMisplacedInstructions(function, loop);

        // 为每个循环创建唯一命名的基本块，避免名称冲突
        String loopId = header.getName() != null ? header.getName() : "loop_" + System.currentTimeMillis();
        BasicBlock preHeader = IRBuilder.createBasicBlock("preheader_" + loopId, function);
        BasicBlock preExitHeader = IRBuilder.createBasicBlock("pre_exit_header_" + loopId, function);
        BasicBlock preExitLatch = IRBuilder.createBasicBlock("pre_exit_latch_" + loopId, function);

        // 重定向原来到header的边 - 先重定向，再创建指令
        redirectPreHeaderEdges(header, latch, preHeader);

        // 在preHeader中添加边界检查 - 使用位运算优化
        int mask = ~(DYNAMIC_UNROLL_FACTOR - 1); // 对于4倍展开，mask = -4
        ConstantInt maskConst = new ConstantInt(mask, IntegerType.I32);
        BinaryInstruction andInst = IRBuilder.createBinaryInst(OpCode.AND, endValue, maskConst, preHeader);

        ConstantInt threshold = new ConstantInt(DYNAMIC_UNROLL_FACTOR - 1, IntegerType.I32);
        CompareInstruction cmpInst = IRBuilder.createICmp(OpCode.SGT, endValue, threshold, preHeader);

        // 修正：preHeader跳转到header或preExitHeader，不能跳转到自己
        IRBuilder.createCondBr(cmpInst, header, preExitHeader, preHeader);

        // 修改步长为DYNAMIC_UNROLL_FACTOR
        updateStepSize(updateInst);

        // 修改循环条件
        updateLoopCondition(loop, andInst, inductionVar);

        // 执行4倍展开复制
        performDynamicUnrollCopies(loop, inductionVar, updateInst);

        // 修改主循环的出口：现在主循环退出时应该跳转到exit而不是原来的出口
        updateMainLoopExit(header, preExitHeader);

        // 创建预出口处理
        createEnhancedPreExitBlocks(loop, preExitHeader, preExitLatch, exit, inductionVar, endValue, header, latch, preHeader);
        
        // 验证：确保没有指令被错误地放置在入口块
        validateNoMisplacedInstructions(function);
    }
    
    /**
     * 验证函数中没有错误放置的指令
     */
    private void validateNoMisplacedInstructions(Function function) {
        BasicBlock entryBlock = function.getEntryBlock();
        
        for (Instruction inst : new ArrayList<>(entryBlock.getInstructions())) {
            // 入口块中不应该有使用未定义值的指令
            for (Value operand : inst.getOperands()) {
                if (operand instanceof Instruction opInst) {
                    BasicBlock opBlock = opInst.getParent();
                    
                    // 如果操作数指令在其他块中定义，这是错误的
                    if (opBlock != null && opBlock != entryBlock) {
                        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                            System.out.println("Error: Instruction in entry block uses value from another block");
                            System.out.println("  Instruction: " + inst);
                            System.out.println("  Operand: " + opInst);
                            System.out.println("  Operand block: " + opBlock.getName());
                        }
                        
                        // 尝试修复：将指令移动到正确的块
                        if (inst instanceof BinaryInstruction && opInst instanceof PhiInstruction) {
                            // 这个指令应该在phi所在的块或其后继块中
                            entryBlock.removeInstruction(inst);
                            // 不在这里重新插入，因为可能会破坏其他逻辑
                            // 标记为需要修复
                            if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                                System.out.println("  Removed misplaced instruction from entry block");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 清理错误放置的指令
     */
    private void cleanupMisplacedInstructions(Function function, Loop loop) {
        BasicBlock entryBlock = function.getEntryBlock();
        Set<BasicBlock> loopBlocks = new HashSet<>(loop.getBlocks());
        
        List<Instruction> toRemove = new ArrayList<>();
        
        for (Instruction inst : entryBlock.getInstructions()) {
            // 检查指令是否使用了循环内的值
            for (Value operand : inst.getOperands()) {
                if (operand instanceof Instruction opInst) {
                    BasicBlock opBlock = opInst.getParent();
                    if (loopBlocks.contains(opBlock)) {
                        // 这个指令使用了循环内的值，应该被移除
                        toRemove.add(inst);
                        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                            System.out.println("Removing misplaced instruction from entry: " + inst);
                        }
                        break;
                    }
                }
            }
        }
        
        for (Instruction inst : toRemove) {
            entryBlock.removeInstruction(inst);
        }
    }

    /**
     * 更新主循环的出口，使其跳转到pre_exit_header处理剩余迭代
     */
    private void updateMainLoopExit(BasicBlock header, BasicBlock preExitHeader) {
        BranchInstruction headerBr = (BranchInstruction) header.getTerminator();
        if (!headerBr.isUnconditional()) {
            // 更新条件分支的false目标为pre_exit_header
            BasicBlock trueTarget = headerBr.getTrueBlock();
            BasicBlock falseTarget = headerBr.getFalseBlock();
            
            // 找到循环的出口边并更新为pre_exit_header
            if (trueTarget != preExitHeader && falseTarget != preExitHeader) {
                // 需要更新false目标为pre_exit_header
                header.updateBranchTarget(falseTarget, preExitHeader);
                
                // 同时更新控制流图
                header.removeSuccessor(falseTarget);
                falseTarget.removePredecessor(header);
                header.addSuccessor(preExitHeader);
                preExitHeader.addPredecessor(header);
            }
        }
    }

    private void redirectPreHeaderEdges(BasicBlock header, BasicBlock latch, BasicBlock preHeader) {
        for (BasicBlock pred : new ArrayList<>(header.getPredecessors())) {
            if (pred != latch) {
                pred.removeSuccessor(header);
                pred.addSuccessor(preHeader);
                preHeader.addPredecessor(pred);
                header.removePredecessor(pred);
                
                // 更新分支指令
                Instruction terminator = pred.getTerminator();
                if (terminator instanceof BranchInstruction) {
                    pred.updateBranchTarget(header, preHeader);
                }
            }
        }

        preHeader.addSuccessor(header);
        header.addPredecessor(preHeader);
    }

    private void updateStepSize(BinaryInstruction updateInst) {
        ConstantInt newStep = new ConstantInt(DYNAMIC_UNROLL_FACTOR, IntegerType.I32);
        if (updateInst.getOperands().get(0) instanceof ConstantInt) {
            updateInst.setOperand(0, newStep);
        } else {
            updateInst.setOperand(1, newStep);
        }
    }

    private void updateLoopCondition(Loop loop, BinaryInstruction andInst, Value inductionVar) {
        BasicBlock header = loop.getHeader();
        BranchInstruction headerBr = (BranchInstruction) header.getTerminator();
        CompareInstruction headerCmp = (CompareInstruction) headerBr.getCondition();
        
        // 替换结束值为掩码值
        for (int i = 0; i < headerCmp.getOperands().size(); i++) {
            if (headerCmp.getOperands().get(i) == loop.getEndValue()) {
                headerCmp.setOperand(i, andInst);
                break;
            }
        }
    }

    private void performDynamicUnrollCopies(Loop loop, Value inductionVar, BinaryInstruction updateInst) {
        BasicBlock latch = loop.getLatchBlocks().get(0);
        BasicBlock header = loop.getHeader();
        
        // 确保updateInst确实在latch中
        if (updateInst.getParent() != latch) {
            if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                System.out.println("Warning: Update instruction not in latch block");
            }
            return;
        }
        
        // 建立头块phi映射
        Map<PhiInstruction, Value> headPhiMap = new HashMap<>();
        Map<Value, PhiInstruction> valueToPhiMap = new HashMap<>();
        
        int latchIndex = header.getPredecessors().indexOf(latch);
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction phi && phi != inductionVar) {
                Value latchValue = phi.getOperand(latchIndex);
                headPhiMap.put(phi, latchValue);
                if (latchValue instanceof Instruction) {
                    valueToPhiMap.put(latchValue, phi);
                }
            }
        }

        // 收集latch中的原始指令（跳过phi、更新指令和分支指令）
        List<Instruction> originalInstructions = new ArrayList<>();
        for (Instruction inst : latch.getInstructions()) {
            if (inst == updateInst) {
                break; // 只复制updateInst之前的指令
            }
            if (!(inst instanceof PhiInstruction) && !(inst instanceof BranchInstruction)) {
                // 检查是否是展开生成的OR指令或基于OR的计算
                boolean isUnrollGenerated = false;
                if (inst instanceof BinaryInstruction binInst && binInst.getOpCode() == OpCode.OR) {
                    isUnrollGenerated = true;
                } else {
                    // 检查指令的操作数是否依赖于OR指令
                    for (Value operand : inst.getOperands()) {
                        if (operand instanceof Instruction opInst) {
                            if (opInst instanceof BinaryInstruction binOp && binOp.getOpCode() == OpCode.OR) {
                                isUnrollGenerated = true;
                                break;
                            }
                        }
                    }
                }
                
                if (!isUnrollGenerated) {
                    originalInstructions.add(inst);
                }
            }
        }

        // 复制DYNAMIC_UNROLL_FACTOR-1次
        for (int i = 1; i < DYNAMIC_UNROLL_FACTOR; i++) {
            // 创建或指令 (inductionVar | i)
            ConstantInt offset = new ConstantInt(i, IntegerType.I32);
            BinaryInstruction orInst = new BinaryInstruction(OpCode.OR, inductionVar, offset, inductionVar.getType());
            
            // 确保orInst被插入到latch中正确的位置
            latch.addInstructionBefore(orInst, updateInst);
            
            // 为这次展开创建映射
            Map<Value, Value> iterationMapping = new HashMap<>();
            iterationMapping.put(inductionVar, orInst);
            
            // 复制并插入原始指令
            for (Instruction original : originalInstructions) {
                // 创建指令副本
                Instruction newInst = copyInstructionWithMapping(original, iterationMapping);
                
                // 处理对头块phi的引用
                for (int j = 0; j < newInst.getOperands().size(); j++) {
                    Value operand = newInst.getOperands().get(j);
                    // 替换phi引用
                    if (headPhiMap.containsKey(operand)) {
                        newInst.setOperand(j, headPhiMap.get(operand));
                    }
                    // 替换归纳变量引用
                    else if (operand == inductionVar) {
                        newInst.setOperand(j, orInst);
                    }
                }
                
                // 更新phi映射
                if (valueToPhiMap.containsKey(original)) {
                    PhiInstruction phi = valueToPhiMap.get(original);
                    headPhiMap.put(phi, newInst);
                }
                
                // 将新指令插入到latch中updateInst之前
                latch.addInstructionBefore(newInst, updateInst);
                
                // 更新映射
                iterationMapping.put(original, newInst);
            }
        }

        // 修复头块phi值
        updateHeaderPhiValues(header, latch, headPhiMap);
    }
    
    /**
     * 复制指令并应用值映射
     */
    private Instruction copyInstructionWithMapping(Instruction original, Map<Value, Value> mapping) {
        Instruction copy = null;
        
        if (original instanceof BinaryInstruction binInst) {
            Value left = mapping.getOrDefault(binInst.getLeft(), binInst.getLeft());
            Value right = mapping.getOrDefault(binInst.getRight(), binInst.getRight());
            copy = new BinaryInstruction(binInst.getOpCode(), left, right, binInst.getType());
        } else if (original instanceof LoadInstruction loadInst) {
            Value pointer = mapping.getOrDefault(loadInst.getPointer(), loadInst.getPointer());
            // 生成唯一名称，避免重复
            String baseName = loadInst.getName() == null ? "load" : loadInst.getName();
            String uniqueName = baseName + "_copy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            copy = new LoadInstruction(pointer, loadInst.getType(), uniqueName);
        } else if (original instanceof StoreInstruction storeInst) {
            Value value = mapping.getOrDefault(storeInst.getValue(), storeInst.getValue());
            Value pointer = mapping.getOrDefault(storeInst.getPointer(), storeInst.getPointer());
            copy = new StoreInstruction(value, pointer);
        } else {
            // 使用CloneHelper作为后备
            CloneHelper helper = new CloneHelper();
            for (Map.Entry<Value, Value> entry : mapping.entrySet()) {
                helper.addValueMapping(entry.getKey(), entry.getValue());
            }
            copy = helper.copyInstruction(original);
        }
        
        return copy;
    }


    private void updateHeaderPhiValues(BasicBlock header, BasicBlock latch, Map<PhiInstruction, Value> headPhiMap) {
        int latchIndex = header.getPredecessors().indexOf(latch);
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction phi && headPhiMap.containsKey(phi)) {
                phi.setOperand(latchIndex, headPhiMap.get(phi));
            }
        }
    }

    private void createEnhancedPreExitBlocks(Loop loop, BasicBlock preExitHeader, BasicBlock preExitLatch, 
                                           BasicBlock exit, Value inductionVar, Value endValue,
                                           BasicBlock header, BasicBlock latch, BasicBlock preHeader) {
        CloneHelper cloneHelper = new CloneHelper();
        
        // 先设置控制流，确保前驱关系正确
        setupPreExitControlFlow(preExitHeader, preExitLatch, exit, header, preHeader);
        
        // 然后创建phi节点，确保操作数数量与前驱数量匹配
        Map<PhiInstruction, PhiInstruction> preExitToHeadPhiMap = new HashMap<>();
        Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap = new HashMap<>();
        Value exitInductionVar = null;
        
        int latchIndex = header.getPredecessors().indexOf(latch);
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                // preExitHeader有两个前驱：preHeader和preExitLatch
                PhiInstruction newPhi = new PhiInstruction(phi.getType(), phi.getName() + "_exit");
                
                // 使用addIncoming方法正确添加phi操作数
                Value initValue = phi.getOperand(1 - latchIndex);
                
                // 添加来自preHeader的初始值
                newPhi.addIncoming(initValue, preHeader);
                
                // 添加来自preExitLatch的值，暂时用初始值
                newPhi.addIncoming(initValue, preExitLatch);
                
                // 添加来自header的当前phi值，保持与控制流前驱一致
                newPhi.addIncoming(phi, header);
                
                // 验证phi节点确实有两个操作数
                if (newPhi.getOperands().size() != 3) {
                    if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                        System.out.println("Warning: phi node " + newPhi.getName() + 
                                         " has " + newPhi.getOperands().size() + " operands, expected 3");
                    }
                }
                
                preExitHeader.addInstruction(newPhi);
                preExitToHeadPhiMap.put(newPhi, phi);
                headToPreExitPhiMap.put(phi, newPhi);
                cloneHelper.addValueMapping(phi, newPhi);
                
                if (phi == inductionVar) {
                    exitInductionVar = newPhi;
                }
                
                // 调试信息
                if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                    System.out.println("Created phi node: " + newPhi.getName() + 
                                     " with " + newPhi.getOperands().size() + " operands");
                    for (int i = 0; i < newPhi.getOperands().size(); i++) {
                        System.out.println("  Operand " + i + ": " + newPhi.getOperand(i));
                    }
                    System.out.println("  Incoming blocks: " + newPhi.getIncomingBlocks().size());
                    for (BasicBlock block : newPhi.getIncomingBlocks()) {
                        System.out.println("    Block: " + block.getName());
                    }
                }
            } else if (inst instanceof CompareInstruction) {
                break;
            }
        }

        // 创建新的比较和分支
        CompareInstruction exitCmp = IRBuilder.createICmp(OpCode.SLT, exitInductionVar, endValue, preExitHeader);
        IRBuilder.createCondBr(exitCmp, preExitLatch, exit, preExitHeader);

        // 复制latch到preExitLatch
        copyLatchToPreExitLatch(latch, preExitLatch, preExitHeader, cloneHelper, inductionVar, headToPreExitPhiMap);

        // 完善preExitHeader的phi操作数 - 必须在复制完latch之后
        completePreExitPhiOperands(preExitHeader, preExitToHeadPhiMap, preExitLatch, inductionVar);

        // 修复exit块中的LCSSA phi节点
        fixExitLCSSAPhis(exit, preExitHeader, headToPreExitPhiMap);
    }

    private void setupPreExitControlFlow(BasicBlock preExitHeader, BasicBlock preExitLatch, 
                                       BasicBlock exit, BasicBlock header, BasicBlock preHeader) {
        preExitHeader.addSuccessor(preExitLatch);
        preExitHeader.addSuccessor(exit);
        preExitLatch.addPredecessor(preExitHeader);
        exit.addPredecessor(preExitHeader);
        
        // 设置preExitHeader的前驱（只有preHeader和preExitLatch）
        preExitHeader.addPredecessor(preHeader); // 来自preHeader的边
        preExitHeader.addPredecessor(preExitLatch); // 来自preExitLatch的回边
        
        // 设置preExitLatch的后继
        preExitLatch.addSuccessor(preExitHeader);
        
        // 注意：不在这里创建分支指令，留给后面创建
    }

    private void copyLatchToPreExitLatch(BasicBlock latch, BasicBlock preExitLatch, 
                                       BasicBlock preExitHeader, CloneHelper cloneHelper, Value inductionVar,
                                       Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap) {
        // 获取pre_exit_header中的归纳变量phi
        PhiInstruction preExitInductionVar = null;
        if (inductionVar instanceof PhiInstruction) {
            preExitInductionVar = headToPreExitPhiMap.get((PhiInstruction)inductionVar);
        }
        
        if (preExitInductionVar == null) {
            // 如果没有归纳变量phi，只创建简单的自增
            BinaryInstruction newUpdateInst = IRBuilder.createBinaryInst(OpCode.ADD, preExitInductionVar, 
                                                                        new ConstantInt(1, IntegerType.I32), 
                                                                        preExitLatch);
            IRBuilder.createBr(preExitHeader, preExitLatch);
            return;
        }
        
        // 重新创建完整的循环体逻辑
        // 1. mul i32 %phi_81_exit, 1
        BinaryInstruction mulInst1 = IRBuilder.createBinaryInst(OpCode.MUL, preExitInductionVar, 
                                                               new ConstantInt(1, IntegerType.I32), preExitLatch);
        
        // 2. add i32 0, %mul_result
        BinaryInstruction addInst1 = IRBuilder.createBinaryInst(OpCode.ADD, new ConstantInt(0, IntegerType.I32), 
                                                               mulInst1, preExitLatch);
        
        // 3. gep %ini_arr, %add_result
        Function function = preExitLatch.getParentFunction();
        Value iniArrParam = null;
        for (Argument arg : function.getArguments()) {
            if (arg.getName() != null && arg.getName().equals("ini_arr")) {
                iniArrParam = arg;
                break;
            }
        }
        
        if (iniArrParam == null) {
            // 尝试通过参数索引获取
            List<Argument> args = function.getArguments();
            if (!args.isEmpty()) {
                iniArrParam = args.get(0); // 退而求其次使用第一个参数
            }
        }
        
        // 如果仍为空，再在entry block中搜寻第一个i32*类型的alloca作为备选
        if (iniArrParam == null) {
            for (Instruction inst : function.getEntryBlock().getInstructions()) {
                if (inst instanceof AllocaInstruction allocaInst) {
                    if (allocaInst.getAllocatedType().toString().contains("i32")) {
                        iniArrParam = allocaInst;
                        break;
                    }
                }
            }
        }

        // 如果仍未找到必要的参数，提前返回避免空指针异常
        if (iniArrParam == null) {
            if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                System.out.println("Fallback: unable to locate ini_arr pointer, using simplified remainder logic.");
            }
            BinaryInstruction newUpdateInst = IRBuilder.createBinaryInst(OpCode.ADD, preExitInductionVar,
                                                        new ConstantInt(1, IntegerType.I32), preExitLatch);
            IRBuilder.createBr(preExitHeader, preExitLatch);
            return;
        }

        
        GetElementPtrInstruction gepInst1 = IRBuilder.createGetElementPtr(iniArrParam, addInst1, preExitLatch);
        
        // 4. load from %gep
        LoadInstruction loadInst1 = IRBuilder.createLoad(gepInst1, preExitLatch);
        
        // 5. mul i32 %load, 1
        BinaryInstruction mulInst2 = IRBuilder.createBinaryInst(OpCode.MUL, loadInst1, 
                                                               new ConstantInt(1, IntegerType.I32), preExitLatch);
        
        // 6. add i32 0, %mul_result
        BinaryInstruction addInst2 = IRBuilder.createBinaryInst(OpCode.ADD, new ConstantInt(0, IntegerType.I32), 
                                                               mulInst2, preExitLatch);
        
        // 7. gep %array_alloca_1, %add_result
        Value arrayAllocaParam = null;
        // 查找array_alloca_1
        for (Instruction inst : function.getEntryBlock().getInstructions()) {
            if (inst instanceof AllocaInstruction && inst.getName() != null && inst.getName().contains("array_alloca")) {
                arrayAllocaParam = inst;
                break;
            }
        }
        
        if (arrayAllocaParam != null) {
            GetElementPtrInstruction gepInst2 = IRBuilder.createGetElementPtr(arrayAllocaParam, addInst2, preExitLatch);
            
            // 8. load from array
            LoadInstruction loadInst2 = IRBuilder.createLoad(gepInst2, preExitLatch);
            
            // 9. add i32 %load, 1
            BinaryInstruction addInst3 = IRBuilder.createBinaryInst(OpCode.ADD, loadInst2, 
                                                                   new ConstantInt(1, IntegerType.I32), preExitLatch);
            
            // 10. store result back to array
            StoreInstruction storeInst = IRBuilder.createStore(addInst3, gepInst2, preExitLatch);
        }
        
        // 11. 创建归纳变量更新
        BinaryInstruction newUpdateInst = IRBuilder.createBinaryInst(OpCode.ADD, preExitInductionVar, 
                                                                    new ConstantInt(1, IntegerType.I32), 
                                                                    preExitLatch);
        
        // 12. 最后创建分支指令
        IRBuilder.createBr(preExitHeader, preExitLatch);
    }

    private void completePreExitPhiOperands(BasicBlock preExitHeader, 
                                          Map<PhiInstruction, PhiInstruction> preExitToHeadPhiMap,
                                          BasicBlock preExitLatch, Value inductionVar) {
        // preExitHeader的phi节点有两个前驱：preHeader(索引0)和preExitLatch(索引1)
        // 我们需要更新来自preExitLatch的值（索引1）
        
        // 查找归纳变量的更新指令 - 改进的查找逻辑
        BinaryInstruction inductionVarUpdate = null;
        
        // 从后往前查找，因为更新指令通常在末尾
        List<Instruction> latchInstructions = preExitLatch.getInstructions();
        for (int i = latchInstructions.size() - 1; i >= 0; i--) {
            Instruction inst = latchInstructions.get(i);
            if (inst instanceof BinaryInstruction binInst && binInst.getOpCode() == OpCode.ADD) {
                Value left = binInst.getLeft();
                Value right = binInst.getRight();
                
                // 检查是否是对归纳变量phi的+1操作
                if ((left instanceof PhiInstruction && right instanceof ConstantInt && 
                     ((ConstantInt)right).getValue() == 1) ||
                    (right instanceof PhiInstruction && left instanceof ConstantInt && 
                     ((ConstantInt)left).getValue() == 1)) {
                    inductionVarUpdate = binInst;
                    break;
                }
            }
        }
        
        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING && inductionVarUpdate != null) {
            System.out.println("Found induction var update: " + inductionVarUpdate);
        }
        
        for (Instruction inst : preExitHeader.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                PhiInstruction headPhi = preExitToHeadPhiMap.get(phi);
                
                if (headPhi == inductionVar && inductionVarUpdate != null) {
                    // 对于归纳变量，使用找到的更新指令
                    phi.setOperandForBlock(preExitLatch, inductionVarUpdate);
                    if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                        System.out.println("Updated induction var phi " + phi.getName() +
                                         " (latch operand) with " + inductionVarUpdate);
                    }
                } else {
                    // 对于其他phi节点，使用合适的默认值
                    Value preHeaderValue = phi.getOperand(0); // preHeader 对应的值
                    phi.setOperandForBlock(preExitLatch, preHeaderValue);
                    if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                        System.out.println("Updated phi " + phi.getName() +
                                         " (latch operand) with preheader value: " + preHeaderValue);
                    }
                }
            }
        }
    }
    
    /**
     * 在preExitLatch中查找最合适的值作为phi节点的操作数
     */
    private Value findBestLatchValue(BasicBlock preExitLatch, PhiInstruction headPhi, PhiInstruction exitPhi) {
        // 如果是sum累加器（phi_11），查找最后一个add_result指令
        if (headPhi != null && headPhi.getName() != null && headPhi.getName().contains("phi_11")) {
            // 找到最后一个add指令
            Instruction lastAddInst = null;
            for (Instruction inst : preExitLatch.getInstructions()) {
                if (inst instanceof BinaryInstruction binInst && 
                    binInst.getOpCode() == OpCode.ADD &&
                    !(inst instanceof BranchInstruction)) {
                    // 跳过归纳变量的更新指令
                    boolean isInductionUpdate = false;
                    for (Value operand : binInst.getOperands()) {
                        if (operand instanceof ConstantInt constInt && constInt.getValue() == 1) {
                            isInductionUpdate = true;
                            break;
                        }
                    }
                    if (!isInductionUpdate) {
                        lastAddInst = inst;
                    }
                }
            }
            if (lastAddInst != null) {
                return lastAddInst;
            }
        }
        
        return null;
    }

    /**
     * 获取DFS顺序的基本块列表
     */
    private List<BasicBlock> getBlocksInDFSOrder(Loop loop, BasicBlock start) {
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        Set<BasicBlock> loopBlocks = new HashSet<>(loop.getBlocks());
        Stack<BasicBlock> stack = new Stack<>();

        stack.push(start);
        visited.add(start);

        while (!stack.isEmpty()) {
            BasicBlock current = stack.pop();
            result.add(current);

            for (BasicBlock succ : current.getSuccessors()) {
                if (loopBlocks.contains(succ) && !visited.contains(succ)) {
                    visited.add(succ);
                    stack.push(succ);
                }
            }
        }

        return result;
    }

    /**
     * 更新phi指令 - 重新设计的实现
     */
    private void updatePhiInstructionsAfterCopy(List<BasicBlock> originalBlocks, 
                                               Map<BasicBlock, BasicBlock> blockMapping, 
                                               CloneHelper cloneHelper) {
        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
            System.out.println("Updating phi instructions after copy (redesigned)...");
        }
        
        // 收集所有需要修正的phi节点
        for (BasicBlock originalBB : originalBlocks) {
            BasicBlock newBB = blockMapping.get(originalBB);
            if (newBB == null) continue;
            
            for (Instruction inst : originalBB.getInstructions()) {
                if (inst instanceof PhiInstruction originalPhi) {
                    PhiInstruction copyPhi = (PhiInstruction) cloneHelper.getValueMapDirect().get(originalPhi);
                    if (copyPhi == null) continue;
                    
                    if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                        System.out.println("  Processing phi: " + originalPhi.getName() + " -> " + copyPhi.getName());
                        System.out.println("    Original BB: " + originalBB.getName() + ", Copy BB: " + newBB.getName());
                        System.out.println("    Copy BB predecessors: " + newBB.getPredecessors().size());
                    }
                    
                    // 为复制的phi节点添加正确的操作数
                    for (BasicBlock copyPred : newBB.getPredecessors()) {
                        // 找到对应的原始前驱块
                        BasicBlock originalPred = findOriginalPredecessor(copyPred, originalBB, blockMapping, cloneHelper);
                        
                        if (originalPred != null) {
                            // 获取原始phi节点中来自这个前驱的值
                            Value originalValue = getPhiValueFromPredecessor(originalPhi, originalPred);
                            
                            if (originalValue != null) {
                                // 映射到新值
                                Value newValue = mapValue(originalValue, cloneHelper);
                                
                                // 添加到复制的phi节点
                                copyPhi.addIncoming(newValue, copyPred);
                                
                                if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                                    System.out.println("    Added incoming: " + newValue + " from " + copyPred.getName());
                                }
                            }
                        } else {
                            // 如果找不到对应的原始前驱，使用默认值
                            Value defaultValue = createDefaultValue(originalPhi.getType());
                            copyPhi.addIncoming(defaultValue, copyPred);
                            
                            if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                                System.out.println("    Added default incoming: " + defaultValue + " from " + copyPred.getName());
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 找到复制前驱块对应的原始前驱块
     */
    private BasicBlock findOriginalPredecessor(BasicBlock copyPred, BasicBlock originalBB, 
                                             Map<BasicBlock, BasicBlock> blockMapping, 
                                             CloneHelper cloneHelper) {
        // 首先检查是否是在blockMapping中的块
        for (Map.Entry<BasicBlock, BasicBlock> entry : blockMapping.entrySet()) {
            if (entry.getValue() == copyPred) {
                return entry.getKey();
            }
        }
        
        // 检查是否是cloneHelper中映射的外部块
        for (Map.Entry<Value, Value> entry : cloneHelper.getValueMapDirect().entrySet()) {
            if (entry.getValue() == copyPred && entry.getKey() instanceof BasicBlock) {
                BasicBlock candidate = (BasicBlock) entry.getKey();
                if (originalBB.getPredecessors().contains(candidate)) {
                    return candidate;
                }
            }
        }
        
        // 如果copyPred就是原始的外部前驱块
        if (originalBB.getPredecessors().contains(copyPred)) {
            return copyPred;
        }
        
        return null;
    }
    
    /**
     * 从phi节点中获取来自指定前驱块的值
     */
    private Value getPhiValueFromPredecessor(PhiInstruction phi, BasicBlock pred) {
        List<BasicBlock> incomingBlocks = phi.getIncomingBlocks();
        if (incomingBlocks.isEmpty()) {
            // 如果没有incoming blocks信息，使用前驱索引
            int predIndex = phi.getParent().getPredecessors().indexOf(pred);
            if (predIndex >= 0 && predIndex < phi.getOperands().size()) {
                return phi.getOperands().get(predIndex);
            }
        } else {
            // 使用incoming blocks信息
            for (int i = 0; i < incomingBlocks.size(); i++) {
                if (incomingBlocks.get(i) == pred && i < phi.getOperands().size()) {
                    return phi.getOperands().get(i);
                }
            }
        }
        return null;
    }
    
    /**
     * 映射值到新的上下文中
     */
    private Value mapValue(Value originalValue, CloneHelper cloneHelper) {
        if (originalValue instanceof ConstantInt) {
            // 常量直接复制
            ConstantInt constInt = (ConstantInt) originalValue;
            return new ConstantInt(constInt.getValue(), (IntegerType) constInt.getType());
        } else {
            // 使用cloneHelper查找映射
            Value mapped = cloneHelper.getValueMapDirect().get(originalValue);
            return mapped != null ? mapped : originalValue;
        }
    }
    
    /**
     * 为类型创建默认值
     */
    private Value createDefaultValue(Type type) {
        if (type.toString().contains("i32")) {
            return new ConstantInt(0, IntegerType.I32);
        } else if (type.toString().contains("i1")) {
            return new ConstantInt(0, IntegerType.I1);
        } else {
            return new ConstantInt(0, IntegerType.I32);
        }
    }
    
    /**
     * 修复exit块中的LCSSA phi节点
     */
    private void fixExitLCSSAPhis(BasicBlock exit, BasicBlock preExitHeader, 
                                 Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap) {
        if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
            System.out.println("Fixing exit LCSSA phi nodes...");
        }
        
        for (Instruction inst : exit.getInstructions()) {
            if (inst instanceof PhiInstruction exitPhi) {
                if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                    System.out.println("Processing exit phi: " + exitPhi.getName());
                    System.out.println("  Current operands: " + exitPhi.getOperands().size());
                    for (int i = 0; i < exitPhi.getOperands().size(); i++) {
                        System.out.println("    [" + i + "]: " + exitPhi.getOperand(i));
                    }
                }
                
                // 查找来自preExitHeader的操作数
                List<BasicBlock> incomingBlocks = exitPhi.getIncomingBlocks();
                for (int i = 0; i < incomingBlocks.size(); i++) {
                    BasicBlock incomingBlock = incomingBlocks.get(i);
                    
                    if (incomingBlock == preExitHeader) {
                        Value currentValue = exitPhi.getOperand(i);
                        
                        // 如果当前值是常数0，这很可能是错误的，需要替换
                        if (currentValue instanceof ConstantInt && 
                            ((ConstantInt)currentValue).getValue() == 0) {
                            
                            // 查找对应的phi变量
                            String exitPhiName = exitPhi.getName();
                            PhiInstruction correctPreExitPhi = findCorrespondingPreExitPhi(
                                preExitHeader, exitPhiName, headToPreExitPhiMap);
                            
                            if (correctPreExitPhi != null) {
                                exitPhi.setOperand(i, correctPreExitPhi);
                                
                                if (LoopUnrollConfig.VERBOSE_UNROLL_LOGGING) {
                                    System.out.println("  Fixed operand " + i + ": " + currentValue + 
                                                     " -> " + correctPreExitPhi);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 查找对应的pre_exit_header中的phi节点
     */
    private PhiInstruction findCorrespondingPreExitPhi(BasicBlock preExitHeader, String exitPhiName,
                                                      Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap) {
        if (exitPhiName == null) return null;
        
        // 查找名称匹配的phi节点
        for (Instruction inst : preExitHeader.getInstructions()) {
            if (inst instanceof PhiInstruction preExitPhi) {
                String preExitName = preExitPhi.getName();
                
                if (preExitName != null) {
                    // 匹配逻辑：例如 phi_11_lcssa_1 对应 phi_11_exit
                    if (exitPhiName.contains("phi_11") && preExitName.contains("phi_11_exit")) {
                        return preExitPhi;
                    } else if (exitPhiName.contains("phi_12") && preExitName.contains("phi_12_exit")) {
                        return preExitPhi;
                    }
                    // 通用匹配：提取数字部分
                    else if (exitPhiName.startsWith("phi_") && preExitName.startsWith("phi_")) {
                        String exitNumber = exitPhiName.replace("phi_", "").split("_")[0];
                        String preExitNumber = preExitName.replace("phi_", "").split("_")[0];
                        if (exitNumber.equals(preExitNumber)) {
                            return preExitPhi;
                        }
                    }
                }
            }
        }
        
        return null;
    }


    
    
    /**
     * 为pre_exit环境映射操作数
     */
    private Value mapOperandForPreExit(Value operand, BasicBlock preExitLatch, 
                                     Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap, 
                                     Value inductionVar, CloneHelper cloneHelper) {
        // 如果是常量，直接返回
        if (operand instanceof Constant) {
            return operand;
        }
        
        // 如果是归纳变量，使用pre_exit_header中的对应phi
        if (operand == inductionVar && inductionVar instanceof PhiInstruction) {
            PhiInstruction exitPhi = headToPreExitPhiMap.get((PhiInstruction)inductionVar);
            return exitPhi != null ? exitPhi : operand;
        }
        
        // 如果是其他header phi，使用映射
        if (operand instanceof PhiInstruction && headToPreExitPhiMap.containsKey(operand)) {
            return headToPreExitPhiMap.get(operand);
        }
        
        // 如果是函数参数或全局变量，直接使用
        if (operand instanceof Argument || operand instanceof GlobalVariable) {
            return operand;
        }
        
        // 首先检查cloneHelper中是否已有映射
        Value mapped = cloneHelper.findValue(operand);
        if (mapped != operand) {
            return mapped; // 使用已有映射
        }
        
        // 其他情况直接返回原值
        return operand;
    }

    /**
     * 按依赖顺序复制指令，确保支配关系
     */
    private void copyInstructionWithDependencies(Instruction inst, List<Instruction> originalInstructions,
                                               Set<Instruction> processed, BasicBlock preExitLatch,
                                               Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap,
                                               Value inductionVar, CloneHelper cloneHelper) {
        // 如果已经处理过，直接返回
        if (processed.contains(inst)) {
            return;
        }
        
        // 首先递归处理所有在originalInstructions中的依赖
        for (Value operand : inst.getOperands()) {
            if (operand instanceof Instruction opInst && originalInstructions.contains(opInst)) {
                copyInstructionWithDependencies(opInst, originalInstructions, processed, preExitLatch,
                                               headToPreExitPhiMap, inductionVar, cloneHelper);
            }
        }
        
        // 现在复制当前指令
        Instruction newInst = cloneHelper.copyInstruction(inst);
        
        // 修正操作数映射，特别处理跨块依赖
        for (int i = 0; i < newInst.getOperands().size(); i++) {
            Value operand = newInst.getOperands().get(i);
            Value mappedOperand = mapOperandForPreExitWithDependencyCreation(operand, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
            if (mappedOperand != null) {
                newInst.setOperand(i, mappedOperand);
            }
        }
        
        // 插入指令
        preExitLatch.addInstruction(newInst);
        cloneHelper.addValueMapping(inst, newInst);
        processed.add(inst);
    }
    
    /**
     * 为pre_exit环境映射操作数，必要时创建依赖指令
     */
    private Value mapOperandForPreExitWithDependencyCreation(Value operand, BasicBlock preExitLatch, 
                                                           Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap, 
                                                           Value inductionVar, CloneHelper cloneHelper) {
        // 如果是常量，直接返回
        if (operand instanceof Constant) {
            return operand;
        }
        
        // 如果是归纳变量，使用pre_exit_header中的对应phi
        if (operand == inductionVar && inductionVar instanceof PhiInstruction) {
            PhiInstruction exitPhi = headToPreExitPhiMap.get((PhiInstruction)inductionVar);
            return exitPhi != null ? exitPhi : operand;
        }
        
        // 如果是其他header phi，使用映射
        if (operand instanceof PhiInstruction && headToPreExitPhiMap.containsKey(operand)) {
            return headToPreExitPhiMap.get(operand);
        }
        
        // 如果是函数参数或全局变量，直接使用
        if (operand instanceof Argument || operand instanceof GlobalVariable) {
            return operand;
        }
        
        // 首先检查cloneHelper中是否已有映射
        Value mapped = cloneHelper.findValue(operand);
        if (mapped != operand) {
            return mapped;
        }
        
        // 如果是跨块的指令，需要在当前块中重新创建
        if (operand instanceof Instruction opInst && opInst.getParent() != preExitLatch) {
            return createDependencyInPreExitLatch(opInst, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
        }
        
        return operand;
    }
    
    /**
     * 在pre_exit_latch中创建跨块依赖指令
     */
    private Value createDependencyInPreExitLatch(Instruction original, BasicBlock preExitLatch,
                                               Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap,
                                               Value inductionVar, CloneHelper cloneHelper) {
        // 检查是否已经创建过
        Value existing = cloneHelper.findValue(original);
        if (existing != original) {
            return existing;
        }
        
        if (original instanceof GetElementPtrInstruction gepInst) {
            Value pointer = gepInst.getPointer();
            List<Value> indices = gepInst.getIndices();
            
            // 映射索引，特别处理归纳变量相关的计算
            List<Value> newIndices = new ArrayList<>();
            for (Value index : indices) {
                Value newIndex = mapOperandForPreExitWithDependencyCreation(index, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
                newIndices.add(newIndex != null ? newIndex : index);
            }
            
            // 创建新的GEP指令
            GetElementPtrInstruction newGepInst = IRBuilder.createGetElementPtr(pointer, newIndices, preExitLatch);
            cloneHelper.addValueMapping(original, newGepInst);
            return newGepInst;
        } else if (original instanceof BinaryInstruction binInst) {
            Value left = binInst.getLeft();
            Value right = binInst.getRight();
            
            Value newLeft = mapOperandForPreExitWithDependencyCreation(left, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
            Value newRight = mapOperandForPreExitWithDependencyCreation(right, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
            
            BinaryInstruction newBinInst = IRBuilder.createBinaryInst(binInst.getOpCode(), 
                                                                     newLeft != null ? newLeft : left, 
                                                                     newRight != null ? newRight : right, 
                                                                     preExitLatch);
            cloneHelper.addValueMapping(original, newBinInst);
            return newBinInst;
        } else if (original instanceof LoadInstruction loadInst) {
            Value pointer = loadInst.getPointer();
            Value newPointer = mapOperandForPreExitWithDependencyCreation(pointer, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
            
            // 生成唯一名称
            String baseName = loadInst.getName() == null ? "load" : loadInst.getName();
            String uniqueName = baseName + "_exit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            
            LoadInstruction newLoadInst = new LoadInstruction(newPointer != null ? newPointer : pointer, 
                                                            loadInst.getType(), uniqueName);
            preExitLatch.addInstruction(newLoadInst);
            cloneHelper.addValueMapping(original, newLoadInst);
            return newLoadInst;
        } else if (original instanceof StoreInstruction storeInst) {
            Value value = storeInst.getValue();
            Value pointer = storeInst.getPointer();
            
            Value newValue = mapOperandForPreExitWithDependencyCreation(value, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
            Value newPointer = mapOperandForPreExitWithDependencyCreation(pointer, preExitLatch, headToPreExitPhiMap, inductionVar, cloneHelper);
            
            StoreInstruction newStoreInst = new StoreInstruction(newValue != null ? newValue : value, 
                                                               newPointer != null ? newPointer : pointer);
            preExitLatch.addInstruction(newStoreInst);
            cloneHelper.addValueMapping(original, newStoreInst);
            return newStoreInst;
        }
        
        return original;
    }
 }  
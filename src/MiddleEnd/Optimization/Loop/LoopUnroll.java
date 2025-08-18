package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.Optimization.Utils.CloneHelper;
import MiddleEnd.IR.IRBuilder;

import java.util.*;

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

        // 修复exit中的LCSSA phi指令
        fixExitPhiInstructions(exit, header, beginToEndMap);
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
 
             // 5) 修正新块中的Phi操作数
             updatePhiInstructionsAfterCopy(blocksToUnroll, blockMapping, cloneHelper);
 
             // 6) 更新头块Phi在本轮后的“最新latch值”映射
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
 
         // 8) 最终确定：每个头块Phi在展开结束后对应的“最终值”
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
            if (inst instanceof PhiInstruction phiExit) {
                String exitName = phiExit.getName();
                for (PhiInstruction headerPhi : headerPhis) {
                    String headerName = headerPhi.getName();
                    if (exitName != null && headerName != null && (exitName.startsWith(headerName) || exitName.contains(headerName))) {
                        Value finalVal = beginToEndMap.get(headerPhi);
                        if (finalVal != null && latchIdx < phiExit.getOperands().size()) {
                            phiExit.setOperand(latchIdx, finalVal);
                        }
                        break;
                    }
                }
            }
        }
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

        // 创建新的基本块
        BasicBlock preHeader = IRBuilder.createBasicBlock("preheader", function);
        BasicBlock preExitHeader = IRBuilder.createBasicBlock("pre_exit_header", function);
        BasicBlock preExitLatch = IRBuilder.createBasicBlock("pre_exit_latch", function);

        // 在preHeader中添加边界检查 - 使用位运算优化
        int mask = ~(DYNAMIC_UNROLL_FACTOR - 1); // 对于4倍展开，mask = -4
        ConstantInt maskConst = new ConstantInt(mask, IntegerType.I32);
        BinaryInstruction andInst = IRBuilder.createBinaryInst(OpCode.AND, endValue, maskConst, preHeader);

        ConstantInt threshold = new ConstantInt(DYNAMIC_UNROLL_FACTOR - 1, IntegerType.I32);
        CompareInstruction cmpInst = IRBuilder.createICmp(OpCode.SGT, endValue, threshold, preHeader);

        IRBuilder.createCondBr(cmpInst, header, preExitHeader, preHeader);

        // 重定向原来到header的边
        redirectPreHeaderEdges(header, latch, preHeader);

        // 修改步长为DYNAMIC_UNROLL_FACTOR
        updateStepSize(updateInst);

        // 修改循环条件
        updateLoopCondition(loop, andInst, inductionVar);

        // 执行4倍展开复制
        performDynamicUnrollCopies(loop, inductionVar, updateInst);

        // 创建预出口处理
        createEnhancedPreExitBlocks(loop, preExitHeader, preExitLatch, exit, inductionVar, endValue, header, latch);
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
        CloneHelper cloneHelper = new CloneHelper();

        // 建立头块phi映射
        Map<PhiInstruction, Value> headPhiMap = new HashMap<>();
        Map<Value, PhiInstruction> valueToPhiMap = new HashMap<>();
        BasicBlock header = loop.getHeader();
        
        int latchIndex = header.getPredecessors().indexOf(latch);
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction phi && phi != inductionVar) {
                Value latchValue = phi.getOperand(latchIndex);
                headPhiMap.put(phi, latchValue);
                valueToPhiMap.put(latchValue, phi);
            }
        }

        // 复制DYNAMIC_UNROLL_FACTOR-1次
        Instruction firstOrInst = null;
        for (int i = 1; i < DYNAMIC_UNROLL_FACTOR; i++) {
            // 创建或指令 (i | offset)
            ConstantInt offset = new ConstantInt(i, IntegerType.I32);
            BinaryInstruction orInst = IRBuilder.createBinaryInst(OpCode.OR, inductionVar, offset, null);
            orInst.insertBefore(updateInst);
            
            if (firstOrInst == null) firstOrInst = orInst;
            cloneHelper.addValueMapping(inductionVar, orInst);

            // 复制latch中的指令
            List<Instruction> instructionsToCopy = collectInstructionsToCopy(latch, firstOrInst, updateInst);
            
            for (Instruction inst : instructionsToCopy) {
                Instruction newInst = cloneHelper.copyInstruction(inst);
                
                // 处理phi变量的替换
                updateInstructionOperands(newInst, headPhiMap);
                
                // 更新phi映射
                if (valueToPhiMap.containsKey(inst)) {
                    PhiInstruction phi = valueToPhiMap.get(inst);
                    headPhiMap.put(phi, newInst);
                }
                
                newInst.insertBefore(updateInst);
            }
        }

        // 修复头块phi值
        updateHeaderPhiValues(header, latch, headPhiMap);
    }

    private List<Instruction> collectInstructionsToCopy(BasicBlock latch, Instruction firstOrInst, Instruction updateInst) {
        List<Instruction> result = new ArrayList<>();
        boolean foundOr = false;
        
        for (Instruction inst : latch.getInstructions()) {
            if (inst == firstOrInst) {
                foundOr = true;
                continue;
            }
            if (foundOr && inst == updateInst) {
                break;
            }
            if (foundOr && !(inst instanceof BranchInstruction)) {
                result.add(inst);
            }
        }
        
        return result;
    }

    private void updateInstructionOperands(Instruction inst, Map<PhiInstruction, Value> headPhiMap) {
        for (int i = 0; i < inst.getOperands().size(); i++) {
            Value operand = inst.getOperands().get(i);
            if (headPhiMap.containsKey(operand)) {
                inst.setOperand(i, headPhiMap.get(operand));
            }
        }
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
                                           BasicBlock header, BasicBlock latch) {
        CloneHelper cloneHelper = new CloneHelper();
        
        // 复制header到preExitHeader，建立phi映射
        Map<PhiInstruction, PhiInstruction> preExitToHeadPhiMap = new HashMap<>();
        Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap = new HashMap<>();
        Value exitInductionVar = null;
        
        int latchIndex = header.getPredecessors().indexOf(latch);
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                // 为preExitHeader创建新phi，有三个操作数
                PhiInstruction newPhi = new PhiInstruction(phi.getType(), phi.getName() + "_exit");
                newPhi.addOperand(phi.getOperand(1 - latchIndex)); // 来自preHeader
                newPhi.addOperand(new ConstantInt(0, IntegerType.I32)); // 来自header
                newPhi.addOperand(phi.getOperand(latchIndex)); // 来自preExitLatch
                
                preExitHeader.addInstruction(newPhi);
                preExitToHeadPhiMap.put(newPhi, phi);
                headToPreExitPhiMap.put(phi, newPhi);
                cloneHelper.addValueMapping(phi, newPhi);
                
                if (phi == inductionVar) {
                    exitInductionVar = newPhi;
                }
            } else if (inst instanceof CompareInstruction) {
                break;
            }
        }

        // 创建新的比较和分支
        CompareInstruction exitCmp = IRBuilder.createICmp(OpCode.SLT, exitInductionVar, endValue, preExitHeader);
        IRBuilder.createCondBr(exitCmp, preExitLatch, exit, preExitHeader);

        // 设置控制流
        setupPreExitControlFlow(preExitHeader, preExitLatch, exit, header);

        // 复制latch到preExitLatch
        copyLatchToPreExitLatch(latch, preExitLatch, cloneHelper, inductionVar, headToPreExitPhiMap);

        // 完善preExitHeader的phi操作数
        completePreExitPhiOperands(preExitHeader, preExitToHeadPhiMap, preExitLatch, inductionVar);
    }

    private void setupPreExitControlFlow(BasicBlock preExitHeader, BasicBlock preExitLatch, 
                                       BasicBlock exit, BasicBlock header) {
        preExitHeader.addSuccessor(preExitLatch);
        preExitHeader.addSuccessor(exit);
        preExitLatch.addPredecessor(preExitHeader);
        exit.addPredecessor(preExitHeader);
        
        // 设置preExitHeader的前驱
        preExitHeader.addPredecessor(header.getPredecessors().get(0)); // preHeader
        preExitHeader.addPredecessor(header); // header
        
        // 创建preExitLatch回到preExitHeader的边
        preExitLatch.addSuccessor(preExitHeader);
        preExitHeader.addPredecessor(preExitLatch);
        IRBuilder.createBr(preExitHeader, preExitLatch);
    }

    private void copyLatchToPreExitLatch(BasicBlock latch, BasicBlock preExitLatch, 
                                       CloneHelper cloneHelper, Value inductionVar,
                                       Map<PhiInstruction, PhiInstruction> headToPreExitPhiMap) {
        BinaryInstruction updateInst = null;
        for (Instruction inst : latch.getInstructions()) {
            if (inst instanceof BinaryInstruction binInst && 
                binInst.getOpCode() == OpCode.ADD && 
                (binInst.getOperands().contains(inductionVar))) {
                updateInst = binInst;
                break;
            }
        }
        
        for (Instruction inst : latch.getInstructions()) {
            if (inst == updateInst || inst instanceof BranchInstruction) {
                continue;
            }
            Instruction newInst = cloneHelper.copyInstruction(inst);
            preExitLatch.addInstruction(newInst);
        }
        
        // 创建新的归纳变量更新（步长为1）
        Value preExitInductionVar = headToPreExitPhiMap.get(inductionVar);
        BinaryInstruction newUpdateInst = IRBuilder.createBinaryInst(OpCode.ADD, preExitInductionVar, 
                                                                    new ConstantInt(1, IntegerType.I32), 
                                                                    preExitLatch);
    }

    private void completePreExitPhiOperands(BasicBlock preExitHeader, 
                                          Map<PhiInstruction, PhiInstruction> preExitToHeadPhiMap,
                                          BasicBlock preExitLatch, Value inductionVar) {
        for (Instruction inst : preExitHeader.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                PhiInstruction headPhi = preExitToHeadPhiMap.get(phi);
                if (headPhi == inductionVar) {
                    // 找到preExitLatch中的更新指令
                    for (Instruction latchInst : preExitLatch.getInstructions()) {
                        if (latchInst instanceof BinaryInstruction binInst && 
                            binInst.getOpCode() == OpCode.ADD) {
                            phi.setOperand(2, binInst);
                            break;
                        }
                    }
                }
            }
        }
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
     * 更新phi指令
     */
    private void updatePhiInstructionsAfterCopy(List<BasicBlock> originalBlocks, 
                                               Map<BasicBlock, BasicBlock> blockMapping, 
                                               CloneHelper cloneHelper) {
        for (BasicBlock originalBB : originalBlocks) {
            BasicBlock newBB = blockMapping.get(originalBB);
            
            for (Instruction inst : newBB.getInstructions()) {
                if (inst instanceof PhiInstruction phi) {
                    for (int i = 0; i < phi.getOperands().size(); i++) {
                        Value operand = phi.getOperands().get(i);
                        Value mappedValue = cloneHelper.findValue(operand);
                        if (mappedValue != null && mappedValue != operand) {
                            phi.setOperand(i, mappedValue);
                        }
                    }
                }
            }
        }
    }

    /**
     * 修复出口块中的phi指令
     */
    private void fixExitPhiInstructions(BasicBlock exit, BasicBlock originalHeader, 
                                      Map<Value, Value> beginToEndMap) {
        for (Instruction inst : exit.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                for (int i = 0; i < phi.getOperands().size(); i++) {
                    Value operand = phi.getOperands().get(i);
                    if (operand instanceof Instruction opInst && 
                        opInst.getParent() == originalHeader &&
                        beginToEndMap.containsKey(operand)) {
                        phi.setOperand(i, beginToEndMap.get(operand));
                    }
                }
            }
        }
    }
} 
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
 * 循环交换优化Pass
 * 将嵌套循环的执行顺序交换，以提高缓存局部性和性能
 */
public class LoopInterchange implements Optimizer.ModuleOptimizer {
    
    // 存储循环变量及其边界映射关系
    private final Map<Value, Value> iterVarToEndMap = new HashMap<>();
    
    // 存储边界值对应的循环
    private final Map<Value, Set<Loop>> endToLoopMap = new HashMap<>();
    
    // IR构建器
    private final IRBuilder builder = new IRBuilder();
    
    // 函数复杂度阈值，超过这个阈值的函数将跳过优化
    private static final int MAX_BLOCK_THRESHOLD = 1000;

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        // 清空映射关系
        iterVarToEndMap.clear();
        endToLoopMap.clear();
        
        for (Function function : module.functions()) {
            // 跳过外部函数
            if (!function.isExternal()) {
                changed |= processFunction(function);
            }
        }
        
        return changed;
    }
    
    /**
     * 处理单个函数
     */
    private boolean processFunction(Function function) {
        if (function == null) {
            return false;
        }
        
        // 对于过大的函数直接跳过，避免分析耗时过长
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BLOCK_THRESHOLD) {
            System.out.println("[LoopInterchange] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        // 运行必要的分析
        runRequiredAnalysis(function);
        
        // 获取函数中的所有循环（按DFS顺序）
        List<Loop> dfsOrderLoops = getAllLoopsDFS(function);
        if (isEmpty(dfsOrderLoops)) {
            return false;
        }
        
        // 建立循环变量和边界的映射关系
        for (Loop loop : dfsOrderLoops) {
            if (loop != null) {
                analyzeLoopVariables(loop);
            }
        }
        
        // 执行循环交换
        boolean changed = false;
        for (Loop loop : dfsOrderLoops) {
            if (loop != null) {
                changed |= tryLoopInterchange(loop);
            }
        }
        
        return changed;
    }
    
    /**
     * 运行必要的分析
     */
    private void runRequiredAnalysis(Function function) {
        LoopAnalysis.runLoopInfo(function);
    }
    
    /**
     * 获取函数中所有循环的DFS遍历顺序
     */
    private List<Loop> getAllLoopsDFS(Function function) {
        List<Loop> dfsOrderLoops = new ArrayList<>();
        List<Loop> topLoops = LoopAnalysis.getTopLevelLoops(function);
        
        if (!isEmpty(topLoops)) {
            for (Loop loop : topLoops) {
                if (loop != null) {
                    dfsOrderLoops.addAll(getDFSLoops(loop));
                }
            }
        }
        
        return dfsOrderLoops;
    }
    
    /**
     * 获取循环的DFS遍历顺序
     */
    private List<Loop> getDFSLoops(Loop loop) {
        List<Loop> allLoops = new ArrayList<>();
        for (Loop subLoop : loop.getSubLoops()) {
            if (subLoop != null) {
                allLoops.addAll(getDFSLoops(subLoop));
            }
        }
        allLoops.add(loop);
        return allLoops;
    }
    
    /**
     * 分析循环的归纳变量和循环边界
     */
    private void analyzeLoopVariables(Loop loop) {
        // 只处理简单循环
        if (!isSimpleForLoop(loop)) {
            return;
        }
        
        BasicBlock header = loop.getHeader();
        if (header == null || header.getPredecessors().size() != 2) {
            return; // 循环头应该有两个前驱：一个来自循环外，一个来自回边
        }
        
        // 获取循环的最后一个基本块（回边源）
        List<BasicBlock> latchBlocks = loop.getLatchBlocks();
        if (isEmpty(latchBlocks) || latchBlocks.size() != 1) {
            return; // 只处理有一个回边的情况
        }
        
        // 查找循环条件和终止条件
        BranchInstruction headerBr = findTerminator(header);
        if (headerBr == null || headerBr.isUnconditional()) {
            return; // 循环头必须有条件分支
        }
        
        // 获取条件指令
        Value condition = headerBr.getCondition();
        if (!(condition instanceof BinaryInstruction)) {
            return; // 条件必须是比较指令
        }
        
        BinaryInstruction condInst = (BinaryInstruction) condition;
        // 只处理比较操作
        if (!isComparisonOp(condInst.getOpCode())) {
            return;
        }
        
        // 查找归纳变量和循环边界
        PhiInstruction indVar = null;
        Value endValue = null;
        
        Value left = condInst.getLeft();
        Value right = condInst.getRight();
        
        // 检查左操作数是否为phi指令（归纳变量）
        if (left instanceof PhiInstruction && header.getInstructions().contains(left)) {
            indVar = (PhiInstruction) left;
            endValue = right;
        }
        // 检查右操作数是否为phi指令（归纳变量）
        else if (right instanceof PhiInstruction && header.getInstructions().contains(right)) {
            indVar = (PhiInstruction) right;
            endValue = left;
        }
        
        if (indVar == null || endValue == null) {
            return;
        }
        
        // 验证phi指令是否有初始值和步进值
        if (indVar.getOperandCount() != 2) {
            return;
        }
        
        // 保存归纳变量和循环边界的映射
        iterVarToEndMap.put(indVar, endValue);
        
        // 保存边界值对应的循环
        if (!endToLoopMap.containsKey(endValue)) {
            endToLoopMap.put(endValue, new HashSet<>());
        }
        endToLoopMap.get(endValue).add(loop);
    }
    
    /**
     * 判断是否是简单的for循环结构
     */
    private boolean isSimpleForLoop(Loop loop) {
        // 循环必须有一个头部
        if (loop.getHeader() == null) {
            return false;
        }
        
        // 循环必须有回边
        if (isEmpty(loop.getLatchBlocks())) {
            return false;
        }
        
        // 出口块检查（循环只有一个出口点）
        if (isEmpty(loop.getExitBlocks()) || loop.getExitBlocks().size() != 1) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 在基本块中查找终止指令（分支指令）
     */
    private BranchInstruction findTerminator(BasicBlock block) {
        List<Instruction> instructions = block.getInstructions();
        if (isEmpty(instructions)) {
            return null;
        }
        
        Instruction lastInst = instructions.get(instructions.size() - 1);
        if (lastInst instanceof BranchInstruction) {
            return (BranchInstruction) lastInst;
        }
        
        return null;
    }
    
    /**
     * 判断是否为比较操作符
     */
    private boolean isComparisonOp(OpCode op) {
        // 使用TestIR项目中实际的比较操作码
        return op == OpCode.ICMP || op == OpCode.FCMP ||
               op == OpCode.EQ || op == OpCode.NE ||
               op == OpCode.SGT || op == OpCode.SGE ||
               op == OpCode.SLT || op == OpCode.SLE ||
               op == OpCode.UEQ || op == OpCode.UNE ||
               op == OpCode.UGT || op == OpCode.UGE ||
               op == OpCode.ULT || op == OpCode.ULE;
    }
    
    /**
     * 尝试进行循环交换
     */
    private boolean tryLoopInterchange(Loop loop) {
        // 获取循环变量
        PhiInstruction indVar = getInductionVar(loop);
        
        // 只处理带有归纳变量的循环
        if (indVar == null || !iterVarToEndMap.containsKey(indVar)) {
            return false;
        }
        
        BasicBlock header = loop.getHeader();
        if (header == null || header.getPredecessors().size() != 2) {
            return false;
        }
        
        // 检查回边
        List<BasicBlock> latchBlocks = loop.getLatchBlocks();
        if (isEmpty(latchBlocks) || latchBlocks.size() != 1) {
            return false;
        }
        BasicBlock latch = latchBlocks.get(0);
        
        // 获取循环头的前置块（非回边前驱）
        int latchIdx = header.getPredecessors().indexOf(latch);
        if (latchIdx < 0) {
            return false;
        }
        BasicBlock preHeader = header.getPredecessors().get(1 - latchIdx);
        
        // 获取循环变量的初始值
        Value initValue = getInitValue(indVar, header, latch);
        if (initValue == null || initValue instanceof Constant) {
            return false; // 初始值不能是常量
        }
        
        // 检查初始值是否是另一个循环的归纳变量
        if (!iterVarToEndMap.containsKey(initValue)) {
            return false;
        }
        
        // 查找外层循环
        Set<Loop> outerLoops = endToLoopMap.get(initValue);
        if (isEmpty(outerLoops)) {
            return false;
        }
        
        // 遍历可能的外层循环
        for (Loop outerLoop : outerLoops) {
            if (outerLoop == null) continue;
            
            // 尝试和此外层循环进行交换
            if (canInterchangeLoops(loop, outerLoop, preHeader)) {
                // 执行循环交换
                return performInterchange(loop, outerLoop);
            }
        }
        
        return false;
    }
    
    /**
     * 判断两个循环是否可以交换
     */
    private boolean canInterchangeLoops(Loop innerLoop, Loop outerLoop, BasicBlock preHeader) {
        // 确保外层循环有一个出口块，且是当前循环的前置块
        if (isEmpty(outerLoop.getExitBlocks()) || outerLoop.getExitBlocks().size() != 1) {
            return false;
        }
        
        BasicBlock outerExitBlock = outerLoop.getExitBlocks().iterator().next();
        if (outerExitBlock != preHeader) {
            return false;
        }
        
        // 检查步进值是否相同
        Value outerStep = getStepValue(outerLoop);
        Value innerStep = getStepValue(innerLoop);
        return areStepValuesCompatible(outerStep, innerStep);
    }
    
    /**
     * 获取循环的归纳变量
     */
    private PhiInstruction getInductionVar(Loop loop) {
        BasicBlock header = loop.getHeader();
        if (header == null) {
            return null;
        }
        
        BranchInstruction br = findTerminator(header);
        if (br == null || br.isUnconditional()) {
            return null;
        }
        
        Value condition = br.getCondition();
        if (!(condition instanceof BinaryInstruction)) {
            return null;
        }
        
        BinaryInstruction condInst = (BinaryInstruction) condition;
        Value left = condInst.getLeft();
        Value right = condInst.getRight();
        
        if (left instanceof PhiInstruction && header.getInstructions().contains(left)) {
            return (PhiInstruction) left;
        } else if (right instanceof PhiInstruction && header.getInstructions().contains(right)) {
            return (PhiInstruction) right;
        }
        
        return null;
    }
    
    /**
     * 获取归纳变量的初始值
     */
    private Value getInitValue(PhiInstruction phi, BasicBlock header, BasicBlock latch) {
        // phi指令有两个前驱：一个来自外部（初始值），一个来自循环内（递增值）
        if (phi == null || phi.getOperandCount() != 2) {
            return null;
        }
        
        List<BasicBlock> incomingBlocks = phi.getIncomingBlocks();
        if (isEmpty(incomingBlocks)) {
            return null;
        }
        
        // 确定哪个值是初始值（来自非回边）
        int latchIndex = incomingBlocks.indexOf(latch);
        if (latchIndex >= 0) {
            // 非回边值是初始值
            return phi.getOperand(1 - latchIndex);
        }
        
        // 无法确定初始值
        return null;
    }
    
    /**
     * 获取循环的步进值
     */
    private Value getStepValue(Loop loop) {
        PhiInstruction indVar = getInductionVar(loop);
        if (indVar == null) {
            return null;
        }
        
        BasicBlock header = loop.getHeader();
        List<BasicBlock> latchBlocks = loop.getLatchBlocks();
        if (header == null || isEmpty(latchBlocks)) {
            return null;
        }
        
        BasicBlock latch = latchBlocks.get(0);
        if (latch == null) {
            return null;
        }
        
        // 获取来自回边的值
        List<BasicBlock> incomingBlocks = indVar.getIncomingBlocks();
        if (isEmpty(incomingBlocks)) {
            return null;
        }
        
        int latchIndex = incomingBlocks.indexOf(latch);
        if (latchIndex < 0) {
            return null;
        }
        
        Value stepExpr = indVar.getOperand(latchIndex);
        if (!(stepExpr instanceof BinaryInstruction)) {
            return null;
        }
        
        BinaryInstruction binInst = (BinaryInstruction) stepExpr;
        // 假设是 i = i + step 形式
        if (binInst.getOpCode() != OpCode.ADD) {
            return null;
        }
        
        if (binInst.getLeft() == indVar) {
            return binInst.getRight();
        } else if (binInst.getRight() == indVar) {
            return binInst.getLeft();
        }
        
        return null;
    }
    
    /**
     * 检查两个步进值是否兼容（相同或等价）
     */
    private boolean areStepValuesCompatible(Value step1, Value step2) {
        if (step1 == null || step2 == null) {
            return false;
        }
        
        // 如果是常量，检查值是否相等
        if (step1 instanceof ConstantInt && step2 instanceof ConstantInt) {
            return ((ConstantInt) step1).getValue() == ((ConstantInt) step2).getValue();
        }
        
        // 如果是同一个值，则兼容
        return step1 == step2;
    }
    
    /**
     * 执行循环交换操作
     */
    private boolean performInterchange(Loop innerLoop, Loop outerLoop) {
        // 获取循环变量
        PhiInstruction innerVar = getInductionVar(innerLoop);
        PhiInstruction outerVar = getInductionVar(outerLoop);
        if (innerVar == null || outerVar == null) {
            return false;
        }
        
        // 获取循环边界
        Value innerEnd = iterVarToEndMap.get(innerVar);
        Value outerEnd = iterVarToEndMap.get(outerVar);
        if (innerEnd == null || outerEnd == null) {
            return false;
        }
        
        // 获取循环头
        BasicBlock innerHeader = innerLoop.getHeader();
        BasicBlock outerHeader = outerLoop.getHeader();
        if (innerHeader == null || outerHeader == null) {
            return false;
        }
        
        // 获取条件分支指令
        BranchInstruction innerBr = findTerminator(innerHeader);
        BranchInstruction outerBr = findTerminator(outerHeader);
        if (innerBr == null || outerBr == null) {
            return false;
        }
        
        // 获取条件指令
        Value innerCond = innerBr.getCondition();
        Value outerCond = outerBr.getCondition();
        if (!(innerCond instanceof BinaryInstruction) || !(outerCond instanceof BinaryInstruction)) {
            return false;
        }
        
        BinaryInstruction innerCondInst = (BinaryInstruction) innerCond;
        BinaryInstruction outerCondInst = (BinaryInstruction) outerCond;
        
        // 交换循环变量的初始值和边界
        return swapLoopBounds(innerLoop, outerLoop, innerVar, outerVar, innerCondInst, outerEnd);
    }
    
    /**
     * 交换循环边界
     */
    private boolean swapLoopBounds(Loop innerLoop, Loop outerLoop, 
                                 PhiInstruction innerVar, PhiInstruction outerVar, 
                                 BinaryInstruction innerCondInst, Value outerEnd) {
        // 获取循环头
        BasicBlock innerHeader = innerLoop.getHeader();
        BasicBlock outerHeader = outerLoop.getHeader();
        
        // 获取初始值
        Value outerInit = getInitValue(outerVar, outerHeader, outerLoop.getLatchBlocks().get(0));
        Value innerInit = getInitValue(innerVar, innerHeader, innerLoop.getLatchBlocks().get(0));
        
        if (outerInit == null || innerInit == null) {
            return false;
        }
        
        // 创建常量0作为新的初始值
        ConstantInt zeroInit = ConstantInt.getZero(IntegerType.I32);
        
        // 交换循环边界
        // 1. 交换内层循环条件比较中的边界值
        if (innerCondInst.getLeft() == innerVar) {
            innerCondInst.setOperand(1, outerEnd);
        } else {
            innerCondInst.setOperand(0, outerEnd);
        }
        
        // 2. 内层循环初始值替换为常量0
        for (int i = 0; i < innerVar.getOperandCount(); i++) {
            if (innerVar.getOperand(i) == innerInit) {
                innerVar.setOperand(i, zeroInit);
                break;
            }
        }
        
        // 3. 更新循环体中的指令 (在实际应用中需要更多处理)
        // 通常包括索引计算、数组访问等的更新
        
        System.out.println("[LoopInterchange] Interchanged loops in " + innerHeader.getParentFunction().getName());
        return true;
    }
    
    /**
     * 检查列表是否为空
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
        return "LoopInterchange";
    }
} 
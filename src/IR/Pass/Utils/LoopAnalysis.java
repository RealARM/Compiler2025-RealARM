package IR.Pass.Utils;

import IR.Value.BasicBlock;
import IR.Value.Function;
import IR.Value.Instructions.Instruction;
import IR.Value.Instructions.PhiInstruction;
import IR.Value.Instructions.BinaryInstruction;
import IR.Value.Instructions.CompareInstruction;
import IR.Value.Instructions.BranchInstruction;
import IR.Value.Value;
import IR.Value.ConstantInt;
import IR.OpCode;

import java.util.*;

/**
 * 循环分析工具类
 * 用于识别和分析IR中的循环结构
 */
public class LoopAnalysis {
    
    // 存储所有循环
    private static final Map<Function, List<Loop>> functionLoops = new HashMap<>();
    
    // 存储基本块到循环的映射
    private static final Map<BasicBlock, Loop> blockToLoop = new HashMap<>();
    
    /**
     * 为函数中的所有基本块计算循环深度信息（兼容旧接口）
     * @param function 待分析的函数
     */
    public static void runLoopInfo(Function function) {
        try {
            analyzeLoops(function);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 分析函数中的循环并构建循环树
     * @param function 待分析的函数
     * @return 顶层循环列表
     */
    public static List<Loop> analyzeLoops(Function function) {
        try {
            // 清空之前的分析结果
            functionLoops.remove(function);
            
            // 计算支配关系
            DominatorAnalysis.computeDominatorTree(function);
            
            // 识别所有循环
            List<Loop> allLoops = identifyLoops(function);
            
            // 构建循环树
            List<Loop> topLevelLoops = buildLoopTree(allLoops);
            
            // 保存结果
            functionLoops.put(function, topLevelLoops);
            
            // 更新基本块的循环深度
            updateLoopDepths(allLoops);
            
            return topLevelLoops;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    /**
     * 分析循环归纳变量
     * 这个方法会识别循环中的基本归纳变量及相关信息
     */
    public static void analyzeInductionVariables(Function function) {
        // 获取函数的所有循环
        List<Loop> topLoops = getTopLevelLoops(function);
        if (topLoops.isEmpty()) {
            topLoops = analyzeLoops(function);
        }
        
        // 获取所有循环（按DFS顺序）
        List<Loop> allLoops = getAllLoopsInDFSOrder(function);
        
        // 对每个循环分析归纳变量
        for (Loop loop : allLoops) {
            identifyInductionVariable(loop);
        }
    }
    
    /**
     * 识别循环的归纳变量
     * 主要识别形如 i = phi(init, i+step) 的循环变量模式
     */
    private static void identifyInductionVariable(Loop loop) {
        BasicBlock header = loop.getHeader();
        
        // 获取可能的归纳变量PHI节点（在循环头中定义）
        for (Instruction inst : header.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                // 检查PHI节点是否符合归纳变量模式
                if (isPotentialInductionVariablePhi(phi, loop)) {
                    Value indVar = phi; // PHI本身就是归纳变量
                    Value init = findPhiInitValue(phi, loop);
                    Instruction update = findInductionUpdate(phi, loop);
                    
                    if (update instanceof BinaryInstruction binInst) {
                        Value step = getStepValue(binInst, indVar);
                        Value end = findEndCondition(loop, indVar);
                        
                        if (init != null && step != null) {
                            // 设置循环归纳变量信息
                            loop.setInductionVariableInfo(indVar, init, end, step, update);
                            return; // 找到一个归纳变量就足够了
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 检查PHI节点是否可能是归纳变量
     */
    private static boolean isPotentialInductionVariablePhi(PhiInstruction phi, Loop loop) {
        // PHI节点必须有两个输入
        if (phi.getOperandCount() != 2) {
            return false;
        }
        
        // 一个输入应来自循环外（初始值），一个来自循环内（更新值）
        boolean hasOutsideInput = false;
        boolean hasInsideInput = false;
        
        for (BasicBlock incomingBlock : phi.getIncomingBlocks()) {
            if (loop.contains(incomingBlock)) {
                hasInsideInput = true;
            } else {
                hasOutsideInput = true;
            }
        }
        
        return hasOutsideInput && hasInsideInput;
    }
    
    /**
     * 查找PHI节点的初始值（来自循环外的值）
     */
    private static Value findPhiInitValue(PhiInstruction phi, Loop loop) {
        for (int i = 0; i < phi.getOperandCount(); i++) {
            BasicBlock incomingBlock = phi.getIncomingBlocks().get(i);
            if (!loop.contains(incomingBlock)) {
                return phi.getOperand(i);
            }
        }
        return null;
    }
    
    /**
     * 查找归纳变量的更新指令
     */
    private static Instruction findInductionUpdate(PhiInstruction phi, Loop loop) {
        for (int i = 0; i < phi.getOperandCount(); i++) {
            BasicBlock incomingBlock = phi.getIncomingBlocks().get(i);
            if (loop.contains(incomingBlock)) {
                Value updateValue = phi.getOperand(i);
                if (updateValue instanceof Instruction inst) {
                    if (inst instanceof BinaryInstruction binInst) {
                        // 检查是否是i+step或i-step形式
                        if (binInst.getOpCode() == OpCode.ADD || binInst.getOpCode() == OpCode.SUB) {
                            if (binInst.getLeft() == phi || binInst.getRight() == phi) {
                                return binInst;
                            }
                        }
                    }
                    // 递归向上查找
                    return inst;
                }
            }
        }
        return null;
    }
    
    /**
     * 获取归纳变量的步长值
     */
    private static Value getStepValue(BinaryInstruction binInst, Value indVar) {
        if (binInst.getOpCode() == OpCode.ADD) {
            // i + step
            if (binInst.getLeft() == indVar) {
                return binInst.getRight();
            }
            // step + i
            else if (binInst.getRight() == indVar) {
                return binInst.getLeft();
            }
        } else if (binInst.getOpCode() == OpCode.SUB) {
            // i - step
            if (binInst.getLeft() == indVar) {
                // 如果是常量，返回负值
                if (binInst.getRight() instanceof ConstantInt constInt) {
                    return new ConstantInt(-constInt.getValue());
                }
                return binInst.getRight();
            }
        }
        return null;
    }
    
    /**
     * 查找循环的结束条件
     */
    private static Value findEndCondition(Loop loop, Value indVar) {
        // 检查循环头的终结指令
        BasicBlock header = loop.getHeader();
        Instruction terminator = header.getTerminator();
        
        if (terminator instanceof BranchInstruction brInst && !brInst.isUnconditional()) {
            Value condition = brInst.getCondition();
            if (condition instanceof CompareInstruction cmpInst) {
                // 检查是否对归纳变量进行比较
                if (cmpInst.getLeft() == indVar) {
                    return cmpInst.getRight();
                } else if (cmpInst.getRight() == indVar) {
                    return cmpInst.getLeft();
                }
            }
        }
        
        // 如果在头块没找到，尝试查找循环内的其他分支
        for (BasicBlock block : loop.getBlocks()) {
            if (block != header) {
                Instruction blockTerminator = block.getTerminator();
                if (blockTerminator instanceof BranchInstruction brInst && !brInst.isUnconditional()) {
                    Value condition = brInst.getCondition();
                    if (condition instanceof CompareInstruction cmpInst) {
                        if (cmpInst.getLeft() == indVar) {
                            return cmpInst.getRight();
                        } else if (cmpInst.getRight() == indVar) {
                            return cmpInst.getLeft();
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 获取函数的顶层循环
     */
    public static List<Loop> getTopLevelLoops(Function function) {
        if (!functionLoops.containsKey(function)) {
            return analyzeLoops(function);
        }
        return functionLoops.get(function);
    }
    
    /**
     * 获取包含指定基本块的最内层循环
     */
    public static Loop getLoopFor(BasicBlock block) {
        return blockToLoop.get(block);
    }
    
    /**
     * 识别函数中的所有循环
     */
    private static List<Loop> identifyLoops(Function function) {
        List<Loop> loops = new ArrayList<>();
        blockToLoop.clear();
        
        // 寻找所有回边
        for (BasicBlock header : function.getBasicBlocks()) {
            if (header == null) {
                continue;
            }
            
            List<BasicBlock> predecessors = header.getPredecessors();
            if (predecessors == null) {
                continue;
            }
            
            for (BasicBlock latch : predecessors) {
                if (latch == null) {
                    continue;
                }
                
                // 如果latch被header支配，则存在回边
                try {
                    if (isDominated(latch, header)) {
                        // 创建或获取循环
                        Loop loop = findOrCreateLoop(header, loops);
                        
                        // 添加回边
                        loop.addLatchBlock(latch);
                        
                        // 收集循环体
                        collectLoopBlocks(loop, header, latch);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        
        // 计算每个循环的出口块
        for (Loop loop : loops) {
            try {
                loop.computeExitBlocks();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        return loops;
    }
    
    /**
     * 查找或创建循环
     */
    private static Loop findOrCreateLoop(BasicBlock header, List<Loop> loops) {
        // 查找是否已存在以该header为头的循环
        for (Loop loop : loops) {
            if (loop.getHeader() == header) {
                return loop;
            }
        }
        
        // 创建新循环
        Loop newLoop = new Loop(header);
        loops.add(newLoop);
        return newLoop;
    }
    
    /**
     * 收集循环体内的所有基本块
     */
    private static void collectLoopBlocks(Loop loop, BasicBlock header, BasicBlock latch) {
        Set<BasicBlock> loopBlocks = new HashSet<>();
        Stack<BasicBlock> workList = new Stack<>();
        
        // 添加header
        loopBlocks.add(header);
        loop.addBlock(header);
        
        // 从latch开始反向搜索
        if (latch != header) {
            workList.push(latch);
            loopBlocks.add(latch);
        }
        
        // 反向遍历，收集所有能到达header的块
        while (!workList.isEmpty()) {
            BasicBlock current = workList.pop();
            loop.addBlock(current);
            
            for (BasicBlock pred : current.getPredecessors()) {
                if (!loopBlocks.contains(pred)) {
                    loopBlocks.add(pred);
                    workList.push(pred);
                }
            }
        }
    }
    
    /**
     * 构建循环树（处理嵌套循环）
     */
    private static List<Loop> buildLoopTree(List<Loop> allLoops) {
        List<Loop> topLevelLoops = new ArrayList<>();
        
        // 首先将所有循环加入顶层
        topLevelLoops.addAll(allLoops);
        
        // 检查循环之间的包含关系
        for (Loop outerLoop : allLoops) {
            for (Loop innerLoop : allLoops) {
                if (outerLoop != innerLoop && isNestedLoop(innerLoop, outerLoop)) {
                    // innerLoop是outerLoop的子循环
                    outerLoop.addSubLoop(innerLoop);
                    topLevelLoops.remove(innerLoop);
                }
            }
        }
        
        // 更新blockToLoop映射，优先记录最内层循环
        for (Loop loop : allLoops) {
            for (BasicBlock block : loop.getBlocks()) {
                Loop existingLoop = blockToLoop.get(block);
                if (existingLoop == null || loop.getDepth() > existingLoop.getDepth()) {
                    blockToLoop.put(block, loop);
                }
            }
        }
        
        return topLevelLoops;
    }
    
    /**
     * 检查inner是否是outer的嵌套循环
     */
    private static boolean isNestedLoop(Loop inner, Loop outer) {
        // 如果inner的所有块都在outer中，则inner是outer的子循环
        for (BasicBlock block : inner.getBlocks()) {
            if (!outer.contains(block)) {
                return false;
            }
        }
        // 还要确保不是同一个循环
        return !inner.getHeader().equals(outer.getHeader());
    }
    
    /**
     * 更新基本块的循环深度
     */
    private static void updateLoopDepths(List<Loop> loops) {
        // 先重置所有块的循环深度
        for (Loop loop : loops) {
            for (BasicBlock block : loop.getBlocks()) {
                block.setLoopDepth(0);
            }
        }
        
        // 根据循环嵌套更新深度
        for (Loop loop : loops) {
            for (BasicBlock block : loop.getBlocks()) {
                block.setLoopDepth(Math.max(block.getLoopDepth(), loop.getDepth() + 1));
            }
        }
    }
    
    /**
     * 判断block是否被header支配
     */
    private static boolean isDominated(BasicBlock block, BasicBlock header) {
        // 递归深度检测，避免无限递归
        return isDominatedWithDepth(block, header, 0);
    }
    
    /**
     * 带深度检查的支配关系判断
     */
    private static boolean isDominatedWithDepth(BasicBlock block, BasicBlock header, int depth) {
        // 防止堆栈溢出
        if (depth > 1000) {
            return false;
        }
        
        // 当前简化实现：检查header是否是block的支配者
        // 如果block是header本身，则被支配
        if (block == header) {
            return true;
        }
        
        // 如果block的直接支配者是header，则被支配
        BasicBlock idom = block.getIdominator();
        if (idom == header) {
            return true;
        }
        
        // 检查支配者链是否形成循环
        if (idom == block) {
            return false;
        }
        
        // 递归检查block的直接支配者是否被header支配
        if (idom != null) {
            return isDominatedWithDepth(idom, header, depth + 1);
        }
        
        return false;
    }
    
    /**
     * 获取循环的深度优先遍历顺序
     */
    public static List<Loop> getLoopsInDFSOrder(Loop loop) {
        List<Loop> result = new ArrayList<>();
        dfsTraverseLoops(loop, result);
        return result;
    }
    
    /**
     * 深度优先遍历循环树
     */
    private static void dfsTraverseLoops(Loop loop, List<Loop> result) {
        // 先访问子循环
        for (Loop subLoop : loop.getSubLoops()) {
            dfsTraverseLoops(subLoop, result);
        }
        // 再访问当前循环
        result.add(loop);
    }
    
    /**
     * 获取函数中所有循环的DFS顺序
     */
    public static List<Loop> getAllLoopsInDFSOrder(Function function) {
        List<Loop> topLoops = getTopLevelLoops(function);
        List<Loop> allLoops = new ArrayList<>();
        
        for (Loop topLoop : topLoops) {
            allLoops.addAll(getLoopsInDFSOrder(topLoop));
        }
        
        return allLoops;
    }
    
    /**
     * 为函数执行完整的循环分析，包括归纳变量分析
     */
    public static void runLoopIndVarInfo(Function function) {
        analyzeLoops(function);
        analyzeInductionVariables(function);
    }
} 
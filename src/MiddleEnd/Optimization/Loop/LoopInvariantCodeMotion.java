package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.*;

/**
 * 循环不变代码移动优化（Loop Invariant Code Motion）
 * 将循环内的不变计算移动到循环外，减少重复计算
 */
public class LoopInvariantCodeMotion implements Optimizer.ModuleOptimizer {

    // 调试模式
    private final boolean debug = false;
    
    // 基本块数量阈值
    private final int MAX_BLOCKS_THRESHOLD = 1000;
    
    // 统计信息
    private int totalMovedInstructions = 0;
    
    @Override
    public String getName() {
        return "LoopInvariantCodeMotion";
    }

    @Override
    public boolean run(Module module) {
        boolean modified = false;
        totalMovedInstructions = 0;
        
        if (debug) {
            System.out.println("[LICM] Starting Loop Invariant Code Motion optimization");
        }
        
        // 对每个函数执行LICM
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            // 检查函数复杂度
            if (function.getBasicBlocks().size() > MAX_BLOCKS_THRESHOLD) {
                if (debug) {
                    System.out.println("[LICM] Skipping function " + function.getName() + 
                                     " - too many blocks (" + function.getBasicBlocks().size() + ")");
                }
                continue;
            }
            
            if (debug) {
                System.out.println("[LICM] Processing function: " + function.getName());
            }
            
            // 分析循环
            List<Loop> allLoops = LoopAnalysis.getAllLoopsInDFSOrder(function);
            
            if (debug) {
                System.out.println("[LICM] Found " + allLoops.size() + " loops");
            }
            
            // 对每个循环执行LICM（从内层循环开始）
            for (Loop loop : allLoops) {
                boolean loopModified = processLoop(loop);
                modified |= loopModified;
            }
            
            // 验证函数中所有基本块的完整性
            validateFunction(function);
        }
        
        if (debug) {
            System.out.println("[LICM] Optimization completed. Total moved instructions: " + totalMovedInstructions);
        }
        
        return modified;
    }
    
    /**
     * 处理单个循环
     */
    private boolean processLoop(Loop loop) {
        if (debug) {
            System.out.println("[LICM] Processing loop with header: " + loop.getHeader());
        }
        
        // 获取循环的前置头块
        BasicBlock preheader = getOrCreatePreheader(loop);
        if (preheader == null) {
            if (debug) {
                System.out.println("[LICM] Cannot create preheader for loop");
            }
            return false;
        }
        
        // 识别循环不变量
        Set<Instruction> invariants = identifyLoopInvariants(loop);
        
        if (debug) {
            System.out.println("[LICM] Found " + invariants.size() + " loop invariant instructions");
        }
        
        // 移动安全的循环不变量
        boolean modified = false;
        for (Instruction inst : invariants) {
            if (canSafelyMove(inst, loop)) {
                moveToPreheader(inst, preheader);
                totalMovedInstructions++;
                modified = true;
                
                if (debug) {
                    System.out.println("[LICM] Moved instruction: " + inst);
                }
            } else if (debug) {
                System.out.println("[LICM] Cannot safely move instruction: " + inst);
            }
        }
        
        return modified;
    }
    
    /**
     * 识别循环不变量
     */
    private Set<Instruction> identifyLoopInvariants(Loop loop) {
        Set<Instruction> invariants = new HashSet<>();
        Set<Instruction> processed = new HashSet<>();
        boolean changed = true;
        
        // 迭代识别循环不变量
        while (changed) {
            changed = false;
            
            for (BasicBlock block : loop.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (!processed.contains(inst) && isLoopInvariant(inst, loop, invariants)) {
                        invariants.add(inst);
                        processed.add(inst);
                        changed = true;
                    }
                }
            }
        }
        
        return invariants;
    }
    
    /**
     * 判断指令是否是循环不变量
     */
    private boolean isLoopInvariant(Instruction inst, Loop loop, Set<Instruction> knownInvariants) {
        // 某些指令类型不能作为循环不变量
        if (!canBeInvariant(inst)) {
            return false;
        }
        
        // 指令必须在循环内
        if (!loop.contains(inst)) {
            return false;
        }
        
        // 检查所有操作数
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            
            // 常量是循环不变的
            if (operand instanceof Constant) {
                continue;
            }
            
            // 如果操作数是指令
            if (operand instanceof Instruction operandInst) {
                // 在循环外定义的是循环不变的
                if (!loop.contains(operandInst)) {
                    continue;
                }
                
                // 已知的循环不变量
                if (knownInvariants.contains(operandInst)) {
                    continue;
                }
                
                // 否则不是循环不变量
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 判断指令类型是否可以作为循环不变量
     */
    private boolean canBeInvariant(Instruction inst) {
        // 以下指令类型不能移动
        if (inst instanceof BranchInstruction ||
            inst instanceof PhiInstruction ||
            inst instanceof ReturnInstruction ||
            inst instanceof StoreInstruction ||  // 内存写操作
            inst instanceof LoadInstruction ||   // 内存读操作
            inst instanceof CallInstruction ||   // 函数调用可能有副作用
            inst instanceof AllocaInstruction) { // 栈分配
            return false;
        }
        
        // 其他计算指令可以作为循环不变量
        return true;
    }
    
    /**
     * 判断是否可以安全地移动指令
     */
    private boolean canSafelyMove(Instruction inst, Loop loop) {
        // 检查指令是否支配所有使用点
        for (User user : inst.getUsers()) {
            if (user instanceof Instruction userInst) {
                // 如果使用者在循环内，不能移动
                if (loop.contains(userInst)) {
                    // 除非使用者也是循环不变量（会被一起移动）
                    // 这里简化处理，不移动
                    return false;
                }
            }
        }
        
        // 检查指令是否在所有出口路径上执行
        // 简化处理：只移动在循环头部的指令
        if (inst.getParent() != loop.getHeader()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取或创建循环的前置头块
     */
    private BasicBlock getOrCreatePreheader(Loop loop) {
        BasicBlock preheader = loop.getPreheader();
        
        if (preheader != null) {
            return preheader;
        }
        
        // 尝试创建前置头块（简化版本）
        BasicBlock header = loop.getHeader();
        Function function = header.getParentFunction();
        
        // 收集非回边前驱
        List<BasicBlock> nonLatchPreds = new ArrayList<>();
        for (BasicBlock pred : header.getPredecessors()) {
            if (!loop.getLatchBlocks().contains(pred)) {
                nonLatchPreds.add(pred);
            }
        }
        
        // 如果没有非回边前驱，无法创建前置头
        if (nonLatchPreds.isEmpty()) {
            return null;
        }
        
        // 如果只有一个非回边前驱且其只有一个后继，可以直接使用它作为前置头
        if (nonLatchPreds.size() == 1) {
            BasicBlock singlePred = nonLatchPreds.get(0);
            if (singlePred.getSuccessors().size() == 1 && singlePred.getSuccessors().contains(header)) {
                return singlePred;
            }
        }
        
        // 对于多个前驱的情况，暂时跳过以确保安全性
        if (nonLatchPreds.size() > 1) {
            if (debug) {
                System.out.println("[LICM] Multiple non-latch predecessors, skipping preheader creation for: " + header.getName());
            }
            return null;
        }
        
        return null;
    }
    

    
    /**
     * 将指令移动到前置头块
     */
    private void moveToPreheader(Instruction inst, BasicBlock preheader) {
        // 从原块中移除
        inst.removeFromParent();
        
        // 插入到前置头块的终结指令之前
        inst.insertBefore(preheader.getTerminator());
    }
    
    /**
     * 验证函数中所有基本块的完整性
     */
    private void validateFunction(Function function) {
        for (BasicBlock block : function.getBasicBlocks()) {
            // 检查每个基本块是否有终结指令
            if (block.getInstructions().isEmpty()) {
                if (debug) {
                    System.out.println("[LICM] Warning: Empty basic block found: " + block.getName());
                }
                // 为空基本块添加一个返回指令（这是保护性措施）
                // 实际上这种情况不应该发生
                continue;
            }
            
            Instruction lastInst = block.getLastInstruction();
            if (lastInst == null) {
                if (debug) {
                    System.out.println("[LICM] Warning: Basic block without last instruction: " + block.getName());
                }
            } else if (!(lastInst instanceof TerminatorInstruction)) {
                if (debug) {
                    System.out.println("[LICM] Warning: Basic block without terminator: " + block.getName());
                    System.out.println("[LICM] Last instruction: " + lastInst);
                }
            }
        }
    }
} 
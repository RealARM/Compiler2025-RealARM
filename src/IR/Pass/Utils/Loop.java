package IR.Pass.Utils;

import IR.Value.BasicBlock;
import IR.Value.Instructions.Instruction;
import IR.Value.Value;
import IR.Value.Constant;

import java.util.*;

/**
 * 表示一个循环的数据结构
 */
public class Loop {
    // 循环头（入口基本块）
    private final BasicBlock header;
    
    // 循环体内的所有基本块
    private final Set<BasicBlock> blocks;
    
    // 子循环
    private final List<Loop> subLoops;
    
    // 父循环
    private Loop parentLoop;
    
    // 循环的回边
    private final List<BasicBlock> latchBlocks;
    
    // 循环出口块（循环外的后继块）
    private final Set<BasicBlock> exitBlocks;
    
    // 循环深度
    private int depth;
    
    /**
     * 构造函数
     * @param header 循环头基本块
     */
    public Loop(BasicBlock header) {
        this.header = header;
        this.blocks = new HashSet<>();
        this.subLoops = new ArrayList<>();
        this.latchBlocks = new ArrayList<>();
        this.exitBlocks = new HashSet<>();
        this.parentLoop = null;
        this.depth = 0;
    }
    
    /**
     * 添加基本块到循环
     */
    public void addBlock(BasicBlock block) {
        blocks.add(block);
    }
    
    /**
     * 添加子循环
     */
    public void addSubLoop(Loop subLoop) {
        subLoops.add(subLoop);
        subLoop.parentLoop = this;
        subLoop.updateDepth();
    }
    
    /**
     * 添加回边块
     */
    public void addLatchBlock(BasicBlock latch) {
        if (!latchBlocks.contains(latch)) {
            latchBlocks.add(latch);
        }
    }
    
    /**
     * 计算循环出口块
     */
    public void computeExitBlocks() {
        exitBlocks.clear();
        for (BasicBlock block : blocks) {
            for (BasicBlock succ : block.getSuccessors()) {
                if (!blocks.contains(succ)) {
                    exitBlocks.add(succ);
                }
            }
        }
    }
    
    /**
     * 更新循环深度
     */
    private void updateDepth() {
        if (parentLoop != null) {
            depth = parentLoop.depth + 1;
        }
        for (Loop subLoop : subLoops) {
            subLoop.updateDepth();
        }
    }
    
    /**
     * 判断基本块是否在循环内
     */
    public boolean contains(BasicBlock block) {
        return blocks.contains(block);
    }
    
    /**
     * 判断指令是否在循环内
     */
    public boolean contains(Instruction inst) {
        return inst.getParent() != null && contains(inst.getParent());
    }
    
    /**
     * 判断是否是循环不变量
     * 循环不变量的条件：
     * 1. 指令的所有操作数都是循环外定义的
     * 2. 或者是常量
     * 3. 或者是已经被识别为循环不变量的指令
     */
    public boolean isLoopInvariant(Instruction inst, Set<Instruction> invariants) {
        // 指令必须在循环内
        if (!contains(inst)) {
            return false;
        }
        
        // 检查所有操作数
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            
            // 如果是常量，继续检查下一个操作数
            if (operand instanceof Constant) {
                continue;
            }
            
            // 如果是指令
            if (operand instanceof Instruction operandInst) {
                // 如果在循环外定义，是循环不变的
                if (!contains(operandInst)) {
                    continue;
                }
                
                // 如果已经被识别为循环不变量
                if (invariants.contains(operandInst)) {
                    continue;
                }
                
                // 否则不是循环不变量
                return false;
            }
        }
        
        return true;
    }
    
    // Getter方法
    public BasicBlock getHeader() {
        return header;
    }
    
    public Set<BasicBlock> getBlocks() {
        return blocks;
    }
    
    public List<Loop> getSubLoops() {
        return subLoops;
    }
    
    public Loop getParentLoop() {
        return parentLoop;
    }
    
    public List<BasicBlock> getLatchBlocks() {
        return latchBlocks;
    }
    
    public Set<BasicBlock> getExitBlocks() {
        return exitBlocks;
    }
    
    public int getDepth() {
        return depth;
    }
    
    /**
     * 获取循环的前置头块（如果存在）
     * 前置头块是循环头的唯一前驱（不包括回边）
     */
    public BasicBlock getPreheader() {
        List<BasicBlock> nonLatchPreds = new ArrayList<>();
        
        for (BasicBlock pred : header.getPredecessors()) {
            if (!latchBlocks.contains(pred)) {
                nonLatchPreds.add(pred);
            }
        }
        
        // 如果只有一个非回边前驱，它就是前置头
        if (nonLatchPreds.size() == 1) {
            return nonLatchPreds.get(0);
        }
        
        return null;
    }
    
    @Override
    public String toString() {
        return "Loop[header=" + header + ", blocks=" + blocks.size() + ", depth=" + depth + "]";
    }
} 
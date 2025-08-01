package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.Constant;

import java.util.*;

/**
 * 表示一个循环的数据结构
 */
public class Loop {
    private final BasicBlock header;
    
    private final Set<BasicBlock> blocks;
    
    private final List<Loop> subLoops;
    
    private Loop parentLoop;
    
    private final List<BasicBlock> latchBlocks;
    
    private final Set<BasicBlock> exitBlocks;
    
    private int depth;
    
    private Value inductionVariable;
    
    private Value initValue;
    
    private Value endValue;
    
    private Value stepValue;
    
    private Instruction updateInstruction;
    
    private boolean hasIndVar;
    
    public Loop(BasicBlock header) {
        this.header = header;
        this.blocks = new HashSet<>();
        this.subLoops = new ArrayList<>();
        this.latchBlocks = new ArrayList<>();
        this.exitBlocks = new HashSet<>();
        this.parentLoop = null;
        this.depth = 0;
        this.hasIndVar = false;
    }
    
    public void addBlock(BasicBlock block) {
        blocks.add(block);
    }
    
    public void addSubLoop(Loop subLoop) {
        subLoops.add(subLoop);
        subLoop.parentLoop = this;
        subLoop.updateDepth();
    }
    
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
    
    private void updateDepth() {
        if (parentLoop != null) {
            depth = parentLoop.depth + 1;
        }
        for (Loop subLoop : subLoops) {
            subLoop.updateDepth();
        }
    }
    
    public boolean contains(BasicBlock block) {
        return blocks.contains(block);
    }
    
    public boolean contains(Instruction inst) {
        return inst.getParent() != null && contains(inst.getParent());
    }
    
    public boolean isLoopInvariant(Instruction inst, Set<Instruction> invariants) {
        if (!contains(inst)) {
            return false;
        }
        
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            
            if (operand instanceof Constant) {
                continue;
            }
            
            if (operand instanceof Instruction operandInst) {
                if (!contains(operandInst)) {
                    continue;
                }
                
                if (invariants.contains(operandInst)) {
                    continue;
                }
                
                return false;
            }
        }
        
        return true;
    }
    
    public void setInductionVariableInfo(Value indVar, Value init, Value end, Value step, Instruction update) {
        this.inductionVariable = indVar;
        this.initValue = init;
        this.endValue = end;
        this.stepValue = step;
        this.updateInstruction = update;
        this.hasIndVar = true;
    }
    
    public boolean hasInductionVariable() {
        return hasIndVar;
    }
    
    public Value getInductionVariable() {
        return inductionVariable;
    }
    
    public Value getInitValue() {
        return initValue;
    }
    
    public Value getEndValue() {
        return endValue;
    }
    
    public Value getStepValue() {
        return stepValue;
    }
    
    public Instruction getUpdateInstruction() {
        return updateInstruction;
    }
    
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
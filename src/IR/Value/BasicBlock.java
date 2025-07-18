package IR.Value;

import IR.Type.LabelType;
import IR.Value.Instructions.Instruction;
import IR.Value.Instructions.PhiInstruction;
import IR.Value.Instructions.TerminatorInstruction;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 表示基本块，是IR中的代码容器
 */
public class BasicBlock extends Value {
    private final LinkedList<Instruction> instructions = new LinkedList<>(); // 指令列表
    private final List<BasicBlock> predecessors = new ArrayList<>();        // 前驱基本块
    private final List<BasicBlock> successors = new ArrayList<>();          // 后继基本块
    private final Function parentFunction;                                 // 所属函数
    
    private static int blockCounter = 0; // 用于生成唯一的块标签
    
    /**
     * 创建一个基本块
     */
    public BasicBlock(String name, Function function) {
        super(name, LabelType.LABEL);
        this.parentFunction = function;
        function.addBasicBlock(this);
    }
    
    /**
     * 创建一个带自动生成名称的基本块
     */
    public BasicBlock(Function function) {
        this("block" + blockCounter++, function);
    }
    
    /**
     * 获取所属函数
     */
    public Function getParentFunction() {
        return parentFunction;
    }
    
    /**
     * 添加指令
     */
    public void addInstruction(Instruction instruction) {
        instructions.add(instruction);
        instruction.setParent(this);
    }
    
    /**
     * 在开头添加指令
     */
    public void addInstructionFirst(Instruction instruction) {
        instructions.addFirst(instruction);
        instruction.setParent(this);
    }
    
    /**
     * 在指令前添加指令
     */
    public void addInstructionBefore(Instruction newInst, Instruction before) {
        int index = instructions.indexOf(before);
        if (index != -1) {
            instructions.add(index, newInst);
            newInst.setParent(this);
        } else {
            addInstruction(newInst);
        }
    }
    
    /**
     * 移除指令
     */
    public void removeInstruction(Instruction instruction) {
        instructions.remove(instruction);
        instruction.setParent(null);
    }
    
    /**
     * 获取指令列表
     */
    public List<Instruction> getInstructions() {
        return instructions;
    }
    
    /**
     * 获取PHI指令列表
     */
    public List<PhiInstruction> getPhiInstructions() {
        List<PhiInstruction> phiInsts = new ArrayList<>();
        for (Instruction inst : instructions) {
            if (inst instanceof PhiInstruction) {
                phiInsts.add((PhiInstruction) inst);
            } else {
                break; // Phi指令必须在基本块开头
            }
        }
        return phiInsts;
    }
    
    /**
     * 获取第一条指令
     */
    public Instruction getFirstInstruction() {
        if (instructions.isEmpty()) {
            return null;
        }
        return instructions.getFirst();
    }
    
    /**
     * 获取最后一条指令
     */
    public Instruction getLastInstruction() {
        if (instructions.isEmpty()) {
            return null;
        }
        return instructions.getLast();
    }
    
    /**
     * 获取终结指令
     */
    public TerminatorInstruction getTerminator() {
        Instruction last = getLastInstruction();
        if (last instanceof TerminatorInstruction) {
            return (TerminatorInstruction) last;
        }
        return null;
    }
    
    /**
     * 添加前驱基本块
     */
    public void addPredecessor(BasicBlock block) {
        if (!predecessors.contains(block)) {
            predecessors.add(block);
        }
    }
    
    /**
     * 移除前驱基本块
     */
    public void removePredecessor(BasicBlock block) {
        predecessors.remove(block);
    }
    
    /**
     * 获取前驱基本块列表
     */
    public List<BasicBlock> getPredecessors() {
        return predecessors;
    }
    
    /**
     * 添加后继基本块
     */
    public void addSuccessor(BasicBlock block) {
        if (!successors.contains(block)) {
            successors.add(block);
            block.addPredecessor(this);
        }
    }
    
    /**
     * 移除后继基本块
     */
    public void removeSuccessor(BasicBlock block) {
        if (successors.remove(block)) {
            block.removePredecessor(this);
        }
    }
    
    /**
     * 获取后继基本块列表
     */
    public List<BasicBlock> getSuccessors() {
        return successors;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(":\n");
        
        for (Instruction inst : instructions) {
            sb.append("  ").append(inst).append("\n");
        }
        
        return sb.toString();
    }
} 
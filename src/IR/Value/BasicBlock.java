package IR.Value;

import IR.Type.LabelType;
import IR.Type.IntegerType;
import IR.Value.Instructions.BranchInstruction;
import IR.Value.Instructions.Instruction;
import IR.Value.Instructions.PhiInstruction;
import IR.Value.Instructions.TerminatorInstruction;
import IR.Value.ConstantInt;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 表示基本块，是IR中的代码容器
 */
public class BasicBlock extends Value {
    private final LinkedList<Instruction> instructions = new LinkedList<>(); // 指令列表
    private List<BasicBlock> predecessors = new ArrayList<>(); // 前驱基本块
    private List<BasicBlock> successors = new ArrayList<>();   // 后继基本块
    private final Function parentFunction;                     // 所属函数
    
    private static int blockCounter = 0; // 用于生成唯一的块标签
    private int loopDepth = 0;           // 所在循环的深度
    
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
    public Instruction getTerminator() {
        Instruction last = getLastInstruction();
        if (last instanceof TerminatorInstruction) {
            return last;
        }
        return null;
    }
    
    /**
     * 添加前驱基本块
     */
    public void addPredecessor(BasicBlock block) {
        if (!predecessors.contains(block)) {
            predecessors.add(block);
            
            // 确保相互关系正确
            if (!block.getSuccessors().contains(this)) {
                block.getSuccessors().add(this);
            }
            
            // 更新phi指令
            for (PhiInstruction phi : getPhiInstructions()) {
                // 如果PHI节点还没有这个前驱的输入，添加一个默认值
                if (!phi.getIncomingBlocks().contains(block)) {
                    Value defaultValue = phi.getType().toString().equals("i1") ? 
                        new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                    phi.addIncoming(defaultValue, block);
                }
            }
        }
    }
    
    /**
     * 移除前驱基本块
     */
    public void removePredecessor(BasicBlock block) {
        int index = predecessors.indexOf(block);
        if (index != -1) {
            predecessors.remove(index);
            
            // 确保相互关系正确
            if (block.getSuccessors().contains(this)) {
                block.getSuccessors().remove(this);
            }
            
            // 更新phi指令
            for (PhiInstruction phi : getPhiInstructions()) {
                // 如果PHI节点有这个前驱的输入，移除它
                if (phi.getIncomingBlocks().contains(block)) {
                    // 创建新的输入映射，排除要移除的前驱
                    Map<BasicBlock, Value> newIncomings = new HashMap<>();
                    for (Map.Entry<BasicBlock, Value> entry : phi.getIncomingValues().entrySet()) {
                        if (!entry.getKey().equals(block)) {
                            newIncomings.put(entry.getKey(), entry.getValue());
                        }
                    }
                    
                    // 清空PHI节点并重新添加输入
                    phi.removeAllOperands();
                    phi.getIncomingValues().clear();
                    for (Map.Entry<BasicBlock, Value> entry : newIncomings.entrySet()) {
                        phi.addIncoming(entry.getValue(), entry.getKey());
                    }
                }
            }
        }
    }
    
    /**
     * 获取前驱基本块列表
     */
    public List<BasicBlock> getPredecessors() {
        // 确保前驱列表与实际情况匹配
        validatePredecessors();
        return predecessors;
    }
    
    /**
     * 验证并修复前驱列表，确保与终结指令的后继一致
     */
    private void validatePredecessors() {
        // 这个方法会根据终结指令信息重建前驱列表
        List<BasicBlock> realPredecessors = new ArrayList<>();
        for (BasicBlock potentialPred : getParentFunction().getBasicBlocks()) {
            if (potentialPred.getSuccessors().contains(this)) {
                if (!realPredecessors.contains(potentialPred)) {
                    realPredecessors.add(potentialPred);
                }
            }
        }
        
        // 检查是否需要更新前驱列表
        if (!predecessors.containsAll(realPredecessors) || !realPredecessors.containsAll(predecessors)) {
            List<BasicBlock> oldPreds = new ArrayList<>(this.predecessors);
            this.predecessors = new ArrayList<>(realPredecessors);
            
            // 更新所有PHI指令
            for (PhiInstruction phi : getPhiInstructions()) {
                phi.updatePredecessors(oldPreds, this.predecessors);
            }
        }
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
    
    /**
     * 设置后继基本块列表
     */
    public void setSuccessors(List<BasicBlock> successors) {
        // 移除原有的后继关系
        for (BasicBlock succ : this.successors) {
            succ.removePredecessor(this);
        }
        
        // 设置新的后继列表
        this.successors = successors;
        
        // 建立新的后继关系
        for (BasicBlock succ : successors) {
            succ.addPredecessor(this);
        }
    }
    
    /**
     * 判断是否为空基本块（没有指令）
     */
    public boolean isEmpty() {
        return instructions.isEmpty();
    }
    
    /**
     * 判断该基本块是否有终结指令
     */
    public boolean hasTerminator() {
        if (instructions.isEmpty()) {
            return false;
        }
        
        Instruction lastInst = instructions.getLast();
        return lastInst instanceof TerminatorInstruction;
    }
    
    /**
     * 更新分支指令中的目标基本块
     */
    public void updateBranchTarget(BasicBlock oldTarget, BasicBlock newTarget) {
        Instruction lastInst = getLastInstruction();
        if (lastInst instanceof BranchInstruction br) {
            if (br.isUnconditional()) {
                if (br.getTrueBlock() == oldTarget) {
                    // 更新无条件分支的目标
                    br = new BranchInstruction(newTarget);
                    instructions.removeLast();
                    addInstruction(br);
                    
                    // 更新后继关系
                    removeSuccessor(oldTarget);
                    addSuccessor(newTarget);
                }
            } else {
                // 条件分支
                BasicBlock trueBlock = br.getTrueBlock();
                BasicBlock falseBlock = br.getFalseBlock();
                boolean changed = false;
                
                if (trueBlock == oldTarget) {
                    trueBlock = newTarget;
                    changed = true;
                }
                
                if (falseBlock == oldTarget) {
                    falseBlock = newTarget;
                    changed = true;
                }
                
                if (changed) {
                    // 创建新的分支指令
                    br = new BranchInstruction(br.getCondition(), trueBlock, falseBlock);
                    instructions.removeLast();
                    addInstruction(br);
                    
                    // 更新后继关系
                    removeSuccessor(oldTarget);
                    addSuccessor(newTarget);
                }
            }
        }
    }
    
    /**
     * 获取循环深度
     */
    public int getLoopDepth() {
        return loopDepth;
    }
    
    /**
     * 设置循环深度
     */
    public void setLoopDepth(int depth) {
        this.loopDepth = depth;
    }
    
    /**
     * 从函数中移除此基本块
     */
    public void removeFromParent() {
        // 从所有前驱的后继列表中移除自己
        for (BasicBlock pred : new ArrayList<>(predecessors)) {
            pred.removeSuccessor(this);
        }
        
        // 从所有后继的前驱列表中移除自己
        for (BasicBlock succ : new ArrayList<>(successors)) {
            succ.removePredecessor(this);
        }
        
        // 移除所有指令
        for (Instruction inst : new ArrayList<>(instructions)) {
            inst.removeFromParent();
        }
        
        // 从父函数中移除自己
        parentFunction.removeBasicBlock(this);
    }
    
    @Override
    public String toString() {
        return getName() + ":";
    }
} 
package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.LabelType;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.Value.Instructions.BranchInstruction;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.IR.Value.Instructions.TerminatorInstruction;


import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class BasicBlock extends Value {
    private final LinkedList<Instruction> instructions = new LinkedList<>();
    private List<BasicBlock> predecessors = new ArrayList<>();
    private List<BasicBlock> successors = new ArrayList<>();
    private final Function parentFunction;
    
    private static int blockCounter = 0;
    private int loopDepth = 0;           // 所在循环的深度
    private int domLevel = 0;            // 支配树中的深度级别
    private BasicBlock idominator = null; // 直接支配者
    
    public BasicBlock(String name, Function function) {
        super(name, LabelType.LABEL);
        this.parentFunction = function;
        function.addBasicBlock(this);
    }
    
    public BasicBlock(Function function) {
        this("block" + blockCounter++, function);
    }
    
    public Function getParentFunction() {
        return parentFunction;
    }
    
    public void addInstruction(Instruction instruction) {
        instructions.add(instruction);
        instruction.setParent(this);
    }
    
    public void addInstructionFirst(Instruction instruction) {
        instructions.addFirst(instruction);
        instruction.setParent(this);
    }
    
    public void addInstructionBefore(Instruction newInst, Instruction before) {
        int index = instructions.indexOf(before);
        if (index != -1) {
            instructions.add(index, newInst);
            newInst.setParent(this);
        } else {
            addInstruction(newInst);
        }
    }
    
    public void removeInstruction(Instruction instruction) {
        instructions.remove(instruction);
        instruction.setParent(null);
    }
    
    public List<Instruction> getInstructions() {
        return instructions;
    }
    
    public List<PhiInstruction> getPhiInstructions() {
        List<PhiInstruction> phiInsts = new ArrayList<>();
        for (Instruction inst : instructions) {
            if (inst instanceof PhiInstruction) {
                phiInsts.add((PhiInstruction) inst);
            } else {
                break;
            }
        }
        return phiInsts;
    }
    
    public Instruction getFirstInstruction() {
        if (instructions.isEmpty()) {
            return null;
        }
        return instructions.getFirst();
    }
    
    public Instruction getLastInstruction() {
        if (instructions.isEmpty()) {
            return null;
        }
        return instructions.getLast();
    }
    
    public Instruction getTerminator() {
        Instruction last = getLastInstruction();
        if (last instanceof TerminatorInstruction) {
            return last;
        }
        return null;
    }
    
    public void addPredecessor(BasicBlock block) {
        if (!predecessors.contains(block)) {
            predecessors.add(block);
            
            if (!block.getSuccessors().contains(this)) {
                block.getSuccessors().add(this);
            }
            
            for (PhiInstruction phi : getPhiInstructions()) {
                if (!phi.getIncomingBlocks().contains(block)) {
                    Value defaultValue = phi.getType().toString().equals("i1") ? 
                        new ConstantInt(0, IntegerType.I1) : new ConstantInt(0);
                    phi.addIncoming(defaultValue, block);
                }
            }
        }
    }
    
    public void removePredecessor(BasicBlock block) {
        int index = predecessors.indexOf(block);
        if (index != -1) {
            predecessors.remove(index);
            
            if (block.getSuccessors().contains(this)) {
                block.getSuccessors().remove(this);
            }
            
            for (PhiInstruction phi : getPhiInstructions()) {
                if (phi.getIncomingBlocks().contains(block)) {
                    Map<BasicBlock, Value> newIncomings = new HashMap<>();
                    for (Map.Entry<BasicBlock, Value> entry : phi.getIncomingValues().entrySet()) {
                        if (!entry.getKey().equals(block)) {
                            newIncomings.put(entry.getKey(), entry.getValue());
                        }
                    }
                    
                    phi.removeAllOperands();
                    phi.getIncomingValues().clear();
                    for (Map.Entry<BasicBlock, Value> entry : newIncomings.entrySet()) {
                        phi.addIncoming(entry.getValue(), entry.getKey());
                    }
                }
            }
        }
    }
    
    public List<BasicBlock> getPredecessors() {
        validatePredecessors();
        return predecessors;
    }
    
    private void validatePredecessors() {
        List<BasicBlock> realPredecessors = new ArrayList<>();
        for (BasicBlock potentialPred : getParentFunction().getBasicBlocks()) {
            if (potentialPred.getSuccessors().contains(this)) {
                if (!realPredecessors.contains(potentialPred)) {
                    realPredecessors.add(potentialPred);
                }
            }
        }
        
        if (!predecessors.containsAll(realPredecessors) || !realPredecessors.containsAll(predecessors)) {
            List<BasicBlock> oldPreds = new ArrayList<>(this.predecessors);
            this.predecessors = new ArrayList<>(realPredecessors);
            
            for (PhiInstruction phi : getPhiInstructions()) {
                phi.updatePredecessors(oldPreds, this.predecessors);
            }
        }
    }
    
    public void addSuccessor(BasicBlock block) {
        if (!successors.contains(block)) {
            successors.add(block);
            block.addPredecessor(this);
        }
    }
    
    public void removeSuccessor(BasicBlock block) {
        if (successors.remove(block)) {
            block.removePredecessor(this);
        }
    }
    
    public List<BasicBlock> getSuccessors() {
        return successors;
    }
    
    public void setSuccessors(List<BasicBlock> successors) {
        for (BasicBlock succ : this.successors) {
            succ.removePredecessor(this);
        }
        
        this.successors = successors;
        
        for (BasicBlock succ : successors) {
            succ.addPredecessor(this);
        }
    }
    
    public boolean isEmpty() {
        return instructions.isEmpty();
    }
    
    public boolean hasTerminator() {
        if (instructions.isEmpty()) {
            return false;
        }
        
        Instruction lastInst = instructions.getLast();
        return lastInst instanceof TerminatorInstruction;
    }
    
    public void updateBranchTarget(BasicBlock oldTarget, BasicBlock newTarget) {
        Instruction lastInst = getLastInstruction();
        if (lastInst instanceof BranchInstruction br) {
            if (br.isUnconditional()) {
                if (br.getTrueBlock() == oldTarget) {
                    br = new BranchInstruction(newTarget);
                    instructions.removeLast();
                    addInstruction(br);
                    
                    removeSuccessor(oldTarget);
                    addSuccessor(newTarget);
                }
            } else {
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
                    br = new BranchInstruction(br.getCondition(), trueBlock, falseBlock);
                    instructions.removeLast();
                    addInstruction(br);
                    
                    removeSuccessor(oldTarget);
                    addSuccessor(newTarget);
                }
            }
        }
    }
    
    public int getLoopDepth() {
        return loopDepth;
    }
    
    public void setLoopDepth(int depth) {
        this.loopDepth = depth;
    }
    
    public int getDomLevel() {
        return domLevel;
    }
    
    public void setDomLevel(int level) {
        this.domLevel = level;
    }
    
    public BasicBlock getIdominator() {
        return idominator;
    }
    
    public void setIdominator(BasicBlock idom) {
        this.idominator = idom;
    }
    
    public void removeFromParent() {
        for (BasicBlock pred : new ArrayList<>(predecessors)) {
            pred.removeSuccessor(this);
        }
        
        for (BasicBlock succ : new ArrayList<>(successors)) {
            succ.removePredecessor(this);
        }
        
        for (Instruction inst : new ArrayList<>(instructions)) {
            inst.removeFromParent();
        }
        
        parentFunction.removeBasicBlock(this);
    }
    
    @Override
    public String toString() {
        return getName() + ":";
    }
} 
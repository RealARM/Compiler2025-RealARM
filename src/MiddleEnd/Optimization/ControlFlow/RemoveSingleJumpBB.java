package MiddleEnd.Optimization.ControlFlow;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Type.*; 

import java.util.ArrayList;
import java.util.List;

/**
 * RemoveSingleJumpBB优化Pass
 * 删除只包含一条跳转指令的基本块，简化控制流
 */
public class RemoveSingleJumpBB implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "RemoveSingleJumpBB";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            // 合并基本块
            // changed |= mergeBasicBlocks(function);
            
            // 合并连续的基本块
            // changed |= mergeContinuousBlocks(function);
            
            // 处理只有一条无条件跳转指令的入口块
            changed |= handleEntryBlock(function);
        }
        
        return changed;
    }
    
    private boolean handleEntryBlock(Function function) {
        BasicBlock entryBlock = function.getEntryBlock();
        
        if (entryBlock.getInstructions().size() == 1) {
            Instruction inst = entryBlock.getInstructions().get(0);
            if (inst instanceof BranchInstruction brInst && brInst.isUnconditional()) {
                BasicBlock targetBlock = brInst.getTrueBlock();
                
                if (targetBlock.getPredecessors().size() == 1) {
                    // 将目标块设为新的入口块
                    entryBlock.removeFromParent();
                    
                    List<BasicBlock> blocks = function.getBasicBlocks();
                    blocks.remove(targetBlock);
                    blocks.add(0, targetBlock);
                    
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean mergeBasicBlocks(Function function) {
        boolean changed = false;
        boolean localChanged;
        
        do {
            localChanged = false;
            
            List<BasicBlock> blocksToRemove = new ArrayList<>();
            for (BasicBlock bb : function.getBasicBlocks()) {
                if (bb == function.getEntryBlock()) {
                    continue;
                }
                
                if (canRemoveBlock(bb)) {
                    blocksToRemove.add(bb);
                    localChanged = true;
                }
            }
            
            for (BasicBlock bb : blocksToRemove) {
                processRemovableBlock(bb);
            }
            
            changed |= localChanged;
        } while (localChanged);
        
        return changed;
    }
    
    private boolean mergeContinuousBlocks(Function function) {
        boolean changed = false;
        boolean localChanged;
        
        do {
            localChanged = false;
            
            List<BasicBlock> blocksToMerge = new ArrayList<>();
            
            for (BasicBlock bb : function.getBasicBlocks()) {
                Instruction terminator = bb.getTerminator();

                if (terminator instanceof BranchInstruction brInst && brInst.isUnconditional()) {
                    BasicBlock successor = brInst.getTrueBlock();
                    
                    if (successor.getPredecessors().size() == 1 && successor != function.getEntryBlock()) {
                        boolean hasPhiInst = false;
                        for (Instruction inst : successor.getInstructions()) {
                            if (inst instanceof PhiInstruction) {
                                hasPhiInst = true;
                                break;
                            }
                        }
                        
                        if (!hasPhiInst) {
                            blocksToMerge.add(bb);
                            localChanged = true;
                        }
                    }
                }
            }
            
            for (BasicBlock bb : blocksToMerge) {
                mergeWithSuccessor(bb);
            }
            
            changed |= localChanged;
        } while (localChanged);
        
        return changed;
    }
    
    private void mergeWithSuccessor(BasicBlock bb) {
        BranchInstruction brInst = (BranchInstruction) bb.getTerminator();
        BasicBlock successor = brInst.getTrueBlock();
        
        bb.removeInstruction(brInst);
        
        List<Instruction> successorInsts = new ArrayList<>(successor.getInstructions());
        for (Instruction inst : successorInsts) {
            inst.removeFromParent();
            bb.addInstruction(inst);
        }
        
        bb.removeSuccessor(successor);
        
        for (BasicBlock succ : new ArrayList<>(successor.getSuccessors())) {
            bb.addSuccessor(succ);
            succ.removePredecessor(successor);
            succ.addPredecessor(bb);
        }
        
        successor.removeFromParent();
    }
    
    private boolean canRemoveBlock(BasicBlock bb) {
        List<Instruction> instructions = bb.getInstructions();
        
        if (instructions.size() == 1 && instructions.get(0) instanceof BranchInstruction brInst) {
            if (brInst.isUnconditional()) {
                BasicBlock targetBB = brInst.getTrueBlock();
                
                if (targetBB.getPredecessors().size() == 1) {
                    return true;
                }

                for (Instruction inst : targetBB.getInstructions()) {
                    if (inst instanceof PhiInstruction phi) {
                        if (phi.getType() instanceof IntegerType && 
                            ((IntegerType)phi.getType()).getBitWidth() == 1) {
                            return false;
                        }
                    }
                }
                
                return true;
            } else {
                Value condition = brInst.getCondition();
                if (condition instanceof ConstantInt) {
                    BasicBlock targetBB = ((ConstantInt) condition).getValue() != 0 ? 
                                         brInst.getTrueBlock() : brInst.getFalseBlock();
                    
                    for (Instruction inst : targetBB.getInstructions()) {
                        if (inst instanceof PhiInstruction phi) {
                            if (phi.getType() instanceof IntegerType && 
                                ((IntegerType)phi.getType()).getBitWidth() == 1) {
                                return false;
                            }
                        }
                    }
                    
                    return true;
                }
                if (brInst.getTrueBlock() == brInst.getFalseBlock()) {
                    for (Instruction inst : brInst.getTrueBlock().getInstructions()) {
                        if (inst instanceof PhiInstruction phi) {
                            if (phi.getType() instanceof IntegerType && 
                                ((IntegerType)phi.getType()).getBitWidth() == 1) {
                                return false;
                            }
                        }
                    }
                    
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private void processRemovableBlock(BasicBlock bb) {
        BranchInstruction brInst = (BranchInstruction) bb.getLastInstruction();
        BasicBlock targetBB;
        
        if (brInst.isUnconditional()) {
            targetBB = brInst.getTrueBlock();
        } else {
            ConstantInt condVal = (ConstantInt) brInst.getCondition();
            targetBB = (condVal.getValue() != 0) ? brInst.getTrueBlock() : brInst.getFalseBlock();
            
            if (brInst.getTrueBlock() == brInst.getFalseBlock()) {
                bb.removeInstruction(brInst);
                BranchInstruction newBr = new BranchInstruction(brInst.getTrueBlock());
                bb.addInstruction(newBr);
                brInst = newBr;
            }
        }
        
        for (Instruction inst : targetBB.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                if (phi.getType() instanceof IntegerType && 
                    ((IntegerType)phi.getType()).getBitWidth() == 1) {
                    return;
                }
            }
        }
        
        List<BasicBlock> predecessors = new ArrayList<>(bb.getPredecessors());
        for (BasicBlock pred : predecessors) {
            updatePredecessorTerminator(pred, bb, targetBB);
            
            pred.removeSuccessor(bb);
            if (!pred.getSuccessors().contains(targetBB)) {
                pred.addSuccessor(targetBB);
                targetBB.addPredecessor(pred);
            }
        }
        
        updatePhiInstructions(bb, targetBB);
        
        targetBB.removePredecessor(bb);
        
        bb.removeFromParent();
    }

    private void updatePredecessorTerminator(BasicBlock pred, BasicBlock oldTarget, BasicBlock newTarget) {
        Instruction terminator = pred.getTerminator();
        if (terminator instanceof BranchInstruction brInst) {
            for (Instruction inst : newTarget.getInstructions()) {
                if (inst instanceof PhiInstruction phi) {
                    if (phi.getType() instanceof IntegerType && 
                        ((IntegerType)phi.getType()).getBitWidth() == 1) {
                        return;
                    }
                }
            }
            
            if (brInst.isUnconditional()) {
                pred.removeInstruction(brInst);
                BranchInstruction newBr = new BranchInstruction(newTarget);
                pred.addInstruction(newBr);
            } else {
                BasicBlock trueBlock = brInst.getTrueBlock();
                BasicBlock falseBlock = brInst.getFalseBlock();
                
                if (trueBlock == oldTarget) {
                    trueBlock = newTarget;
                }
                if (falseBlock == oldTarget) {
                    falseBlock = newTarget;
                }
                
                if (trueBlock == falseBlock) {
                    pred.removeInstruction(brInst);
                    BranchInstruction newBr = new BranchInstruction(trueBlock);
                    pred.addInstruction(newBr);
                } else {
                    pred.removeInstruction(brInst);
                    BranchInstruction newBr = new BranchInstruction(brInst.getCondition(), trueBlock, falseBlock);
                    pred.addInstruction(newBr);
                }
            }
        }
    }
    
    private void updatePhiInstructions(BasicBlock oldBlock, BasicBlock targetBlock) {
        for (Instruction inst : targetBlock.getInstructions()) {
            if (!(inst instanceof PhiInstruction phi)) {
                break;
            }
            
            if (phi.getIncomingBlocks().contains(oldBlock)) {
                Value value = phi.getIncomingValue(oldBlock);
                
                phi.removeIncoming(oldBlock);
                
                for (BasicBlock pred : oldBlock.getPredecessors()) {
                    if (!phi.getIncomingBlocks().contains(pred)) {
                        phi.addIncoming(value, pred);
                    }
                }
            }
        }
    }
    
} 
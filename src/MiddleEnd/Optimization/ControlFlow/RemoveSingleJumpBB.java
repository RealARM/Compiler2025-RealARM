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
        
        // 对模块中的每个函数应用优化
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            // 合并基本块
            // changed |= mergeBasicBlocks(function);
            
            // 合并连续的基本块
            // changed |= mergeContinuousBlocks(function);
            
            // 处理入口块的特殊情况
            changed |= handleEntryBlock(function);
        }
        
        return changed;
    }
    
    /**
     * 处理入口块的特殊情况
     * 如果入口块只有一条无条件跳转指令，将其目标块设为新的入口块
     * @param function 要处理的函数
     * @return 如果发生了变化返回true，否则返回false
     */
    private boolean handleEntryBlock(Function function) {
        BasicBlock entryBlock = function.getEntryBlock();
        
        // 检查入口块是否只有一条无条件跳转指令
        if (entryBlock.getInstructions().size() == 1) {
            Instruction inst = entryBlock.getInstructions().get(0);
            if (inst instanceof BranchInstruction brInst && brInst.isUnconditional()) {
                BasicBlock targetBlock = brInst.getTrueBlock();
                
                // 检查目标块的前驱是否只有入口块
                if (targetBlock.getPredecessors().size() == 1) {
                    // 将目标块设为新的入口块
                    
                    // 首先从函数中移除入口块
                    entryBlock.removeFromParent();
                    
                    // 将目标块移到函数基本块列表的开头
                    List<BasicBlock> blocks = function.getBasicBlocks();
                    blocks.remove(targetBlock);
                    blocks.add(0, targetBlock);
                    
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 合并基本块
     * @param function 要处理的函数
     * @return 如果发生了变化返回true，否则返回false
     */
    private boolean mergeBasicBlocks(Function function) {
        boolean changed = false;
        boolean localChanged;
        
        // 多次迭代直到没有变化
        do {
            localChanged = false;
            
            // 找出可以删除的基本块
            List<BasicBlock> blocksToRemove = new ArrayList<>();
            for (BasicBlock bb : function.getBasicBlocks()) {
                // 跳过入口块
                if (bb == function.getEntryBlock()) {
                    continue;
                }
                
                // 检查是否只有一条跳转指令
                if (canRemoveBlock(bb)) {
                    blocksToRemove.add(bb);
                    localChanged = true;
                }
            }
            
            // 处理需要删除的块
            for (BasicBlock bb : blocksToRemove) {
                processRemovableBlock(bb);
            }
            
            changed |= localChanged;
        } while (localChanged);
        
        return changed;
    }
    
    /**
     * 合并连续的基本块
     * 如果一个基本块只有一个后继，且该后继只有一个前驱，则可以合并
     * @param function 要处理的函数
     * @return 如果发生了变化返回true，否则返回false
     */
    private boolean mergeContinuousBlocks(Function function) {
        boolean changed = false;
        boolean localChanged;
        
        do {
            localChanged = false;
            
            List<BasicBlock> blocksToMerge = new ArrayList<>();
            
            // 找出可以合并的基本块对
            for (BasicBlock bb : function.getBasicBlocks()) {
                // 获取基本块的终结指令
                Instruction terminator = bb.getTerminator();
                
                // 只处理无条件跳转
                if (terminator instanceof BranchInstruction brInst && brInst.isUnconditional()) {
                    BasicBlock successor = brInst.getTrueBlock();
                    
                    // 如果后继块只有当前块作为前驱，且不是入口块
                    if (successor.getPredecessors().size() == 1 && successor != function.getEntryBlock()) {
                        // 检查后继块是否有PHI指令
                        boolean hasPhiInst = false;
                        for (Instruction inst : successor.getInstructions()) {
                            if (inst instanceof PhiInstruction) {
                                hasPhiInst = true;
                                break;
                            }
                        }
                        
                        // 如果没有PHI指令，可以合并
                        if (!hasPhiInst) {
                            blocksToMerge.add(bb);
                            localChanged = true;
                        }
                    }
                }
            }
            
            // 合并基本块
            for (BasicBlock bb : blocksToMerge) {
                mergeWithSuccessor(bb);
            }
            
            changed |= localChanged;
        } while (localChanged);
        
        return changed;
    }
    
    /**
     * 将基本块与其后继合并
     * @param bb 要合并的基本块
     */
    private void mergeWithSuccessor(BasicBlock bb) {
        // 获取终结指令和后继块
        BranchInstruction brInst = (BranchInstruction) bb.getTerminator();
        BasicBlock successor = brInst.getTrueBlock();
        
        // 移除终结指令
        bb.removeInstruction(brInst);
        
        // 将后继块的所有指令移动到当前块
        List<Instruction> successorInsts = new ArrayList<>(successor.getInstructions());
        for (Instruction inst : successorInsts) {
            inst.removeFromParent();
            bb.addInstruction(inst);
        }
        
        // 更新CFG
        // 移除当前块到后继块的边
        bb.removeSuccessor(successor);
        
        // 添加当前块到后继块的后继的边
        for (BasicBlock succ : new ArrayList<>(successor.getSuccessors())) {
            bb.addSuccessor(succ);
            succ.removePredecessor(successor);
            succ.addPredecessor(bb);
        }
        
        // 从函数中移除后继块
        successor.removeFromParent();
    }
    
    /**
     * 检查基本块是否可以删除
     * @param bb 要检查的基本块
     * @return 如果可以删除返回true，否则返回false
     */
    private boolean canRemoveBlock(BasicBlock bb) {
        // 获取基本块的指令
        List<Instruction> instructions = bb.getInstructions();
        
        // 如果基本块只有一条指令，并且是跳转指令
        if (instructions.size() == 1 && instructions.get(0) instanceof BranchInstruction brInst) {
            // 只处理无条件跳转或条件为常量的跳转
            if (brInst.isUnconditional()) {
                // 获取目标基本块
                BasicBlock targetBB = brInst.getTrueBlock();
                
                // 检查目标块的前驱数量，如果只有当前块是其前驱，则可以合并
                if (targetBB.getPredecessors().size() == 1) {
                    return true;
                }
                
                // 检查目标块是否包含PHI指令，如果包含，检查这些PHI指令是否参与逻辑表达式
                for (Instruction inst : targetBB.getInstructions()) {
                    if (inst instanceof PhiInstruction phi) {
                        // 检查PHI指令的类型是否为i1，这通常表示它参与逻辑表达式
                        if (phi.getType() instanceof IntegerType && 
                            ((IntegerType)phi.getType()).getBitWidth() == 1) {
                            // 如果是逻辑表达式的PHI节点，不删除当前块
                            return false;
                        }
                    }
                }
                
                return true; // 可以删除这个只有无条件跳转的块
            } else {
                Value condition = brInst.getCondition();
                // 条件是常量，可以确定跳转目标
                if (condition instanceof ConstantInt) {
                    // 检查目标块是否包含PHI指令
                    BasicBlock targetBB = ((ConstantInt) condition).getValue() != 0 ? 
                                         brInst.getTrueBlock() : brInst.getFalseBlock();
                    
                    // 检查目标块是否包含逻辑表达式相关的PHI指令
                    for (Instruction inst : targetBB.getInstructions()) {
                        if (inst instanceof PhiInstruction phi) {
                            if (phi.getType() instanceof IntegerType && 
                                ((IntegerType)phi.getType()).getBitWidth() == 1) {
                                return false; // 有逻辑表达式相关的PHI指令，不删除
                            }
                        }
                    }
                    
                    return true;
                }
                // 特殊情况：条件分支的两个目标相同，可以转换为无条件跳转
                if (brInst.getTrueBlock() == brInst.getFalseBlock()) {
                    // 检查目标块是否包含逻辑表达式相关的PHI指令
                    for (Instruction inst : brInst.getTrueBlock().getInstructions()) {
                        if (inst instanceof PhiInstruction phi) {
                            if (phi.getType() instanceof IntegerType && 
                                ((IntegerType)phi.getType()).getBitWidth() == 1) {
                                return false; // 有逻辑表达式相关的PHI指令，不删除
                            }
                        }
                    }
                    
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 处理可以删除的基本块
     * @param bb 要删除的基本块
     */
    private void processRemovableBlock(BasicBlock bb) {
        BranchInstruction brInst = (BranchInstruction) bb.getLastInstruction();
        BasicBlock targetBB;
        
        // 确定目标基本块
        if (brInst.isUnconditional()) {
            targetBB = brInst.getTrueBlock();
        } else {
            // 对于条件跳转，根据常量条件确定目标块
            ConstantInt condVal = (ConstantInt) brInst.getCondition();
            targetBB = (condVal.getValue() != 0) ? brInst.getTrueBlock() : brInst.getFalseBlock();
            
            // 特殊情况：如果条件分支的两个目标相同，直接替换为无条件跳转
            if (brInst.getTrueBlock() == brInst.getFalseBlock()) {
                // 删除原条件分支
                bb.removeInstruction(brInst);
                // 创建无条件跳转
                BranchInstruction newBr = new BranchInstruction(brInst.getTrueBlock());
                bb.addInstruction(newBr);
                // 更新brInst引用
                brInst = newBr;
            }
        }
        
        // 检查目标块是否包含逻辑表达式相关的PHI指令
        for (Instruction inst : targetBB.getInstructions()) {
            if (inst instanceof PhiInstruction phi) {
                // 检查PHI指令的类型是否为i1，这通常表示它参与逻辑表达式
                if (phi.getType() instanceof IntegerType && 
                    ((IntegerType)phi.getType()).getBitWidth() == 1) {
                    // 如果是逻辑表达式的PHI节点，不处理这个块
                    return;
                }
            }
        }
        
        // 更新前驱块的跳转
        List<BasicBlock> predecessors = new ArrayList<>(bb.getPredecessors());
        for (BasicBlock pred : predecessors) {
            // 更新前驱块的终结指令
            updatePredecessorTerminator(pred, bb, targetBB);
            
            // 更新CFG - 检查是否已经存在边，避免重复
            pred.removeSuccessor(bb);
            if (!pred.getSuccessors().contains(targetBB)) {
                pred.addSuccessor(targetBB);
                targetBB.addPredecessor(pred);
            }
        }
        
        // 更新目标块中的PHI指令
        updatePhiInstructions(bb, targetBB);
        
        // 从目标块的前驱列表中移除当前块
        targetBB.removePredecessor(bb);
        
        // 从函数中移除基本块
        bb.removeFromParent();
    }
    
    /**
     * 更新前驱块的终结指令
     * @param pred 前驱块
     * @param oldTarget 旧的目标块
     * @param newTarget 新的目标块
     */
    private void updatePredecessorTerminator(BasicBlock pred, BasicBlock oldTarget, BasicBlock newTarget) {
        Instruction terminator = pred.getTerminator();
        if (terminator instanceof BranchInstruction brInst) {
            // 检查新目标块是否包含逻辑表达式相关的PHI指令
            for (Instruction inst : newTarget.getInstructions()) {
                if (inst instanceof PhiInstruction phi) {
                    // 检查PHI指令的类型是否为i1，这通常表示它参与逻辑表达式
                    if (phi.getType() instanceof IntegerType && 
                        ((IntegerType)phi.getType()).getBitWidth() == 1) {
                        // 如果是逻辑表达式的PHI节点，不更新终结指令
                        return;
                    }
                }
            }
            
            if (brInst.isUnconditional()) {
                // 无条件跳转，直接替换目标
                pred.removeInstruction(brInst);
                BranchInstruction newBr = new BranchInstruction(newTarget);
                pred.addInstruction(newBr);
            } else {
                // 条件跳转，替换相应的目标
                BasicBlock trueBlock = brInst.getTrueBlock();
                BasicBlock falseBlock = brInst.getFalseBlock();
                
                if (trueBlock == oldTarget) {
                    trueBlock = newTarget;
                }
                if (falseBlock == oldTarget) {
                    falseBlock = newTarget;
                }
                
                // 如果true和false分支指向相同的块，创建无条件跳转
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
    
    /**
     * 更新目标块中的PHI指令
     * @param oldBlock 要删除的块
     * @param targetBlock 目标块
     */
    private void updatePhiInstructions(BasicBlock oldBlock, BasicBlock targetBlock) {
        for (Instruction inst : targetBlock.getInstructions()) {
            if (!(inst instanceof PhiInstruction phi)) {
                break;  // PHI指令必须在块的开头
            }
            
            // 查找PHI指令中来自oldBlock的值
            if (phi.getIncomingBlocks().contains(oldBlock)) {
                Value value = phi.getIncomingValue(oldBlock);
                
                // 移除来自oldBlock的值
                phi.removeIncoming(oldBlock);
                
                // 为oldBlock的每个前驱添加新的PHI输入，避免重复添加
                for (BasicBlock pred : oldBlock.getPredecessors()) {
                    // 检查前驱是否已经在PHI节点中
                    if (!phi.getIncomingBlocks().contains(pred)) {
                        phi.addIncoming(value, pred);
                    }
                }
            }
        }
    }
    
} 
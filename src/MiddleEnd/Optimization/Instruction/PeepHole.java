package MiddleEnd.Optimization.Instruction;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 窥孔优化
 */
public class PeepHole implements Optimizer.ModuleOptimizer {
    
    @Override
    public String getName() {
        return "PeepHole";
    }
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= optimizeBranchSameTarget(function);
            changed |= optimizeCascadingBranches(function);
            changed |= optimizeConsecutiveStores(function);
            changed |= optimizeStoreLoad(function);
            // changed |= optimizeConstantCompare(function);
        }
        
        return changed;
    }
    
    /**
     * 简化相同目标的条件分支 (类似于 br %x, %block A, %block A)
     * 将 br cond, target, target 替换为 br target
     */
    private boolean optimizeBranchSameTarget(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            Instruction terminator = block.getTerminator();
            if (terminator instanceof BranchInstruction branch && !branch.isUnconditional()) {
                BasicBlock trueBlock = branch.getTrueBlock();
                BasicBlock falseBlock = branch.getFalseBlock();
                
                if (trueBlock == falseBlock) {
                    BranchInstruction newBranch = new BranchInstruction(trueBlock);
                    block.removeInstruction(terminator);
                    block.addInstruction(newBranch);
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 合并级联条件分支 (类似于 br %x %block A, %block B; Block B: br %x %block C, %block D)
     * 如果条件相同，可以直接从第一个块跳转到最终目标
     */
    private boolean optimizeCascadingBranches(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            Instruction terminator = block.getTerminator();
            if (!(terminator instanceof BranchInstruction branch) || branch.isUnconditional()) {
                continue;
            }
            
            Value condition = branch.getCondition();
            BasicBlock trueBlock = branch.getTrueBlock();
            BasicBlock falseBlock = branch.getFalseBlock();
            
            if (trueBlock.getInstructions().size() == 1) {
                Instruction trueTerm = trueBlock.getTerminator();
                if (trueTerm instanceof BranchInstruction trueBranch && 
                        !trueBranch.isUnconditional() && 
                        trueBranch.getCondition() == condition) {
                    branch.setOperand(1, trueBranch.getTrueBlock());
                    changed = true;
                }
            }
            
            if (falseBlock.getInstructions().size() == 1) {
                Instruction falseTerm = falseBlock.getTerminator();
                if (falseTerm instanceof BranchInstruction falseBranch && 
                        !falseBranch.isUnconditional() && 
                        falseBranch.getCondition() == condition) {
                    branch.setOperand(2, falseBranch.getFalseBlock());
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 连续对同一地址的store指令，消除前面的store
     * store A -> ptr, store B -> ptr ==> store B -> ptr
     */
    private boolean optimizeConsecutiveStores(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            List<StoreInstruction> toRemove = new ArrayList<>();
            
            for (int i = 0; i < instructions.size() - 1; i++) {
                Instruction curr = instructions.get(i);
                
                if (!(curr instanceof StoreInstruction currStore)) {
                    continue;
                }
                
                Value currPointer = currStore.getPointer();
                
                for (int j = i + 1; j < instructions.size(); j++) {
                    Instruction next = instructions.get(j);
                    
                    if (next instanceof CallInstruction) {
                        break;
                    }
                    
                    if (next instanceof StoreInstruction nextStore &&
                            nextStore.getPointer() == currPointer) {
                        toRemove.add(currStore);
                        break;
                    }
                    
                    if (next instanceof LoadInstruction load &&
                            load.getPointer() == currPointer) {
                        break;
                    }
                }
            }
            
            for (StoreInstruction inst : toRemove) {
                inst.removeFromParent();
                changed = true;
            }
        }
        
        return changed;
    }
    
    /**
     * store后立即load优化
     * store A -> ptr; load B <- ptr ==> 用A替换B的所有使用
     */
    private boolean optimizeStoreLoad(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (int i = 0; i < instructions.size() - 1; i++) {
                Instruction curr = instructions.get(i);
                
                if (!(curr instanceof StoreInstruction currStore)) {
                    continue;
                }
                
                Value storedValue = currStore.getValue();
                Value storePointer = currStore.getPointer();
                
                Instruction next = instructions.get(i + 1);
                if (next instanceof LoadInstruction loadInst && 
                        loadInst.getPointer() == storePointer) {
                    replaceAllUses(loadInst, storedValue);
                    loadInst.removeFromParent();
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 常数比较优化
     * 识别以下模式：
     * a = icmp ne/eq/... const1, const2
     * zext a to b
     * c = icmp ne b, 0
     * br c blocka, blockb
     * 
     * 这种模式可以直接在编译时计算结果，替换为无条件跳转
     */
    private boolean optimizeConstantCompare(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            Instruction terminator = block.getTerminator();
            if (!(terminator instanceof BranchInstruction branch) || branch.isUnconditional()) {
                continue;
            }
            
            Value condition = branch.getCondition();
            if (!(condition instanceof CompareInstruction secondCmp)) {
                continue;
            }
            
            if (secondCmp.getPredicate() != OpCode.NE) {
                continue;
            }
            
            if (!(secondCmp.getRight() instanceof ConstantInt rightConst) || rightConst.getValue() != 0) {
                continue;
            }
            
            Value zextResult = secondCmp.getLeft();
            if (!(zextResult instanceof ConversionInstruction convInst)) {
                continue;
            }
            
            if (convInst.getConversionType() != OpCode.ZEXT) {
                continue;
            }
            
            Value firstCmpResult = convInst.getSource();
            if (!(firstCmpResult instanceof CompareInstruction firstCmp)) {
                continue;
            }
            
            if (!(firstCmp.getLeft() instanceof ConstantInt firstLeft && 
                  firstCmp.getRight() instanceof ConstantInt firstRight)) {
                continue;
            }
            
            int leftValue = firstLeft.getValue();
            int rightValue = firstRight.getValue();
            boolean compareResult;
            
            switch (firstCmp.getPredicate()) {
                case EQ:
                    compareResult = (leftValue == rightValue);
                    break;
                case NE:
                    compareResult = (leftValue != rightValue);
                    break;
                case SGT:
                    compareResult = (leftValue > rightValue);
                    break;
                case SGE:
                    compareResult = (leftValue >= rightValue);
                    break;
                case SLT:
                    compareResult = (leftValue < rightValue);
                    break;
                case SLE:
                    compareResult = (leftValue <= rightValue);
                    break;
                default:
                    continue;
            }
            
            BasicBlock target = compareResult ? branch.getTrueBlock() : branch.getFalseBlock();
            BranchInstruction newBranch = new BranchInstruction(target);
            
            block.removeInstruction(terminator);
            block.addInstruction(newBranch);
            
            cleanupUnusedInstructions(secondCmp, convInst, firstCmp);
            
            changed = true;
        }
        
        return changed;
    }
    
    private void cleanupUnusedInstructions(CompareInstruction secondCmp, 
                                         ConversionInstruction convInst, 
                                         CompareInstruction firstCmp) {
        secondCmp.removeFromParent();
        convInst.removeFromParent();
        firstCmp.removeFromParent();
    }
    
    private void replaceAllUses(Value oldValue, Value newValue) {
        List<User> users = new ArrayList<>(oldValue.getUsers());
        
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldValue) {
                    user.setOperand(i, newValue);
                }
            }
        }
    }
    
    private void replaceInstruction(Instruction oldInst, Value newValue) {
        ArrayList<User> users = new ArrayList<>(oldInst.getUsers());
        
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldInst) {
                    user.setOperand(i, newValue);
                }
            }
        }
        
        if (oldInst.getUsers().isEmpty()) {
            oldInst.removeFromParent();
        }
    }
}
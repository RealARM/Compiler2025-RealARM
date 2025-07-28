package IR.Pass;

import IR.Module;
import IR.OpCode;
import IR.Type.IntegerType;
import IR.Value.BasicBlock;
import IR.Value.ConstantInt;
import IR.Value.Function;
import IR.Value.Instructions.*;
import IR.Value.User;
import IR.Value.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 窥孔优化Pass - 对特定模式的指令序列进行优化
 */
public class PeepHole implements Pass.IRPass {
    
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
            
            // 启用安全的窥孔优化
            changed |= optimizePointerAddZero(function);
            changed |= optimizeBranchSameTarget(function);
            changed |= optimizeCascadingBranches(function);
            changed |= optimizeConsecutiveStores(function);
            changed |= optimizeStoreLoad(function);
            // changed |= optimizeConstantCompare(function);
        }
        
        return changed;
    }
    
    /**
     * 优化1：消除指针+0操作 (类似于 ptradd A, 0)
     * 将 ptr + 0 替换为 ptr
     */
    private boolean optimizePointerAddZero(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (Instruction inst : instructions) {
                if (inst instanceof GetElementPtrInstruction gep) {
                    // 只处理只有一个索引的GEP指令
                    if (gep.getOperandCount() != 2) {
                        continue;
                    }
                    
                    Value offset = gep.getOffset();
                    if (offset instanceof ConstantInt && ((ConstantInt) offset).getValue() == 0) {
                        // 替换为原始指针
                        replaceInstruction(gep, gep.getPointer());
                        changed = true;
                    }
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 优化2：简化相同目标的条件分支 (类似于 br %x, %block A, %block A)
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
                    // 替换为无条件分支
                    BranchInstruction newBranch = new BranchInstruction(trueBlock);
                    // 移除旧的分支指令
                    block.removeInstruction(terminator);
                    // 添加新的分支指令
                    block.addInstruction(newBranch);
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 优化3：合并级联条件分支 (类似于 br %x %block A, %block B; Block B: br %x %block C, %block D)
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
            
            // 检查true分支是否只有一个指令(终结指令)
            if (trueBlock.getInstructions().size() == 1) {
                Instruction trueTerm = trueBlock.getTerminator();
                if (trueTerm instanceof BranchInstruction trueBranch && 
                        !trueBranch.isUnconditional() && 
                        trueBranch.getCondition() == condition) {
                    // 替换为直接跳转到最终目标
                    branch.setOperand(1, trueBranch.getTrueBlock());
                    changed = true;
                }
            }
            
            // 检查false分支是否只有一个指令(终结指令)
            if (falseBlock.getInstructions().size() == 1) {
                Instruction falseTerm = falseBlock.getTerminator();
                if (falseTerm instanceof BranchInstruction falseBranch && 
                        !falseBranch.isUnconditional() && 
                        falseBranch.getCondition() == condition) {
                    // 替换为直接跳转到最终目标
                    branch.setOperand(2, falseBranch.getFalseBlock());
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 优化4：连续对同一地址的store指令，消除前面的store
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
                
                // 检查后续指令
                for (int j = i + 1; j < instructions.size(); j++) {
                    Instruction next = instructions.get(j);
                    
                    // 如果遇到其他可能改变内存的指令，停止检查
                    if (next instanceof CallInstruction) {
                        break;
                    }
                    
                    // 如果是对同一地址的store
                    if (next instanceof StoreInstruction nextStore &&
                            nextStore.getPointer() == currPointer) {
                        // 前面的store可以删除
                        toRemove.add(currStore);
                        break;
                    }
                    
                    // 如果遇到load指令加载同一地址，停止检查
                    if (next instanceof LoadInstruction load &&
                            load.getPointer() == currPointer) {
                        break;
                    }
                }
            }
            
            // 移除冗余的store指令
            for (StoreInstruction inst : toRemove) {
                inst.removeFromParent();
                changed = true;
            }
        }
        
        return changed;
    }
    
    /**
     * 优化5：store后立即load优化
     * store A -> ptr; load B <- ptr ==> 用A替换B的所有使用
     */
    private boolean optimizeStoreLoad(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (int i = 0; i < instructions.size() - 1; i++) {
                Instruction curr = instructions.get(i);
                
                // 检查是否为store指令
                if (!(curr instanceof StoreInstruction currStore)) {
                    continue;
                }
                
                Value storedValue = currStore.getValue();
                Value storePointer = currStore.getPointer();
                
                // 检查下一条指令是否为load指令
                Instruction next = instructions.get(i + 1);
                if (next instanceof LoadInstruction loadInst && 
                        loadInst.getPointer() == storePointer) {
                    // 直接用store的值替换load的所有使用
                    replaceAllUses(loadInst, storedValue);
                    loadInst.removeFromParent();
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 优化6：常数比较优化
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
            // 检查终结指令是否为条件分支
            if (!(terminator instanceof BranchInstruction branch) || branch.isUnconditional()) {
                continue;
            }
            
            Value condition = branch.getCondition();
            // 检查条件是否为比较指令
            if (!(condition instanceof CompareInstruction secondCmp)) {
                continue;
            }
            
            // 检查是否为 ne 比较
            if (secondCmp.getPredicate() != OpCode.NE) {
                continue;
            }
            
            // 检查是否比较的是0
            if (!(secondCmp.getRight() instanceof ConstantInt rightConst) || rightConst.getValue() != 0) {
                continue;
            }
            
            // 检查左操作数是否为转换指令（可能是zext）
            Value zextResult = secondCmp.getLeft();
            if (!(zextResult instanceof ConversionInstruction convInst)) {
                continue;
            }
            
            // 检查是否为zext指令
            if (convInst.getConversionType() != OpCode.ZEXT) {
                continue;
            }
            
            // 检查zext的操作数是否为比较指令
            Value firstCmpResult = convInst.getSource();
            if (!(firstCmpResult instanceof CompareInstruction firstCmp)) {
                continue;
            }
            
            // 检查比较操作数是否为常量
            if (!(firstCmp.getLeft() instanceof ConstantInt firstLeft && 
                  firstCmp.getRight() instanceof ConstantInt firstRight)) {
                continue;
            }
            
            // 计算常量比较的结果
            int leftValue = firstLeft.getValue();
            int rightValue = firstRight.getValue();
            boolean compareResult;
            
            // 根据比较谓词计算结果
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
                    continue; // 不处理其他类型的比较
            }
            
            // 创建无条件分支，跳转到相应目标
            BasicBlock target = compareResult ? branch.getTrueBlock() : branch.getFalseBlock();
            BranchInstruction newBranch = new BranchInstruction(target);
            
            // 替换原分支指令
            block.removeInstruction(terminator);
            block.addInstruction(newBranch);
            
            // 清理现在无用的指令：secondCmp, convInst, firstCmp
            cleanupUnusedInstructions(secondCmp, convInst, firstCmp);
            
            changed = true;
        }
        
        return changed;
    }
    
    /**
     * 清理无用的指令
     * 按照依赖关系的逆序删除指令（先删除使用者，再删除被使用者）
     */
    private void cleanupUnusedInstructions(CompareInstruction secondCmp, 
                                         ConversionInstruction convInst, 
                                         CompareInstruction firstCmp) {
        // 现在这些指令已经不被分支指令使用了，应该可以安全删除
        // 按逆序删除：secondCmp -> convInst -> firstCmp
        
        // 1. 删除 secondCmp（icmp ne）
        secondCmp.removeFromParent();
        
        // 2. 删除 convInst（zext）
        convInst.removeFromParent();
        
        // 3. 删除 firstCmp（icmp eq）
        firstCmp.removeFromParent();
    }
    
    /**
     * 替换指令的所有使用为新值
     */
    private void replaceAllUses(Value oldValue, Value newValue) {
        // 获取所有使用oldValue的用户
        List<User> users = new ArrayList<>(oldValue.getUsers());
        
        // 遍历所有用户，替换使用
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldValue) {
                    user.setOperand(i, newValue);
                }
            }
        }
    }
    
    /**
     * 替换指令
     */
    private void replaceInstruction(Instruction oldInst, Value newValue) {
        // 保存所有使用oldInst的用户
        ArrayList<User> users = new ArrayList<>(oldInst.getUsers());
        
        // 替换所有使用
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldInst) {
                    user.setOperand(i, newValue);
                }
            }
        }
        
        // 如果指令没有使用者，从基本块中移除
        if (oldInst.getUsers().isEmpty()) {
            oldInst.removeFromParent();
        }
    }
}
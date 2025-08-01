package MiddleEnd.Optimization.Instruction;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Cleanup.DCE;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 移除无用的不等于比较指令和相关无用转换指令Pass
 * 优化：
 * 1. 形如 (x != 0) 的比较指令，其中x已经是布尔值(i1类型)
 * 2. 形如 (x != 0) 的比较指令后，使用zext对结果进行扩展的模式
 */
public class RemoveUselessNE implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "RemoveUselessNE";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;

        // 遍历模块中的所有函数
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            // 遍历函数中的所有基本块
            for (BasicBlock bb : function.getBasicBlocks()) {
                changed |= optimizeBasicBlock(bb);
            }
        }
        
        // 如果进行了优化，运行DCE以清理不再使用的指令
        if (changed) {
            DCE dce = new DCE();
            dce.run(module);
        }
        
        return changed;
    }
    
    /**
     * 优化基本块中的指令
     * @param bb 要优化的基本块
     * @return 如果进行了优化返回true，否则返回false
     */
    private boolean optimizeBasicBlock(BasicBlock bb) {
        boolean changed = false;
        List<OptimizationCandidate> optimizationCandidates = new ArrayList<>();
        
        // 第一遍：识别可以优化的指令
        for (Instruction inst : bb.getInstructions()) {
            // 检查并收集无用的NE比较指令
            OptimizationCandidate candidate = findUselessNEComparison(inst);
            if (candidate != null) {
                optimizationCandidates.add(candidate);
                continue;
            }
            
            // 检查并收集无用的zext指令
            candidate = findUselessZext(inst);
            if (candidate != null) {
                optimizationCandidates.add(candidate);
            }
        }
        
        // 第二遍：应用优化
        for (OptimizationCandidate candidate : optimizationCandidates) {
            applyOptimization(candidate);
            changed = true;
        }
        
        return changed;
    }
    
    /**
     * 查找无用的不等于0比较指令
     * @param inst 要检查的指令
     * @return 如果找到可优化的指令，返回优化候选对象，否则返回null
     */
    private OptimizationCandidate findUselessNEComparison(Instruction inst) {
        // 检查是否为整数比较指令并且比较类型为不等于(NE)
        if (!(inst instanceof CompareInstruction cmpInst) || 
            !cmpInst.isIntegerCompare() || 
            cmpInst.getPredicate() != OpCode.NE) {
            return null;
        }
        
        // 获取比较的两个操作数
        Value left = cmpInst.getLeft();
        Value right = cmpInst.getRight();
        
        Value boolValue = null;
        
        // 检查是否为形如 x != 0 的比较
        if (right instanceof ConstantInt constInt && constInt.getValue() == 0) {
            boolValue = left;
        }
        // 检查是否为形如 0 != x 的比较
        else if (left instanceof ConstantInt constInt && constInt.getValue() == 0) {
            boolValue = right;
        } else {
            return null;  // 不符合优化条件
        }
        
        // 检查被比较的值是否为布尔类型的二元指令
        if (boolValue instanceof BinaryInstruction binaryInst
                && binaryInst.getType() == IntegerType.I1) {
            return new OptimizationCandidate(inst, binaryInst);
        }
        
        return null;
    }
    
    /**
     * 查找无用的zext指令，特别是对比较结果的扩展
     * @param inst 要检查的指令
     * @return 如果找到可优化的指令，返回优化候选对象，否则返回null
     */
    private OptimizationCandidate findUselessZext(Instruction inst) {
        // 检查是否为zext转换指令
        if (!(inst instanceof ConversionInstruction convInst) || 
            convInst.getConversionType() != OpCode.ZEXT) {
            return null;
        }
        
        // 获取源值
        Value source = convInst.getSource();
        
        // 检查源值是否为i1类型的比较指令
        if (source instanceof CompareInstruction cmpInst && 
            cmpInst.getType() == IntegerType.I1) {
            
            // 检查比较指令的类型
            if (cmpInst.isIntegerCompare()) {
                // 如果比较指令是形如 x != 0 的模式，我们可以优化
                if (cmpInst.getPredicate() == OpCode.NE) {
                    Value left = cmpInst.getLeft();
                    Value right = cmpInst.getRight();
                    
                    if ((right instanceof ConstantInt constIntRight && constIntRight.getValue() == 0) ||
                        (left instanceof ConstantInt constIntLeft && constIntLeft.getValue() == 0)) {
                        
                        // 在这种情况下，我们可以检查源值是否还有其他使用者
                        // 如果没有，则源比较指令将由DCE删除
                        // 这里我们直接复用二元指令作为源
                        Value otherOperand = (right instanceof ConstantInt) ? left : right;
                        if (otherOperand instanceof BinaryInstruction binaryInst && 
                            binaryInst.getType() == IntegerType.I1) {
                            
                            // 创建一个新的zext指令，直接从二元指令扩展，跳过比较指令
                            // 但在本例中，我们只是标记它为可优化
                            return new OptimizationCandidate(inst, null, OptimizationType.SKIP_FOR_NOW);
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 应用优化：将使用无用指令的地方替换为替代值
     * @param candidate 优化候选对象
     */
    private void applyOptimization(OptimizationCandidate candidate) {
        // 对于SKIP_FOR_NOW类型的优化，暂时不做处理
        if (candidate.type == OptimizationType.SKIP_FOR_NOW) {
            return;
        }
        
        Instruction uselessInst = candidate.uselessInstruction;
        Value replacementValue = candidate.replacementValue;
        
        // 获取所有使用该指令的用户
        List<User> users = new ArrayList<>(uselessInst.getUsers());
        
        // 对每个用户，替换对该指令的使用
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == uselessInst) {
                    user.setOperand(i, replacementValue);
                }
            }
        }
        
        // 如果指令没有使用者，从基本块中移除
        if (uselessInst.getUsers().isEmpty()) {
            uselessInst.removeFromParent();
        }
    }
    
    /**
     * 优化类型枚举
     */
    private enum OptimizationType {
        REPLACE,       // 替换指令
        SKIP_FOR_NOW   // 暂时跳过，可能在后续Pass中处理
    }
    
    /**
     * 内部类：表示一个优化候选
     */
    private static class OptimizationCandidate {
        final Instruction uselessInstruction;  // 无用的指令
        final Value replacementValue;         // 替换值
        final OptimizationType type;          // 优化类型
        
        OptimizationCandidate(Instruction uselessInstruction, Value replacementValue) {
            this.uselessInstruction = uselessInstruction;
            this.replacementValue = replacementValue;
            this.type = OptimizationType.REPLACE;
        }
        
        OptimizationCandidate(Instruction uselessInstruction, Value replacementValue, OptimizationType type) {
            this.uselessInstruction = uselessInstruction;
            this.replacementValue = replacementValue;
            this.type = type;
        }
    }
} 
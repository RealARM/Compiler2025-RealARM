package MiddleEnd.Optimization.Constant;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * 常量唯一化
 * 确保IR中相同值的常量只有一个实例，减少内存使用并简化后续优化
 */
public class ConstantDeduplication implements Optimizer.ModuleOptimizer {

    private final Map<Integer, ConstantInt> uniqueIntConstants = new HashMap<>();
    
    private final Map<Double, ConstantFloat> uniqueFloatConstants = new HashMap<>();

    @Override
    public String getName() {
        return "ConstantDeduplication";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        uniqueIntConstants.clear();
        uniqueFloatConstants.clear();
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= processFunction(function);
        }
        
        return changed;
    }
    
    private boolean processFunction(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (Instruction inst : instructions) {
                for (int i = 0; i < inst.getOperandCount(); i++) {
                    Value operand = inst.getOperand(i);
                    
                    if (operand instanceof ConstantInt constInt) {
                        ConstantInt ConstantDeduplicationInt = getConstantDeduplicationantInt(constInt.getValue());
                        if (ConstantDeduplicationInt != operand) {
                            inst.setOperand(i, ConstantDeduplicationInt);
                            changed = true;
                        }
                    } else if (operand instanceof ConstantFloat constFloat) {
                        ConstantFloat ConstantDeduplicationFloat = getConstantDeduplicationantFloat(constFloat.getValue());
                        if (ConstantDeduplicationFloat != operand) {
                            inst.setOperand(i, ConstantDeduplicationFloat);
                            changed = true;
                        }
                    }
                }
            }
        }
        
        return changed;
    }
    
    private ConstantInt getConstantDeduplicationantInt(int value) {
        return uniqueIntConstants.computeIfAbsent(value, ConstantInt::new);
    }
    
    private ConstantFloat getConstantDeduplicationantFloat(double value) {
        return uniqueFloatConstants.computeIfAbsent(value, ConstantFloat::new);
    }
} 
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
 * 常量唯一化优化Pass
 * 确保IR中相同值的常量只有一个实例，减少内存使用并简化后续优化
 */
public class ConstantDeduplication implements Optimizer.ModuleOptimizer {

    // 整数常量映射表：值 -> 唯一常量实例
    private final Map<Integer, ConstantInt> uniqueIntConstants = new HashMap<>();
    
    // 浮点常量映射表：值 -> 唯一常量实例
    private final Map<Double, ConstantFloat> uniqueFloatConstants = new HashMap<>();

    @Override
    public String getName() {
        return "ConstantDeduplication";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        // 清空常量映射表
        uniqueIntConstants.clear();
        uniqueFloatConstants.clear();
        
        // 对模块中的每个函数进行处理
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= processFunction(function);
        }
        
        return changed;
    }
    
    /**
     * 处理函数中的常量
     * @param function 要处理的函数
     * @return 是否发生了变化
     */
    private boolean processFunction(Function function) {
        boolean changed = false;
        
        // 处理函数中的每个基本块
        for (BasicBlock block : function.getBasicBlocks()) {
            // 收集所有指令，因为我们会在迭代过程中修改指令列表
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (Instruction inst : instructions) {
                // 检查指令的每个操作数
                for (int i = 0; i < inst.getOperandCount(); i++) {
                    Value operand = inst.getOperand(i);
                    
                    if (operand instanceof ConstantInt constInt) {
                        // 处理整数常量
                        ConstantInt ConstantDeduplicationInt = getConstantDeduplicationantInt(constInt.getValue());
                        if (ConstantDeduplicationInt != operand) {
                            inst.setOperand(i, ConstantDeduplicationInt);
                            changed = true;
                        }
                    } else if (operand instanceof ConstantFloat constFloat) {
                        // 处理浮点常量
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
    
    /**
     * 获取或创建唯一的整数常量
     * @param value 常量值
     * @return 唯一的常量实例
     */
    private ConstantInt getConstantDeduplicationantInt(int value) {
        return uniqueIntConstants.computeIfAbsent(value, ConstantInt::new);
    }
    
    /**
     * 获取或创建唯一的浮点常量
     * @param value 常量值
     * @return 唯一的常量实例
     */
    private ConstantFloat getConstantDeduplicationantFloat(double value) {
        return uniqueFloatConstants.computeIfAbsent(value, ConstantFloat::new);
    }
} 
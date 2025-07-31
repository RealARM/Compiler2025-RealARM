package MiddleEnd.Optimization.Constant;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Constant;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 常量传播优化
 * 将在编译期可确定值的变量替换为常量
 */
public class ConstantPropagation implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "ConstantPropagation";
    }

    // 值映射表，记录变量对应的常量
    private final Map<Value, Constant> valueMap = new HashMap<>();
    
    // 已访问的指令集合，避免循环依赖
    private final Set<Instruction> visited = new HashSet<>();

    @Override
    public boolean run(Module module) {
        boolean modified = false;
        
        // 对模块中的每个函数进行常量传播
        for (Function function : module.functions()) {
            // 跳过外部函数
            if (function.isExternal()) {
                continue;
            }
            
            // 清空映射表和已访问集合
            valueMap.clear();
            visited.clear();
            
            // 对函数执行常量传播
            modified |= propagateConstants(function);
        }
        
        return modified;
    }
    
    /**
     * 对函数执行常量传播
     * @param function 待优化的函数
     * @return 如果函数被修改则返回true
     */
    private boolean propagateConstants(Function function) {
        boolean changed = false;
        boolean localChanged;
        
        // 进行多轮传播，直到没有新的常量被发现
        do {
            localChanged = false;
            
            // 对每个基本块进行处理
            for (BasicBlock block : function.getBasicBlocks()) {
                // 处理基本块中的每条指令
                for (Instruction inst : block.getInstructions()) {
                    // 尝试计算指令的常量结果
                    Constant constResult = evaluateConstant(inst);
                    
                    if (constResult != null) {
                        // 记录指令对应的常量值
                        valueMap.put(inst, constResult);
                        localChanged = true;
                    }
                    
                    // 尝试用常量替换操作数
                    boolean operandsReplaced = tryReplaceOperands(inst);
                    
                    // 如果指令结果是常量或者操作数被替换，则标记变化
                    if (constResult != null || operandsReplaced) {
                        changed = true;
                        localChanged = true;
                    }
                }
            }
            
            // 应用常量替换
            if (localChanged) {
                applyConstantReplacements(function);
            }
        } while (localChanged);
        
        return changed;
    }
    
    /**
     * 尝试计算指令的常量结果
     * @param inst 要计算的指令
     * @return 如果能计算出常量结果则返回常量，否则返回null
     */
    private Constant evaluateConstant(Instruction inst) {
        // 避免重复计算
        if (visited.contains(inst)) {
            return valueMap.get(inst);
        }
        
        visited.add(inst);
        
        // 调用ConstantExpressionEvaluator进行求值
        if (canEvaluateStatically(inst)) {
            return ConstantExpressionEvaluator.evaluate(inst);
        }
        
        return null;
    }
    
    /**
     * 判断指令是否可以静态求值
     * @param inst 要判断的指令
     * @return 如果可以静态求值返回true
     */
    private boolean canEvaluateStatically(Instruction inst) {
        // 只有特定类型的指令可以静态求值
        if (inst instanceof BinaryInstruction || 
            inst instanceof CompareInstruction || 
            inst instanceof ConversionInstruction) {
            
            // 所有操作数都是常量才能静态求值
            for (Value operand : inst.getOperands()) {
                if (!(operand instanceof Constant)) {
                    // 如果操作数在映射表中有对应常量，也认为是常量
                    if (!valueMap.containsKey(operand)) {
                        return false;
                    }
                }
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * 尝试用常量替换指令的操作数
     * @param inst 要处理的指令
     * @return 如果有操作数被替换则返回true
     */
    private boolean tryReplaceOperands(Instruction inst) {
        boolean replaced = false;
        
        // 检查每个操作数是否可以替换为常量
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            
            // 如果操作数在映射表中有常量值，替换它
            if (valueMap.containsKey(operand)) {
                inst.setOperand(i, valueMap.get(operand));
                replaced = true;
            }
        }
        
        return replaced;
    }
    
    /**
     * 应用常量替换到函数中
     * @param function 要处理的函数
     */
    private void applyConstantReplacements(Function function) {
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : new ArrayList<>(block.getInstructions())) {
                // 如果指令在映射表中有常量值，替换其所有使用
                if (valueMap.containsKey(inst)) {
                    Constant constValue = valueMap.get(inst);
                    
                    // 遍历使用此指令的所有用户
                    for (Value user : new ArrayList<>(inst.getUsers())) {
                        if (user instanceof Instruction) {
                            // 在用户指令中替换此指令为常量值
                            for (int i = 0; i < ((Instruction) user).getOperandCount(); i++) {
                                if (((Instruction) user).getOperand(i) == inst) {
                                    ((Instruction) user).setOperand(i, constValue);
                                }
                            }
                        }
                    }
                    
                    // 如果指令没有用户了，可以移除它
                    if (!inst.isUsed()) {
                        inst.removeFromParent();
                    }
                }
            }
        }
    }
} 
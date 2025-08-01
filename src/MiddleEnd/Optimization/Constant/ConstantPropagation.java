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
 * 常量传播
 * 将在编译期可确定值的变量替换为常量
 */
public class ConstantPropagation implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "ConstantPropagation";
    }

    private final Map<Value, Constant> valueMap = new HashMap<>();
    
    private final Set<Instruction> visited = new HashSet<>();

    @Override
    public boolean run(Module module) {
        boolean modified = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            valueMap.clear();
            visited.clear();
            
            modified |= propagateConstants(function);
        }
        
        return modified;
    }
    
    private boolean propagateConstants(Function function) {
        boolean changed = false;
        boolean localChanged;
        
        do {
            localChanged = false;
            
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    Constant constResult = evaluateConstant(inst);
                    
                    if (constResult != null) {
                        valueMap.put(inst, constResult);
                        localChanged = true;
                    }
                    
                    boolean operandsReplaced = tryReplaceOperands(inst);
                    
                    if (constResult != null || operandsReplaced) {
                        changed = true;
                        localChanged = true;
                    }
                }
            }
            
            if (localChanged) {
                applyConstantReplacements(function);
            }
        } while (localChanged);
        
        return changed;
    }
    
    private Constant evaluateConstant(Instruction inst) {
        if (visited.contains(inst)) {
            return valueMap.get(inst);
        }
        
        visited.add(inst);
        
        if (canEvaluateStatically(inst)) {
            return ConstantExpressionEvaluator.evaluate(inst);
        }
        
        return null;
    }
    
    private boolean canEvaluateStatically(Instruction inst) {
        if (inst instanceof BinaryInstruction || 
            inst instanceof CompareInstruction || 
            inst instanceof ConversionInstruction) {
            
            for (Value operand : inst.getOperands()) {
                if (!(operand instanceof Constant)) {
                    if (!valueMap.containsKey(operand)) {
                        return false;
                    }
                }
            }
            return true;
        }
        
        return false;
    }
    
    private boolean tryReplaceOperands(Instruction inst) {
        boolean replaced = false;
        
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            
            if (valueMap.containsKey(operand)) {
                inst.setOperand(i, valueMap.get(operand));
                replaced = true;
            }
        }
        
        return replaced;
    }
    
    private void applyConstantReplacements(Function function) {
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : new ArrayList<>(block.getInstructions())) {
                if (valueMap.containsKey(inst)) {
                    Constant constValue = valueMap.get(inst);
                    
                    for (Value user : new ArrayList<>(inst.getUsers())) {
                        if (user instanceof Instruction) {
                            for (int i = 0; i < ((Instruction) user).getOperandCount(); i++) {
                                if (((Instruction) user).getOperand(i) == inst) {
                                    ((Instruction) user).setOperand(i, constValue);
                                }
                            }
                        }
                    }
                    
                    if (!inst.isUsed()) {
                        inst.removeFromParent();
                    }
                }
            }
        }
    }
} 
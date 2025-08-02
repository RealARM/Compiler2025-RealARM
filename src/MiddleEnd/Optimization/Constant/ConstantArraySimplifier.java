package MiddleEnd.Optimization.Constant;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Type.PointerType;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 常量数组访问优化Pass
 * 在编译时计算对常量数组的访问，避免运行时的内存访问
 */
public class ConstantArraySimplifier implements Optimizer.ModuleOptimizer {

    @Override
    public String getName() {
        return "ConstantArraySimplifier";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            for (BasicBlock block : function.getBasicBlocks()) {
                List<Instruction> instructions = new ArrayList<>(block.getInstructions());
                
                for (Instruction inst : instructions) {
                    if (inst instanceof LoadInstruction loadInst) {
                        Value pointer = loadInst.getPointer();
                        
                        if (pointer instanceof GetElementPtrInstruction gepInst) {
                            Constant constantValue = foldConstantArrayAccess(gepInst);
                            if (constantValue != null) {
                                replaceAllUses(loadInst, constantValue);
                                
                                loadInst.removeFromParent();
                                
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        
        return changed;
    }
    
    private Constant foldConstantArrayAccess(GetElementPtrInstruction gepInst) {        
        Value basePointer = gepInst.getOperand(0);
        
        if (gepInst.getOperandCount() < 2) {
            return null;
        }
        
        Value indexValue = gepInst.getOperand(1);
        if (!(indexValue instanceof ConstantInt)) {
            return null;
        }
        
        int index = ((ConstantInt) indexValue).getValue();
        
        Constant constantValue = null;
        
        if (basePointer instanceof GlobalVariable globalVar) {
            if (!globalVar.isConstant()) {
                return null;
            }
            
            if (!isConstantArray(globalVar)) {
                return null;
            }
            
            if (index >= 0 && index < globalVar.getArraySize()) {
                List<Value> arrayValues = getArrayValues(globalVar);
                if (arrayValues != null && index < arrayValues.size()) {
                    constantValue = (Constant) arrayValues.get(index);
                }
            }
        }
        else if (basePointer instanceof AllocaInstruction allocaInst) {
            if (!isConstantArray(allocaInst)) {
                return null;
            }
            
            List<Value> arrayValues = getArrayValues(allocaInst);
            if (index >= 0 && arrayValues != null && index < arrayValues.size()) {
                constantValue = (Constant) arrayValues.get(index);
            }
        }
        
        return constantValue;
    }
    
    private boolean isConstantArray(Value value) {
        if (value instanceof GlobalVariable globalVar) {
            return globalVar.isArray() && globalVar.isConstant();
        } else if (value instanceof AllocaInstruction allocaInst) {
            return allocaInst.getType() instanceof PointerType && 
                   allocaInst.getName().contains("array");
        }
        return false;
    }

    private List<Value> getArrayValues(Value arrayValue) {
        if (arrayValue instanceof GlobalVariable globalVar) {
            if (globalVar.isArray() && globalVar.hasInitializer()) {
                return globalVar.getArrayValues();
            }
        } else if (arrayValue instanceof AllocaInstruction) {
            return null;
        }
        return null;
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
} 
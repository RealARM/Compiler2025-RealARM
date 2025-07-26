package IR.Pass;

import IR.Module;
import IR.Type.PointerType;
import IR.Value.BasicBlock;
import IR.Value.Constant;
import IR.Value.ConstantInt;
import IR.Value.Function;
import IR.Value.GlobalVariable;
import IR.Value.Instructions.AllocaInstruction;
import IR.Value.Instructions.GetElementPtrInstruction;
import IR.Value.Instructions.Instruction;
import IR.Value.Instructions.LoadInstruction;
import IR.Value.Value;
import IR.Value.User;

import java.util.ArrayList;
import java.util.List;

/**
 * 常量数组访问优化Pass
 * 在编译时计算对常量数组的访问，避免运行时的内存访问
 */
public class ConstantArraySimplifier implements Pass.IRPass {

    @Override
    public String getName() {
        return "ConstantArraySimplifier";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        // 对模块中的每个函数进行处理
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            // 处理函数中的每个基本块
            for (BasicBlock block : function.getBasicBlocks()) {
                // 创建指令的副本，因为我们可能会修改指令列表
                List<Instruction> instructions = new ArrayList<>(block.getInstructions());
                
                for (Instruction inst : instructions) {
                    // 查找形如 load (gep array, index) 的模式
                    if (inst instanceof LoadInstruction loadInst) {
                        // 检查是否是从数组元素加载
                        Value pointer = loadInst.getPointer();
                        
                        if (pointer instanceof GetElementPtrInstruction gepInst) {
                            // 尝试常量折叠
                            Constant constantValue = foldConstantArrayAccess(gepInst);
                            if (constantValue != null) {
                                // 替换load指令的所有使用为常量值
                                replaceAllUses(loadInst, constantValue);
                                
                                // 从基本块中移除load指令
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
    
    /**
     * 尝试折叠常量数组访问
     * @param gepInst GEP指令
     * @return 如果可以折叠则返回常量值，否则返回null
     */
    private Constant foldConstantArrayAccess(GetElementPtrInstruction gepInst) {
        // 获取GEP指令的基址和索引
        Value basePointer = gepInst.getOperand(0);
        
        // 必须有至少一个索引操作数
        if (gepInst.getOperandCount() < 2) {
            return null;
        }
        
        // 获取索引值，只处理常量索引
        Value indexValue = gepInst.getOperand(1);
        if (!(indexValue instanceof ConstantInt)) {
            return null;
        }
        
        int index = ((ConstantInt) indexValue).getValue();
        
        // 根据基址的类型进行处理
        Constant constantValue = null;
        
        // 处理全局变量数组
        if (basePointer instanceof GlobalVariable globalVar) {
            // 只处理常量全局变量
            if (!globalVar.isConstant()) {
                return null;
            }
            
            // 检查是否是数组类型
            if (!isConstantArray(globalVar)) {
                return null;
            }
            
            // 获取数组元素值
            if (index >= 0 && index < globalVar.getArraySize()) {
                // 这里需要获取全局变量数组的元素值
                // 在你的API中，可能需要通过其他方式获取
                List<Value> arrayValues = getArrayValues(globalVar);
                if (arrayValues != null && index < arrayValues.size()) {
                    constantValue = (Constant) arrayValues.get(index);
                }
            }
        }
        // 处理局部数组变量（仅对初始化为常量的局部数组有效）
        else if (basePointer instanceof AllocaInstruction allocaInst) {
            // 检查是否是常量数组
            if (!isConstantArray(allocaInst)) {
                return null;
            }
            
            // 获取数组元素值
            List<Value> arrayValues = getArrayValues(allocaInst);
            if (index >= 0 && arrayValues != null && index < arrayValues.size()) {
                constantValue = (Constant) arrayValues.get(index);
            }
        }
        
        return constantValue;
    }
    
    /**
     * 检查值是否为常量数组
     */
    private boolean isConstantArray(Value value) {
        if (value instanceof GlobalVariable globalVar) {
            return globalVar.isArray() && globalVar.isConstant();
        } else if (value instanceof AllocaInstruction allocaInst) {
            // 在你的API中需要实现适当的检查方法
            // 这里简化处理，实际应该检查allocaInst是否为数组类型且有常量初始化值
            return allocaInst.getType() instanceof PointerType && 
                   allocaInst.getName().contains("array");
        }
        return false;
    }
    
    /**
     * 获取数组的值列表
     */
    private List<Value> getArrayValues(Value arrayValue) {
        if (arrayValue instanceof GlobalVariable globalVar) {
            // 这里需要根据你的API实现，获取全局变量数组的元素值
            if (globalVar.isArray() && globalVar.hasInitializer()) {
                // 使用你的API正确方式获取全局变量数组的元素值
                return globalVar.getArrayValues(); // 如果此方法不存在，需要替换为你API中的正确方法
            }
        } else if (arrayValue instanceof AllocaInstruction) {
            // 对于局部数组，可能需要通过其他方式获取初始化值
            // 这部分实现取决于你的编译器如何表示局部数组的初始化值
            return null; // 简化处理，实际中可能无法获取局部数组的常量值
        }
        return null;
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
} 
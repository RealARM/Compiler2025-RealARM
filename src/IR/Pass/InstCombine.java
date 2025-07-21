package IR.Pass;

import IR.Module;
import IR.OpCode;
import IR.Type.IntegerType;
import IR.Type.Type;
import IR.Value.BasicBlock;
import IR.Value.ConstantInt;
import IR.Value.Function;
import IR.Value.Instructions.BinaryInstruction;
import IR.Value.Instructions.Instruction;
import IR.Value.Value;
import IR.Value.User;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令组合优化Pass - 安全版本
 * 只执行确定安全的优化
 */
public class InstCombine implements Pass.IRPass {
    
    @Override
    public String getName() {
        return "InstructionCombine";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= runOnFunction(function);
        }
        
        return changed;
    }
    
    /**
     * 对函数执行指令组合优化
     */
    private boolean runOnFunction(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            // 使用列表而不是直接迭代，因为我们可能会修改指令
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (Instruction inst : instructions) {
                // 只处理仍然在块中的指令
                if (inst.getParent() != block) {
                    continue;
                }
                
                // 只处理二元运算指令
                if (inst instanceof BinaryInstruction binInst) {
                    boolean instChanged = optimizeBinaryInstruction(binInst);
                    changed |= instChanged;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 优化二元运算指令
     * @return 如果指令被优化则返回true
     */
    private boolean optimizeBinaryInstruction(BinaryInstruction binInst) {
        OpCode opcode = binInst.getOpCode();
        Value left = binInst.getOperand(0);
        Value right = binInst.getOperand(1);
        
        // 只处理几个绝对安全的情况
        
        // X + 0 = X
        if (opcode == OpCode.ADD && isConstantZero(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        // 0 + X = X
        if (opcode == OpCode.ADD && isConstantZero(left)) {
            replaceInstruction(binInst, right);
            return true;
        }
        
        // X - 0 = X
        if (opcode == OpCode.SUB && isConstantZero(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        // X * 1 = X
        if (opcode == OpCode.MUL && isConstantOne(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        // 1 * X = X
        if (opcode == OpCode.MUL && isConstantOne(left)) {
            replaceInstruction(binInst, right);
            return true;
        }
        
        // X * 0 = 0
        if (opcode == OpCode.MUL && (isConstantZero(left) || isConstantZero(right))) {
            Type type = binInst.getType();
            if (type instanceof IntegerType) {
                ConstantInt zero = new ConstantInt(0, (IntegerType) type);
                replaceInstruction(binInst, zero);
                return true;
            }
        }
        
        // X / 1 = X
        if (opcode == OpCode.DIV && isConstantOne(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查值是否为常量0
     */
    private boolean isConstantZero(Value value) {
        return value instanceof ConstantInt && ((ConstantInt) value).getValue() == 0;
    }
    
    /**
     * 检查值是否为常量1
     */
    private boolean isConstantOne(Value value) {
        return value instanceof ConstantInt && ((ConstantInt) value).getValue() == 1;
    }
    
    /**
     * 替换指令
     */
    private void replaceInstruction(Instruction oldInst, Value newValue) {
        // 保存所有使用oldInst的指令
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
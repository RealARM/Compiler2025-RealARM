package MiddleEnd.Optimization.Instruction;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令组合优化
 */
public class InstCombine implements Optimizer.ModuleOptimizer {
    
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
    
    private boolean runOnFunction(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (Instruction inst : instructions) {
                if (inst.getParent() != block) {
                    continue;
                }
                
                if (inst instanceof BinaryInstruction binInst) {
                    boolean instChanged = optimizeBinaryInstruction(binInst);
                    changed |= instChanged;
                }
                
                if (inst instanceof CompareInstruction cmpInst) {
                    boolean instChanged = optimizeCompareInstruction(cmpInst);
                    changed |= instChanged;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 优化比较指令
     * 对形如 i * const < const 或 i + const < const 的比较指令进行优化
     * @return 如果指令被优化则返回true
     */
    private boolean optimizeCompareInstruction(CompareInstruction cmpInst) {
        Value left = cmpInst.getLeft();
        Value right = cmpInst.getRight();
        
        if (!cmpInst.isIntegerCompare()) {
            return false;
        }
        
        if (!(right instanceof ConstantInt rightConst)) {
            return false;
        }
        
        if (left instanceof BinaryInstruction binInst) {
            return optimizeCompareWithBinary(cmpInst, binInst, rightConst);
        }
        
        return false;
    }
    
    /**
     * 优化左操作数是二元指令的比较指令
     */
    private boolean optimizeCompareWithBinary(CompareInstruction cmpInst, BinaryInstruction binInst, ConstantInt rightConst) {
        Value binLeft = binInst.getOperand(0);
        Value binRight = binInst.getOperand(1);
        OpCode binOpCode = binInst.getOpCode();
        int rightValue = rightConst.getValue();
        
        if (binRight instanceof ConstantInt binRightConst) {
            int binRightValue = binRightConst.getValue();
            
            // 处理加法：(x + const1) < const2 => x < (const2 - const1)
            if (binOpCode == OpCode.ADD) {
                ConstantInt newConst = new ConstantInt(rightValue - binRightValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binLeft);
                cmpInst.setOperand(1, newConst);
                return true;
            }
            
            // 处理减法：(x - const1) < const2 => x < (const2 + const1)
            else if (binOpCode == OpCode.SUB) {
                ConstantInt newConst = new ConstantInt(rightValue + binRightValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binLeft);
                cmpInst.setOperand(1, newConst);
                return true;
            }
            
            // 处理乘法：(x * const1) < const2 => x < (const2 / const1)，仅当const1为非零且能整除时
            else if (binOpCode == OpCode.MUL && binRightValue != 0 && rightValue % binRightValue == 0) {
                ConstantInt newConst = new ConstantInt(rightValue / binRightValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binLeft);
                cmpInst.setOperand(1, newConst);
                return true;
            }
        }
        
        else if (binLeft instanceof ConstantInt binLeftConst) {
            int binLeftValue = binLeftConst.getValue();
            
            // 处理加法：(const1 + x) < const2 => x < (const2 - const1)
            if (binOpCode == OpCode.ADD) {
                ConstantInt newConst = new ConstantInt(rightValue - binLeftValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binRight);
                cmpInst.setOperand(1, newConst);
                return true;
            }
            
            // 处理乘法：(const1 * x) < const2 => x < (const2 / const1)，仅当const1为非零且能整除时
            else if (binOpCode == OpCode.MUL && binLeftValue != 0 && rightValue % binLeftValue == 0) {
                ConstantInt newConst = new ConstantInt(rightValue / binLeftValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binRight);
                cmpInst.setOperand(1, newConst);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 优化二元运算指令
     * @return 如果指令被优化则返回true
     */
    private boolean optimizeBinaryInstruction(BinaryInstruction binInst) {
        OpCode opcode = binInst.getOpCode();
        Value left = binInst.getOperand(0);
        Value right = binInst.getOperand(1);
        
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
    
    private boolean isConstantZero(Value value) {
        return value instanceof ConstantInt && ((ConstantInt) value).getValue() == 0;
    }
    
    private boolean isConstantOne(Value value) {
        return value instanceof ConstantInt && ((ConstantInt) value).getValue() == 1;
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
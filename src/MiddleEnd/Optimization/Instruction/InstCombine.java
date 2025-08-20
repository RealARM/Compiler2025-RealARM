package MiddleEnd.Optimization.Instruction;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.Type.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令组合优化 - 增强版
 * 通过代数简化、常量折叠等技术合并和简化指令序列
 */
public class InstCombine implements Optimizer.ModuleOptimizer {

    private boolean changed = false;
    
    @Override
    public String getName() {
        return "InstCombine";
    }

    @Override
    public boolean run(Module module) {
        boolean globalChanged = false;
        
        // 多轮迭代直到不再有改变
        boolean roundChanged;
        int rounds = 0;
        do {
            roundChanged = false;
            rounds++;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                    continue;
                }
                
                roundChanged |= runOnFunction(function);
            }
            globalChanged |= roundChanged;
            
            // 防止无限循环
            if (rounds > 10) break;
            
        } while (roundChanged);
        
        return globalChanged;
    }
    
    private boolean runOnFunction(Function function) {
        changed = false;
        
        // 收集所有指令，避免在迭代中修改集合
        List<Instruction> allInstructions = new ArrayList<>();
        for (BasicBlock block : function.getBasicBlocks()) {
            allInstructions.addAll(new ArrayList<>(block.getInstructions()));
        }
        
        for (Instruction inst : allInstructions) {
            // 检查指令是否仍然在函数中（可能被之前的优化删除）
            if (inst.getParent() == null) {
                continue;
            }
            
            if (inst instanceof BinaryInstruction) {
                optimizeBinaryInstruction((BinaryInstruction) inst);
            } else if (inst instanceof CompareInstruction) {
                optimizeCompareInstruction((CompareInstruction) inst);
            } else if (inst instanceof BranchInstruction) {
                optimizeBranchInstruction((BranchInstruction) inst);
            } else if (inst instanceof ConversionInstruction) {
                optimizeConversionInstruction((ConversionInstruction) inst);
            } else if (inst instanceof LoadInstruction) {
                optimizeLoadInstruction((LoadInstruction) inst);
            } else if (inst instanceof CallInstruction) {
                optimizeCallInstruction((CallInstruction) inst);
            }
        }
        
        return changed;
    }
    
        private boolean optimizeBinaryInstruction(BinaryInstruction binInst) {
        Value left = binInst.getLeft();
        Value right = binInst.getRight();
        OpCode opcode = binInst.getOpCode();

        // 只启用非常安全、不影响溢出行为的优化

        // 1. 明显的零操作优化（不改变溢出行为）
        if (opcode == OpCode.ADD) {
            // x + 0 = x (安全：不改变溢出行为)
            if (isConstantZero(right)) {
                replaceInstruction(binInst, left);
                return true;
            }
            if (isConstantZero(left)) {
                replaceInstruction(binInst, right);
                return true;
            }
        }

        if (opcode == OpCode.SUB) {
            // x - 0 = x (安全：不改变溢出行为)
            if (isConstantZero(right)) {
                replaceInstruction(binInst, left);
                return true;
            }
        }

        if (opcode == OpCode.MUL) {
            // x * 0 = 0 (安全：结果确定)
            if (isConstantZero(left) || isConstantZero(right)) {
                replaceInstruction(binInst, new ConstantInt(0));
                return true;
            }
            // x * 1 = x (安全：不改变值)
            if (isConstantOne(right)) {
                replaceInstruction(binInst, left);
                return true;
            }
            if (isConstantOne(left)) {
                replaceInstruction(binInst, right);
                return true;
            }
        }

        // 2. 位运算的明显优化（不涉及溢出）
        if (opcode == OpCode.AND) {
            // x & 0 = 0 (安全)
            if (isConstantZero(left) || isConstantZero(right)) {
                replaceInstruction(binInst, new ConstantInt(0));
                return true;
            }
            // x & x = x (安全)
            if (left.equals(right)) {
                replaceInstruction(binInst, left);
                return true;
            }
        }

        if (opcode == OpCode.OR) {
            // x | 0 = x (安全)
            if (isConstantZero(right)) {
                replaceInstruction(binInst, left);
                return true;
            }
            if (isConstantZero(left)) {
                replaceInstruction(binInst, right);
                return true;
            }
            // x | x = x (安全)
            if (left.equals(right)) {
                replaceInstruction(binInst, left);
                return true;
            }
        }

        if (opcode == OpCode.XOR) {
            // x ^ 0 = x (安全)
            if (isConstantZero(right)) {
                replaceInstruction(binInst, left);
                return true;
            }
            if (isConstantZero(left)) {
                replaceInstruction(binInst, right);
                return true;
            }
            // x ^ x = 0 (安全)
            if (left.equals(right)) {
                replaceInstruction(binInst, new ConstantInt(0));
                return true;
            }
        }

        // 3. 只有两个操作数都是常量时才进行常量折叠（安全）
        if (left instanceof ConstantInt && right instanceof ConstantInt) {
            Value result = foldConstants(left, right, opcode);
            if (result != null) {
                replaceInstruction(binInst, result);
                return true;
            }
        }

        return false;
    }
    
    private Value simplifyAlgebraicIdentities(Value left, Value right, OpCode opcode) {
        switch (opcode) {
            case ADD:
                // x + 0 = x
                if (isConstantZero(right)) return left;
                if (isConstantZero(left)) return right;
                // x + (-x) = 0 (需要检测取负模式)
                if (isNegation(left, right) || isNegation(right, left)) {
                    return new ConstantInt(0);
                }
                break;
                
            case SUB:
                // x - 0 = x
                if (isConstantZero(right)) return left;
                // x - x = 0
                if (left.equals(right)) return new ConstantInt(0);
                // 0 - x = -x
                if (isConstantZero(left)) {
                    return createNegation(right);
                }
                break;
                
            case MUL:
                // x * 0 = 0
                if (isConstantZero(left) || isConstantZero(right)) {
                    return new ConstantInt(0);
                }
                // x * 1 = x
                if (isConstantOne(right)) return left;
                if (isConstantOne(left)) return right;
                // x * -1 = -x
                if (isConstantMinusOne(right)) return createNegation(left);
                if (isConstantMinusOne(left)) return createNegation(right);
                break;
                
            case DIV:
                // x / 1 = x
                if (isConstantOne(right)) return left;
                // x / x = 1 (假设x != 0)
                if (left.equals(right)) return new ConstantInt(1);
                // 0 / x = 0 (假设x != 0)
                if (isConstantZero(left)) return new ConstantInt(0);
                break;
                
            case REM:
                // x % 1 = 0
                if (isConstantOne(right)) return new ConstantInt(0);
                // x % x = 0
                if (left.equals(right)) return new ConstantInt(0);
                // 0 % x = 0
                if (isConstantZero(left)) return new ConstantInt(0);
                break;
                
            case AND:
                // x & 0 = 0
                if (isConstantZero(left) || isConstantZero(right)) {
                    return new ConstantInt(0);
                }
                // x & -1 = x (全1)
                if (isConstantMinusOne(right)) return left;
                if (isConstantMinusOne(left)) return right;
                // x & x = x
                if (left.equals(right)) return left;
                break;
                
            case OR:
                // x | 0 = x
                if (isConstantZero(right)) return left;
                if (isConstantZero(left)) return right;
                // x | -1 = -1 (全1)
                if (isConstantMinusOne(left) || isConstantMinusOne(right)) {
                    return new ConstantInt(-1);
                }
                // x | x = x
                if (left.equals(right)) return left;
                break;
                
            case XOR:
                // x ^ 0 = x
                if (isConstantZero(right)) return left;
                if (isConstantZero(left)) return right;
                // x ^ x = 0
                if (left.equals(right)) return new ConstantInt(0);
                break;
        }
        
        return null;
    }
    
    private Value simplifyWithConstant(Value left, Value right, OpCode opcode) {
        ConstantInt constVal = null;
        Value varVal = null;
        
        if (left instanceof ConstantInt) {
            constVal = (ConstantInt) left;
            varVal = right;
        } else if (right instanceof ConstantInt) {
            constVal = (ConstantInt) right;
            varVal = left;
        } else {
            return null;
        }
        
        int constIntVal = constVal.getValue();
        
        switch (opcode) {
            case MUL:
                                 // 乘以2的幂，转换为左移
                 if (isPowerOfTwo(constIntVal) && constIntVal > 0) {
                     int shiftAmount = Integer.numberOfTrailingZeros(constIntVal);
                     return new BinaryInstruction(OpCode.SHL, varVal, 
                                                new ConstantInt(shiftAmount), varVal.getType());
                 }
                 break;
                 
             case DIV:
                 // 除以2的幂，转换为右移
                 if (isPowerOfTwo(constIntVal) && constIntVal > 0 && right == constVal) {
                     int shiftAmount = Integer.numberOfTrailingZeros(constIntVal);
                     return new BinaryInstruction(OpCode.ASHR, varVal, 
                                                new ConstantInt(shiftAmount), varVal.getType());
                 }
                 break;
                 
             case ADD:
                 // 添加负数转换为减法
                 if (constIntVal < 0 && right == constVal) {
                     return new BinaryInstruction(OpCode.SUB, varVal, 
                                                new ConstantInt(-constIntVal), varVal.getType());
                 }
                 break;
                 
             case SUB:
                 // 减去负数转换为加法
                 if (constIntVal < 0 && right == constVal) {
                     return new BinaryInstruction(OpCode.ADD, varVal, 
                                                new ConstantInt(-constIntVal), varVal.getType());
                 }
                break;
        }
        
        return null;
    }
    
    private Value strengthReduction(Value left, Value right, OpCode opcode) {
        // 将昂贵的操作转换为便宜的操作
        if (opcode == OpCode.MUL && right instanceof ConstantInt) {
            int val = ((ConstantInt) right).getValue();
            
            // x * 0 = 0 (已在代数恒等式中处理)
            // x * 1 = x (已在代数恒等式中处理)
            
            // x * 2 = x + x
            if (val == 2) {
                return new BinaryInstruction(OpCode.ADD, left, left, left.getType());
            }
            
            // 对于复合表达式，暂时禁用以避免支配关系问题
            // 这些优化需要更复杂的插入逻辑来确保正确性
            
            // TODO: 重新实现这些优化，确保正确的指令插入顺序
            // x * 3 = x + x + x 可以转换为 x + (x << 1)
            // x * 5 = x + (x << 2)  
            // x * 9 = x + (x << 3)
        }
        
        return null;
    }
    
    private Value reassociateExpression(BinaryInstruction binInst) {
        // 表达式重组以便更好的常量折叠
        // (x + c1) + c2 = x + (c1 + c2)
        // (x * c1) * c2 = x * (c1 * c2)
        
        OpCode opcode = binInst.getOpCode();
        Value left = binInst.getLeft();
        Value right = binInst.getRight();
        
        if ((opcode == OpCode.ADD || opcode == OpCode.MUL) && right instanceof ConstantInt) {
            if (left instanceof BinaryInstruction) {
                BinaryInstruction leftBin = (BinaryInstruction) left;
                if (leftBin.getOpCode() == opcode && leftBin.getRight() instanceof ConstantInt) {
                    ConstantInt c1 = (ConstantInt) leftBin.getRight();
                    ConstantInt c2 = (ConstantInt) right;
                    
                    Value newConst = foldConstants(c1, c2, opcode);
                    if (newConst != null) {
                        return new BinaryInstruction(opcode, leftBin.getLeft(), newConst, leftBin.getType());
                    }
                }
            }
        }
        
        return null;
    }
    
    private Value recognizeSpecialPatterns(Value left, Value right, OpCode opcode) {
        // 识别特殊模式
        
        // (x & mask) == 0 的优化
        if (opcode == OpCode.AND) {
            // 检查是否有冗余的与操作
            if (left instanceof BinaryInstruction && ((BinaryInstruction) left).getOpCode() == OpCode.AND) {
                BinaryInstruction leftAnd = (BinaryInstruction) left;
                if (leftAnd.getRight() instanceof ConstantInt && right instanceof ConstantInt) {
                    // (x & c1) & c2 = x & (c1 & c2)
                    ConstantInt c1 = (ConstantInt) leftAnd.getRight();
                    ConstantInt c2 = (ConstantInt) right;
                                         int newMask = c1.getValue() & c2.getValue();
                     return new BinaryInstruction(OpCode.AND, leftAnd.getLeft(), 
                                                new ConstantInt(newMask), leftAnd.getType());
                }
            }
        }
        
        // 布尔运算的德摩根定律应用等
        
        return null;
    }
    
    private boolean optimizeCompareInstruction(CompareInstruction cmpInst) {
        Value left = cmpInst.getLeft();
        Value right = cmpInst.getRight();
        OpCode predicate = cmpInst.getPredicate();
        
        // 暂时禁用比较指令优化以避免影响控制流精度
        // 在对溢出敏感的算法中，比较优化可能会：
        // 1. 改变比较的语义
        // 2. 影响条件分支的行为
        // 3. 改变与常量比较的精度
        
        // TODO: 需要实现更安全的比较优化
        
        return false;
        
        /*
        // 1. 常量比较
        if (left instanceof Constant && right instanceof Constant) {
            Value result = foldConstantComparison(left, right, predicate);
            if (result != null) {
                replaceInstruction(cmpInst, result);
                return true;
            }
        }
        
        // 2. 自比较
        if (left.equals(right)) {
            Value result = simplifySelfComparison(predicate);
            if (result != null) {
                replaceInstruction(cmpInst, result);
                return true;
            }
        }
        
        // 3. 与0比较的优化
        if (isConstantZero(right)) {
            Value result = simplifyCompareWithZero(left, predicate);
            if (result != null) {
                replaceInstruction(cmpInst, result);
                return true;
            }
        }
        
        // 4. 比较链优化
        Value chained = optimizeComparisonChain(cmpInst);
        if (chained != null) {
            replaceInstruction(cmpInst, chained);
            return true;
        }
        
        // 原有的二元运算与常量比较优化
        if (left instanceof BinaryInstruction && right instanceof ConstantInt) {
            return optimizeCompareWithBinary(cmpInst, (BinaryInstruction) left, (ConstantInt) right);
        }
        
        return false;
        */
    }
    
    private Value simplifySelfComparison(OpCode predicate) {
        switch (predicate) {
            case EQ:
            case UEQ:
            case SLE:
            case SGE:
            case ULE:
            case UGE:
                return ConstantInt.getBool(true);
            case NE:
            case UNE:
            case SLT:
            case SGT:
            case ULT:
            case UGT:
                return ConstantInt.getBool(false);
        }
        return null;
    }
    
    private Value simplifyCompareWithZero(Value value, OpCode predicate) {
        // 特殊情况：x & mask != 0 可以简化
        if (value instanceof BinaryInstruction) {
            BinaryInstruction binInst = (BinaryInstruction) value;
            if (binInst.getOpCode() == OpCode.AND && predicate == OpCode.NE) {
                // (x & mask) != 0 保持原样，但可以标记为布尔上下文
                return null; // 暂时不优化
            }
        }
        
        return null;
    }
    
    private Value optimizeComparisonChain(CompareInstruction cmpInst) {
        // TODO: 实现比较链优化，例如 a < b && b < c => a < c (在某些情况下)
        return null;
    }
    
    private boolean optimizeCompareWithBinary(CompareInstruction cmpInst, BinaryInstruction binInst, ConstantInt rightConst) {
        OpCode binOp = binInst.getOpCode();
        OpCode cmpOp = cmpInst.getPredicate();
        int constVal = rightConst.getValue();
        
        // x + c1 == c2 => x == c2 - c1
        if (binOp == OpCode.ADD && cmpOp == OpCode.EQ && binInst.getRight() instanceof ConstantInt) {
            int addConst = ((ConstantInt) binInst.getRight()).getValue();
            int newConst = constVal - addConst;
            
            CompareInstruction newCmp = new CompareInstruction(
                cmpInst.getCompareType(), OpCode.EQ, 
                binInst.getLeft(), new ConstantInt(newConst)
            );
            replaceInstruction(cmpInst, newCmp);
                return true;
            }
        
        // x - c1 == c2 => x == c2 + c1
        if (binOp == OpCode.SUB && cmpOp == OpCode.EQ && binInst.getRight() instanceof ConstantInt) {
            int subConst = ((ConstantInt) binInst.getRight()).getValue();
            int newConst = constVal + subConst;
            
            CompareInstruction newCmp = new CompareInstruction(
                cmpInst.getCompareType(), OpCode.EQ, 
                binInst.getLeft(), new ConstantInt(newConst)
            );
            replaceInstruction(cmpInst, newCmp);
                return true;
            }
        
        return false;
    }
    
    private boolean optimizeBranchInstruction(BranchInstruction brInst) {
        if (brInst.isUnconditional()) {
            return false;
        }
        
        // 暂时禁用分支优化以避免PHI指令支配关系问题
        // 这些优化可能会改变控制流图但没有正确更新PHI指令
        
        // TODO: 需要实现更安全的分支优化，确保：
        // 1. PHI指令的操作数与前驱块一致
        // 2. 支配关系不被破坏
        // 3. 控制流图的一致性
        
        return false;
        
        /*
        Value condition = brInst.getCondition();
        
        // 1. 常量条件
        if (condition instanceof ConstantInt) {
            int val = ((ConstantInt) condition).getValue();
            BasicBlock target = (val != 0) ? brInst.getTrueBlock() : brInst.getFalseBlock();
            
            BranchInstruction newBr = new BranchInstruction(target);
            replaceInstruction(brInst, newBr);
            return true;
        }
        
        // 2. 相同目标
        if (brInst.getTrueBlock() == brInst.getFalseBlock()) {
            BranchInstruction newBr = new BranchInstruction(brInst.getTrueBlock());
            replaceInstruction(brInst, newBr);
            return true;
        }
        
        // 3. 简化比较条件
        if (condition instanceof CompareInstruction) {
            Value simplified = simplifyBranchCondition((CompareInstruction) condition);
            if (simplified != null && simplified != condition) {
                BranchInstruction newBr = new BranchInstruction(simplified, 
                                         brInst.getTrueBlock(), brInst.getFalseBlock());
                replaceInstruction(brInst, newBr);
                return true;
            }
        }
        
        return false;
        */
    }
    
    private Value simplifyBranchCondition(CompareInstruction cmp) {
        // 简化用于分支的比较指令
        // 例如：将复杂的比较转换为简单的测试
        return null; // 暂时返回null，后续可以添加更多规则
    }
    
    private boolean optimizeConversionInstruction(ConversionInstruction convInst) {
        Value source = convInst.getSource();
        
        // 1. 冗余转换消除
        if (source instanceof ConversionInstruction) {
            ConversionInstruction sourceConv = (ConversionInstruction) source;
            
            // 同类型转换链：i32->i64->i32 可能可以简化
            if (sourceConv.getSource().getType().equals(convInst.getType())) {
                replaceInstruction(convInst, sourceConv.getSource());
                return true;
            }
        }
        
        // 2. 常量转换
        if (source instanceof Constant) {
            Value result = foldConstantConversion((Constant) source, 
                                                 convInst.getType(), convInst.getConversionType());
            if (result != null) {
                replaceInstruction(convInst, result);
                return true;
            }
        }
        
        return false;
    }
    
    private boolean optimizeLoadInstruction(LoadInstruction loadInst) {
        // Load指令的优化
        Value pointer = loadInst.getPointer();
        
        // 1. 从常量全局变量加载
        if (pointer instanceof GlobalVariable) {
            GlobalVariable gv = (GlobalVariable) pointer;
            if (gv.isConstant() && gv.hasInitializer()) {
                Value initializer = gv.getInitializer();
                if (initializer instanceof Constant) {
                    replaceInstruction(loadInst, initializer);
            return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean optimizeCallInstruction(CallInstruction callInst) {
        Function callee = callInst.getCallee();
        
        // 1. 内置函数优化
        if (callee.isExternal()) {
            Value result = optimizeIntrinsicCall(callInst);
            if (result != null) {
                replaceInstruction(callInst, result);
            return true;
            }
        }
        
        return false;
    }
    
    private Value optimizeIntrinsicCall(CallInstruction callInst) {
        String name = callInst.getCallee().getName();
        List<Value> args = callInst.getArguments();
        
        return null;
    }
    
    // ============= 辅助方法 =============
    
    private Value foldConstants(Value left, Value right, OpCode opcode) {
        if (left instanceof ConstantInt && right instanceof ConstantInt) {
            int l = ((ConstantInt) left).getValue();
            int r = ((ConstantInt) right).getValue();
            
            switch (opcode) {
                case ADD: return new ConstantInt(l + r);
                case SUB: return new ConstantInt(l - r);
                case MUL: return new ConstantInt(l * r);
                case DIV: return r != 0 ? new ConstantInt(l / r) : null;
                case REM: return r != 0 ? new ConstantInt(l % r) : null;
                case AND: return new ConstantInt(l & r);
                case OR:  return new ConstantInt(l | r);
                case XOR: return new ConstantInt(l ^ r);
                case SHL: return new ConstantInt(l << r);
                case LSHR: return new ConstantInt(l >>> r);
                case ASHR: return new ConstantInt(l >> r);
            }
        }
        
        if (left instanceof ConstantFloat && right instanceof ConstantFloat) {
            double l = ((ConstantFloat) left).getValue();
            double r = ((ConstantFloat) right).getValue();
            
            switch (opcode) {
                case FADD: return new ConstantFloat(l + r);
                case FSUB: return new ConstantFloat(l - r);
                case FMUL: return new ConstantFloat(l * r);
                case FDIV: return r != 0.0 ? new ConstantFloat(l / r) : null;
                case FREM: return r != 0.0 ? new ConstantFloat(l % r) : null;
            }
        }
        
        return null;
    }
    
    private Value foldConstantComparison(Value left, Value right, OpCode predicate) {
        if (left instanceof ConstantInt && right instanceof ConstantInt) {
            int l = ((ConstantInt) left).getValue();
            int r = ((ConstantInt) right).getValue();
            
            boolean result = false;
            switch (predicate) {
                case EQ: result = (l == r); break;
                case NE: result = (l != r); break;
                case SLT: result = (l < r); break;
                case SLE: result = (l <= r); break;
                case SGT: result = (l > r); break;
                case SGE: result = (l >= r); break;
            }
            return ConstantInt.getBool(result);
        }
        
        if (left instanceof ConstantFloat && right instanceof ConstantFloat) {
            double l = ((ConstantFloat) left).getValue();
            double r = ((ConstantFloat) right).getValue();
            
            boolean result = false;
            switch (predicate) {
                case UEQ: result = (l == r); break;
                case UNE: result = (l != r); break;
                case ULT: result = (l < r); break;
                case ULE: result = (l <= r); break;
                case UGT: result = (l > r); break;
                case UGE: result = (l >= r); break;
            }
            return ConstantInt.getBool(result);
        }
        
        return null;
    }
    
    private Value foldConstantConversion(Constant source, Type targetType, OpCode convType) {
        if (source instanceof ConstantInt && targetType instanceof FloatType) {
            int intVal = ((ConstantInt) source).getValue();
            return new ConstantFloat((double) intVal);
        }
        
        if (source instanceof ConstantFloat && targetType instanceof IntegerType) {
            double floatVal = ((ConstantFloat) source).getValue();
            return new ConstantInt((int) floatVal);
        }
        
        return null;
    }
    
    private boolean isConstantZero(Value value) {
        if (value instanceof ConstantInt) {
            return ((ConstantInt) value).getValue() == 0;
        }
        if (value instanceof ConstantFloat) {
            return ((ConstantFloat) value).getValue() == 0.0;
        }
        return false;
    }
    
    private boolean isConstantOne(Value value) {
        if (value instanceof ConstantInt) {
            return ((ConstantInt) value).getValue() == 1;
        }
        if (value instanceof ConstantFloat) {
            return ((ConstantFloat) value).getValue() == 1.0;
        }
        return false;
    }
    
    private boolean isConstantMinusOne(Value value) {
        if (value instanceof ConstantInt) {
            return ((ConstantInt) value).getValue() == -1;
        }
        if (value instanceof ConstantFloat) {
            return ((ConstantFloat) value).getValue() == -1.0;
        }
        return false;
    }
    
    private boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
    
    private boolean isNegation(Value a, Value b) {
        // 检查a是否是-b或b是否是-a
        if (a instanceof BinaryInstruction) {
            BinaryInstruction binA = (BinaryInstruction) a;
            if (binA.getOpCode() == OpCode.SUB && isConstantZero(binA.getLeft()) && binA.getRight().equals(b)) {
                return true;
            }
        }
        return false;
    }
    
    private Value createNegation(Value value) {
        return new BinaryInstruction(OpCode.SUB, new ConstantInt(0), value, value.getType());
    }
    
    private void replaceInstruction(Instruction oldInst, Value newValue) {
        // 替换所有使用
        for (Value user : new ArrayList<>(oldInst.getUsers())) {
            if (user instanceof Instruction) {
                Instruction userInst = (Instruction) user;
                for (int i = 0; i < userInst.getOperandCount(); i++) {
                    if (userInst.getOperand(i) == oldInst) {
                        userInst.setOperand(i, newValue);
                    }
                }
            }
        }
        
        // 如果新值是指令，插入到适当位置
        if (newValue instanceof Instruction) {
            Instruction newInst = (Instruction) newValue;
            if (newInst.getParent() == null) {
                newInst.insertBefore(oldInst);
            }
        }
        
        // 移除旧指令
        oldInst.removeFromParent();
        changed = true;
    }
} 
package MiddleEnd.Optimization.Constant;

import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Type.FloatType;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

/**
 * 常量表达式评估器
 * 用于在编译时计算常量表达式
 */
public class ConstantExpressionEvaluator {
    
    /**
     * 评估常量表达式
     * @param expr 待评估的表达式
     * @return 计算结果，如果不是常量表达式则返回null
     */
    public static Constant evaluate(Value expr) {
        // 如果已经是常量，直接返回
        if (expr instanceof Constant) {
            return (Constant) expr;
        }
        
        // 处理变量引用 (主要用于全局常量引用)
        if (expr instanceof GlobalVariable gv) {
            if (gv.isConstant() && gv.hasInitializer()) {
                Value initializer = gv.getInitializer();
                if (initializer instanceof Constant) {
                    return (Constant) initializer;
                }
            }
            return null;
        }
        
        // 处理二元表达式
        if (expr instanceof BinaryInstruction binaryInst) {
            Value left = binaryInst.getOperand(0);
            Value right = binaryInst.getOperand(1);
            
            // 递归评估操作数
            Constant leftConst = evaluate(left);
            Constant rightConst = evaluate(right);
            
            // 如果操作数不是常量，则整个表达式不是常量
            if (leftConst == null || rightConst == null) {
                return null;
            }
            
            // 计算常量表达式
            return calculateConstantExpr(leftConst, rightConst, binaryInst.getOpCode());
        }
        
        // 处理一元表达式（如负号）
        if (expr instanceof UnaryInstruction unaryInst) {
            Value operand = unaryInst.getOperand(0);
            Constant operandConst = evaluate(operand);
            
            if (operandConst == null) {
                return null;
            }
            
            // 处理负号
            if (unaryInst.getOpCode() == OpCode.NEG) {
                if (operandConst instanceof ConstantInt) {
                    return new ConstantInt(-((ConstantInt) operandConst).getValue());
                } else if (operandConst instanceof ConstantFloat) {
                    return new ConstantFloat(-((ConstantFloat) operandConst).getValue());
                }
            }
        }
        
        // 处理类型转换指令
        if (expr instanceof ConversionInstruction convInst) {
            Value operand = convInst.getOperand(0);
            Constant operandConst = evaluate(operand);
            
            if (operandConst == null) {
                return null;
            }
            
            Type targetType = convInst.getType();
            
            // 整数转浮点数
            if (convInst.getConversionType() == OpCode.SITOFP && operandConst instanceof ConstantInt) {
                return new ConstantFloat((double) ((ConstantInt) operandConst).getValue());
            }
            // 浮点数转整数
            else if (convInst.getConversionType() == OpCode.FPTOSI && operandConst instanceof ConstantFloat) {
                return new ConstantInt((int) ((ConstantFloat) operandConst).getValue());
            }
        }
        
        // 其他情况，不是常量表达式
        return null;
    }
    
    /**
     * 计算常量表达式结果
     */
    private static Constant calculateConstantExpr(Constant left, Constant right, OpCode op) {
        // 整数常量计算
        if (left instanceof ConstantInt && right instanceof ConstantInt) {
            int leftVal = ((ConstantInt) left).getValue();
            int rightVal = ((ConstantInt) right).getValue();
            
            switch (op) {
                case ADD:
                    return new ConstantInt(leftVal + rightVal);
                case SUB:
                    return new ConstantInt(leftVal - rightVal);
                case MUL:
                    return new ConstantInt(leftVal * rightVal);
                case DIV:
                    if (rightVal != 0) {
                        return new ConstantInt(leftVal / rightVal);
                    }
                    break;
                case REM:
                    if (rightVal != 0) {
                        return new ConstantInt(leftVal % rightVal);
                    }
                    break;
                case SHL:
                    return new ConstantInt(leftVal << rightVal);
                case LSHR:
                    return new ConstantInt(leftVal >>> rightVal);
                case ASHR:
                    return new ConstantInt(leftVal >> rightVal);
                case AND:
                    return new ConstantInt(leftVal & rightVal);
                case OR:
                    return new ConstantInt(leftVal | rightVal);
                case XOR:
                    return new ConstantInt(leftVal ^ rightVal);
                case EQ:
                    return new ConstantInt(leftVal == rightVal ? 1 : 0);
                case NE:
                    return new ConstantInt(leftVal != rightVal ? 1 : 0);
                case SLT:
                    return new ConstantInt(leftVal < rightVal ? 1 : 0);
                case SLE:
                    return new ConstantInt(leftVal <= rightVal ? 1 : 0);
                case SGT:
                    return new ConstantInt(leftVal > rightVal ? 1 : 0);
                case SGE:
                    return new ConstantInt(leftVal >= rightVal ? 1 : 0);
            }
        }
        
        // 浮点常量计算
        if (left instanceof ConstantFloat && right instanceof ConstantFloat) {
            double leftVal = ((ConstantFloat) left).getValue();
            double rightVal = ((ConstantFloat) right).getValue();
            
            switch (op) {
                case FADD:
                    return new ConstantFloat(leftVal + rightVal);
                case FSUB:
                    return new ConstantFloat(leftVal - rightVal);
                case FMUL:
                    return new ConstantFloat(leftVal * rightVal);
                case FDIV:
                    if (rightVal != 0) {
                        return new ConstantFloat(leftVal / rightVal);
                    }
                    break;
                case FREM:
                    if (rightVal != 0) {
                        return new ConstantFloat(leftVal % rightVal);
                    }
                    break;
                case UEQ:
                    return new ConstantInt(leftVal == rightVal ? 1 : 0);
                case UNE:
                    return new ConstantInt(leftVal != rightVal ? 1 : 0);
                case ULT:
                    return new ConstantInt(leftVal < rightVal ? 1 : 0);
                case ULE:
                    return new ConstantInt(leftVal <= rightVal ? 1 : 0);
                case UGT:
                    return new ConstantInt(leftVal > rightVal ? 1 : 0);
                case UGE:
                    return new ConstantInt(leftVal >= rightVal ? 1 : 0);
            }
        }
        
        // 混合类型计算（整数和浮点）
        if (left instanceof ConstantInt && right instanceof ConstantFloat) {
            double leftVal = ((ConstantInt) left).getValue();
            double rightVal = ((ConstantFloat) right).getValue();
            
            switch (op) {
                case FADD:
                    return new ConstantFloat(leftVal + rightVal);
                case FSUB:
                    return new ConstantFloat(leftVal - rightVal);
                case FMUL:
                    return new ConstantFloat(leftVal * rightVal);
                case FDIV:
                    if (rightVal != 0) {
                        return new ConstantFloat(leftVal / rightVal);
                    }
                    break;
                case FREM:
                    if (rightVal != 0) {
                        return new ConstantFloat(leftVal % rightVal);
                    }
                    break;
            }
        }
        
        if (left instanceof ConstantFloat && right instanceof ConstantInt) {
            double leftVal = ((ConstantFloat) left).getValue();
            double rightVal = ((ConstantInt) right).getValue();
            
            switch (op) {
                case FADD:
                    return new ConstantFloat(leftVal + rightVal);
                case FSUB:
                    return new ConstantFloat(leftVal - rightVal);
                case FMUL:
                    return new ConstantFloat(leftVal * rightVal);
                case FDIV:
                    if (rightVal != 0) {
                        return new ConstantFloat(leftVal / rightVal);
                    }
                    break;
                case FREM:
                    if (rightVal != 0) {
                        return new ConstantFloat(leftVal % rightVal);
                    }
                    break;
            }
        }
        
        return null; // 无法计算
    }

    /**
     * 将值提升为浮点类型（如果是整数）
     */
    private static Value promoteToFloat(Value value) {
        if (value.getType() instanceof IntegerType && value instanceof ConstantInt) {
            return new ConstantFloat((double) ((ConstantInt) value).getValue());
        }
        return value;
    }
} 
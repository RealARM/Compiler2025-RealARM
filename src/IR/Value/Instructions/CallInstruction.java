package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.Type;
import IR.Type.VoidType;
import IR.Value.Function;
import IR.Value.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 函数调用指令
 */
public class CallInstruction extends Instruction {
    private final Function callee; // 被调用的函数
    
    /**
     * 创建一个函数调用指令
     */
    public CallInstruction(Function callee, List<Value> arguments, String name) {
        // 对于void返回类型的函数调用，如果名称为null，则使用空字符串
        super(callee.getReturnType() instanceof VoidType && name == null ? "" : name, callee.getReturnType());
        this.callee = callee;
        
        // 添加被调用函数作为操作数
        addOperand(callee);
        
        // 添加所有参数作为操作数
        for (Value arg : arguments) {
            addOperand(arg);
        }
    }
    
    /**
     * 获取被调用的函数
     */
    public Function getCallee() {
        return callee;
    }
    
    /**
     * 获取参数列表
     */
    public List<Value> getArguments() {
        List<Value> args = new ArrayList<>();
        // 跳过第一个操作数（callee），获取剩余的参数
        for (int i = 1; i < getOperandCount(); i++) {
            args.add(getOperand(i));
        }
        return args;
    }
    
    /**
     * 获取参数数量
     */
    public int getArgumentCount() {
        return getOperandCount() - 1; // 减去callee
    }
    
    /**
     * 获取指定索引的参数
     */
    public Value getArgument(int index) {
        return getOperand(index + 1); // 加1跳过callee
    }
    
    /**
     * 判断是否为void返回类型的调用
     */
    public boolean isVoidCall() {
        return getType() instanceof VoidType;
    }
    
    @Override
    public String getOpcodeName() {
        return OpCode.CALL.getName();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        if (!isVoidCall()) {
            sb.append(getName()).append(" = ");
        }
        
        sb.append(getOpcodeName()).append(" ");
        sb.append(getCallee().getType()).append(" @").append(getCallee().getName()).append("(");
        
        // 添加参数
        List<Value> args = getArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Value arg = args.get(i);
            sb.append(arg.getType()).append(" ").append(arg.getName());
        }
        
        sb.append(")");
        return sb.toString();
    }
} 
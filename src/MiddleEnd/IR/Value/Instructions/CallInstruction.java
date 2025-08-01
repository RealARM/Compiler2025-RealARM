package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Type.VoidType;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Value;

import java.util.ArrayList;
import java.util.List;

public class CallInstruction extends Instruction {
    private final Function callee;
    
    public CallInstruction(Function callee, List<Value> arguments, String name) {
        super(callee.getReturnType() instanceof VoidType && name == null ? "" : name, callee.getReturnType());
        this.callee = callee;
        
        addOperand(callee);
        
        for (Value arg : arguments) {
            addOperand(arg);
        }
    }
    
    public Function getCallee() {
        return callee;
    }
    
    public List<Value> getArguments() {
        List<Value> args = new ArrayList<>();
        for (int i = 1; i < getOperandCount(); i++) {
            args.add(getOperand(i));
        }
        return args;
    }
    
    public int getArgumentCount() {
        return getOperandCount() - 1;
    }
    
    public Value getArgument(int index) {
        return getOperand(index + 1);
    }
    
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
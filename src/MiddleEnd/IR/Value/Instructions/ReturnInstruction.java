package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.VoidType;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Value;

public class ReturnInstruction extends Instruction implements TerminatorInstruction {
    public ReturnInstruction() {
        super("ret", VoidType.VOID);
    }

    public ReturnInstruction(Value returnValue) {
        super("ret", VoidType.VOID);
        if (returnValue != null) {
            addOperand(returnValue);
        }
    }

    public boolean isVoidReturn() {
        return getOperandCount() == 0;
    }

    public Value getReturnValue() {
        return isVoidReturn() ? null : getOperand(0);
    }

    @Override
    public String getOpcodeName() {
        return OpCode.RET.getName();
    }

    @Override
    public String toString() {
        if (isVoidReturn()) {
            return "ret void";
        } else {
            Value rv = getReturnValue();
            return "ret " + rv.getType() + " " + rv.getName();
        }
    }

    @Override
    public BasicBlock[] getSuccessors() {
        return new BasicBlock[0];
    }
} 
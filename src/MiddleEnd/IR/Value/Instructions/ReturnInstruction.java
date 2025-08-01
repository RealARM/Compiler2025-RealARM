package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.VoidType;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Value;

/**
 * 返回指令，用于从函数返回
 */
public class ReturnInstruction extends Instruction implements TerminatorInstruction {
    /**
     * 创建一个void返回指令
     */
    public ReturnInstruction() {
        super("ret", VoidType.VOID);
    }

    /**
     * 创建一个带返回值的返回指令
     */
    public ReturnInstruction(Value returnValue) {
        super("ret", VoidType.VOID); // 返回指令本身无返回值类型
        if (returnValue != null) {
            addOperand(returnValue);
        }
    }

    /**
     * 判断是否为void返回
     */
    public boolean isVoidReturn() {
        return getOperandCount() == 0;
    }

    /**
     * 获取返回值
     */
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
        return new BasicBlock[0]; // 返回指令没有后继基本块
    }
} 
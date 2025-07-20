package IR.Value.Instructions;

import IR.OpCode;
import IR.Type.Type;
import IR.Type.VoidType;
import IR.Value.BasicBlock;
import IR.Value.Value;

/**
 * 返回指令，用于从函数返回
 */
public class ReturnInstruction extends Instruction implements TerminatorInstruction {
    private final Value returnValue; // 返回值，可能为null表示void返回
    
    /**
     * 创建一个void返回指令
     */
    public ReturnInstruction() {
        super("ret", VoidType.VOID);
        this.returnValue = null;
    }
    
    /**
     * 创建一个带返回值的返回指令
     */
    public ReturnInstruction(Value returnValue) {
        super("ret", VoidType.VOID); // 返回指令本身无返回值类型
        this.returnValue = returnValue;
        if (returnValue != null) {
            addOperand(returnValue);
        }
    }
    
    /**
     * 判断是否为void返回
     */
    public boolean isVoidReturn() {
        return returnValue == null;
    }
    
    /**
     * 获取返回值
     */
    public Value getReturnValue() {
        return returnValue;
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
            return "ret " + returnValue.getType() + " " + returnValue.getName();
        }
    }
    
    @Override
    public BasicBlock[] getSuccessors() {
        return new BasicBlock[0]; // 返回指令没有后继基本块
    }
} 
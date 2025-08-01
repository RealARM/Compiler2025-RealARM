package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.Value.BasicBlock;

/**
 * 终结指令接口，所有基本块终结指令都实现此接口
 */
public interface TerminatorInstruction {
    /**
     * 获取此终结指令可能跳转到的所有基本块
     */
    BasicBlock[] getSuccessors();
} 
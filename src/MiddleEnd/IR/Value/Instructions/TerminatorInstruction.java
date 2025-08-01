package MiddleEnd.IR.Value.Instructions;

import MiddleEnd.IR.Value.BasicBlock;

public interface TerminatorInstruction {
    BasicBlock[] getSuccessors();
} 
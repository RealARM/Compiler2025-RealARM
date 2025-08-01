package Backend.RegisterManager;

import Backend.Structure.AArch64Block;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Instruction.DataMovement.AArch64Move;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.*;

/**
 * RegisterAllocator的辅助类，提供图着色算法和内存操作的辅助方法
 */
public class RegisterAllocatorHelper {
    
    /**
     * 加载大立即数到寄存器
     */
    public static void loadLargeImmToReg(AArch64Block block, AArch64Instruction refInst, AArch64Reg destReg, long value, boolean insertAfter) {
        long bits = value;
        
        // 使用MOVZ指令加载第一个16位
        AArch64Move movzInst = new AArch64Move(destReg, new AArch64Imm(bits & 0xFFFF), true, AArch64Move.MoveType.MOVZ);
        if (insertAfter) {
            insertAfterInstruction(block, refInst, movzInst);
        } else {
            block.insertBeforeInst(refInst, movzInst);
        }
        
        // 检查第二个16位（bits[31:16]）
        if (((bits >> 16) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 16) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(16);
            if (insertAfter) {
                insertAfterInstruction(block, refInst, movkInst);
            } else {
                block.insertBeforeInst(refInst, movkInst);
            }
        }
        
        // 检查第三个16位（bits[47:32]）
        if (((bits >> 32) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 32) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(32);
            if (insertAfter) {
                insertAfterInstruction(block, refInst, movkInst);
            } else {
                block.insertBeforeInst(refInst, movkInst);
            }
        }
        
        // 检查第四个16位（bits[63:48]）
        if (((bits >> 48) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 48) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(48);
            if (insertAfter) {
                insertAfterInstruction(block, refInst, movkInst);
            } else {
                block.insertBeforeInst(refInst, movkInst);
            }
        }
    }

    /**
     * 在指定指令后插入新指令
     */
    public static void insertAfterInstruction(AArch64Block block, AArch64Instruction refInst, AArch64Instruction newInst) {
        int instIndex = block.getInstructions().indexOf(refInst);
        if (instIndex != -1 && instIndex + 1 < block.getInstructions().size()) {
            AArch64Instruction nextInst = block.getInstructions().get(instIndex + 1);
            block.insertBeforeInst(nextInst, newInst);
        } else {
            block.addAArch64Instruction(newInst);
        }
    }
    
}
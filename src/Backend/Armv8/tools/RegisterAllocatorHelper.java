package Backend.Armv8.tools;

import Backend.Armv8.Instruction.*;
import Backend.Armv8.Operand.*;
import Backend.Armv8.Structure.Armv8Block;
import java.util.ArrayList;

/**
 * RegisterAllocator的辅助类，提供安全的内存操作方法
 */
public class RegisterAllocatorHelper {
    
    /**
     * 加载大立即数到寄存器
     */
    public static void loadLargeImmToReg(Armv8Block block, Armv8Instruction refInst, Armv8Reg destReg, long value, boolean insertAfter) {
        long bits = value;
        
        // 使用MOVZ指令加载第一个16位
        Armv8Move movzInst = new Armv8Move(destReg, new Armv8Imm(bits & 0xFFFF), true, Armv8Move.MoveType.MOVZ);
        if (insertAfter) {
            insertAfterInstruction(block, refInst, movzInst);
        } else {
            block.insertBeforeInst(refInst, movzInst);
        }
        
        // 检查第二个16位（bits[31:16]）
        if (((bits >> 16) & 0xFFFF) != 0) {
            Armv8Move movkInst = new Armv8Move(destReg, new Armv8Imm((bits >> 16) & 0xFFFF), true, Armv8Move.MoveType.MOVK);
            movkInst.setShift(16);
            if (insertAfter) {
                insertAfterInstruction(block, refInst, movkInst);
            } else {
                block.insertBeforeInst(refInst, movkInst);
            }
        }
        
        // 检查第三个16位（bits[47:32]）
        if (((bits >> 32) & 0xFFFF) != 0) {
            Armv8Move movkInst = new Armv8Move(destReg, new Armv8Imm((bits >> 32) & 0xFFFF), true, Armv8Move.MoveType.MOVK);
            movkInst.setShift(32);
            if (insertAfter) {
                insertAfterInstruction(block, refInst, movkInst);
            } else {
                block.insertBeforeInst(refInst, movkInst);
            }
        }
        
        // 检查第四个16位（bits[63:48]）
        if (((bits >> 48) & 0xFFFF) != 0) {
            Armv8Move movkInst = new Armv8Move(destReg, new Armv8Imm((bits >> 48) & 0xFFFF), true, Armv8Move.MoveType.MOVK);
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
    public static void insertAfterInstruction(Armv8Block block, Armv8Instruction refInst, Armv8Instruction newInst) {
        int instIndex = block.getInstructions().indexOf(refInst);
        if (instIndex != -1 && instIndex + 1 < block.getInstructions().size()) {
            Armv8Instruction nextInst = block.getInstructions().get(instIndex + 1);
            block.insertBeforeInst(nextInst, newInst);
        } else {
            block.addArmv8Instruction(newInst);
        }
    }
}
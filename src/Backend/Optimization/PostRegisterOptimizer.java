package Backend.Optimization;

import Backend.Structure.AArch64Block;
import Backend.Structure.AArch64Function;
import Backend.Utils.LivenessAnalyzer;
import Backend.Value.Base.*;
import Backend.Value.Instruction.Address.*;
import Backend.Value.Instruction.Arithmetic.*;
import Backend.Value.Instruction.Comparison.*;
import Backend.Value.Instruction.ControlFlow.*;
import Backend.Value.Instruction.DataMovement.*;
import Backend.Value.Instruction.Memory.*;
import Backend.Value.Operand.Constant.*;
import Backend.Value.Operand.Register.*;
import Backend.Value.Instruction.System.*;

import java.util.*;

/**
 * 寄存器分配后的优化器
 */
public class PostRegisterOptimizer {
    
    private final AArch64Function function;
    private LinkedHashMap<AArch64Block, LivenessAnalyzer.LivenessInfo> livenessInfoMap;
    private LinkedHashSet<AArch64Reg> modifiedRegs;
    private boolean hasOptimizations = false;
    
    public PostRegisterOptimizer(AArch64Function function) {
        this.function = function;
        this.modifiedRegs = new LinkedHashSet<>();
    }
    
    public void optimize() {
        boolean changed = false;
        int round = 0;
        final int MAX_ROUNDS = 3; // 最多3轮优化，避免死循环
        
        do {
            changed = false;
            round++;
            hasOptimizations = false;
            
            System.out.println("第 " + round + " 轮后端优化");
            
            // 更新活跃性信息
            livenessInfoMap = LivenessAnalyzer.analyzeLiveness(function);
            
            // 执行各种优化
            changed |= removeRedundantMoves();
            changed |= optimizeArithmetic();
            changed |= removeUnusedInstructions();
            changed |= combineMemoryOperations();
            changed |= optimizeConditionalBranches();
            
            if (hasOptimizations) {
                System.out.println("函数 " + function.getName() + " 第 " + round + " 轮优化完成");
            }
            
        } while (changed && round < MAX_ROUNDS);
        
        if (round > 1) {
            System.out.println("函数 " + function.getName() + " 后端优化完成，共 " + (round - 1) + " 轮");
        }
    }
    
    private boolean removeRedundantMoves() {
        boolean changed = false;
        
        for (AArch64Block block : function.getBlocks()) {
            List<AArch64Instruction> instructions = block.getInstructions();
            Iterator<AArch64Instruction> iterator = instructions.iterator();
            
            while (iterator.hasNext()) {
                AArch64Instruction instruction = iterator.next();
                
                if (instruction instanceof AArch64Move) {
                    AArch64Move moveInst = (AArch64Move) instruction;
                    
                    if (isRedundantMove(moveInst)) {
                        iterator.remove();
                        changed = true;
                        hasOptimizations = true;
                        System.out.println("删除冗余move: " + moveInst);
                    }
                }
            }
        }
        
        return changed;
    }
    
    private boolean optimizeArithmetic() {
        boolean changed = false;
        
        for (AArch64Block block : function.getBlocks()) {
            List<AArch64Instruction> instructions = block.getInstructions();
            
            for (int i = 0; i < instructions.size(); i++) {
                AArch64Instruction inst = instructions.get(i);
                
                if (inst instanceof AArch64Binary) {
                    AArch64Binary binInst = (AArch64Binary) inst;
                    
                    // 优化 add reg, reg, 0
                    if (binInst.getInstType() == AArch64Binary.AArch64BinaryType.add &&
                        binInst.getOperands().size() >= 2 &&
                        binInst.getOperands().get(1) instanceof AArch64Imm) {
                        
                        AArch64Imm imm = (AArch64Imm) binInst.getOperands().get(1);
                        if (imm.getValue() == 0) {
                            if (binInst.getDefReg().equals(binInst.getOperands().get(0))) {
                                // add reg, reg, 0 -> 删除
                                instructions.remove(i);
                                i--;
                                changed = true;
                                hasOptimizations = true;
                                System.out.println("删除无用加法: " + binInst);
                            } else {
                                // add rd, rs, 0 -> mov rd, rs
                                AArch64Move moveInst = new AArch64Move(
                                    binInst.getDefReg(),
                                    (AArch64Reg) binInst.getOperands().get(0),
                                    false
                                );
                                instructions.set(i, moveInst);
                                changed = true;
                                hasOptimizations = true;
                                System.out.println("优化加法为move: " + binInst + " -> " + moveInst);
                            }
                        }
                    }
                    
                    // 优化 sub reg, reg, 0
                    if (binInst.getInstType() == AArch64Binary.AArch64BinaryType.sub &&
                        binInst.getOperands().size() >= 2 &&
                        binInst.getOperands().get(1) instanceof AArch64Imm) {
                        
                        AArch64Imm imm = (AArch64Imm) binInst.getOperands().get(1);
                        if (imm.getValue() == 0) {
                            if (binInst.getDefReg().equals(binInst.getOperands().get(0))) {
                                // sub reg, reg, 0 -> 删除
                                instructions.remove(i);
                                i--;
                                changed = true;
                                hasOptimizations = true;
                                System.out.println("删除无用减法: " + binInst);
                            } else {
                                // sub rd, rs, 0 -> mov rd, rs
                                AArch64Move moveInst = new AArch64Move(
                                    binInst.getDefReg(),
                                    (AArch64Reg) binInst.getOperands().get(0),
                                    false
                                );
                                instructions.set(i, moveInst);
                                changed = true;
                                hasOptimizations = true;
                                System.out.println("优化减法为move: " + binInst + " -> " + moveInst);
                            }
                        }
                    }
                }
            }
        }
        
        return changed;
    }
    
    private boolean removeUnusedInstructions() {
        boolean changed = false;
        
        for (AArch64Block block : function.getBlocks()) {
            List<AArch64Instruction> instructions = block.getInstructions();
            Iterator<AArch64Instruction> iterator = instructions.iterator();
            
            while (iterator.hasNext()) {
                AArch64Instruction inst = iterator.next();
                
                if (canRemoveInstruction(inst, block)) {
                    iterator.remove();
                    changed = true;
                    hasOptimizations = true;
                    System.out.println("删除未使用指令: " + inst);
                }
            }
        }
        
        return changed;
    }
    
    private boolean combineMemoryOperations() {
        boolean changed = false;
        
        for (AArch64Block block : function.getBlocks()) {
            List<AArch64Instruction> instructions = block.getInstructions();
            
            for (int i = 0; i < instructions.size() - 1; i++) {
                AArch64Instruction current = instructions.get(i);
                AArch64Instruction next = instructions.get(i + 1);
                
                // store + load 优化
                if (current instanceof AArch64Store && next instanceof AArch64Load) {
                    if (canCombineStoreLoad((AArch64Store) current, (AArch64Load) next)) {
                        // store reg1, addr; load reg2, addr -> store reg1, addr; mov reg2, reg1
                        AArch64Store store = (AArch64Store) current;
                        AArch64Load load = (AArch64Load) next;
                        
                        if (store.getOperands().size() >= 3 && 
                            store.getOperands().get(0) instanceof AArch64Reg) {
                            AArch64Move moveInst = new AArch64Move(
                                load.getDefReg(),
                                (AArch64Reg) store.getOperands().get(0),
                                false
                            );
                            instructions.set(i + 1, moveInst);
                            changed = true;
                            hasOptimizations = true;
                            System.out.println("合并store-load: " + load + " -> " + moveInst);
                        }
                    }
                }
            }
        }
        
        return changed;
    }
    

    private boolean optimizeConditionalBranches() {
        boolean changed = false;
        
        for (AArch64Block block : function.getBlocks()) {
            List<AArch64Instruction> instructions = block.getInstructions();
            
            // 查找连续的比较和分支指令
            for (int i = 0; i < instructions.size() - 1; i++) {
                AArch64Instruction current = instructions.get(i);
                AArch64Instruction next = instructions.get(i + 1);
                
                if (current instanceof AArch64Compare && next instanceof AArch64Branch) {
                    // TODO：添加比较分支的优化逻辑
                    // 例如：cmp reg, 0; beq -> cbz reg
                }
            }
        }
        
        return changed;
    }
    

    private void finalCleanup() {
        for (AArch64Block block : function.getBlocks()) {
            List<AArch64Instruction> instructions = block.getInstructions();
            
            // 删除连续的相同move指令
            for (int i = 0; i < instructions.size() - 1; i++) {
                AArch64Instruction current = instructions.get(i);
                AArch64Instruction next = instructions.get(i + 1);
                
                if (current instanceof AArch64Move && next instanceof AArch64Move) {
                    AArch64Move move1 = (AArch64Move) current;
                    AArch64Move move2 = (AArch64Move) next;
                    
                    if (move1.getDefReg().equals(move2.getDefReg())) {
                        // 连续两个move到同一个寄存器，删除第一个
                        instructions.remove(i);
                        i--;
                        System.out.println("删除重复move: " + move1);
                    }
                }
            }
        }
    }
    
    private boolean isRedundantMove(AArch64Move moveInst) {
        if (moveInst.getOperands().size() > 0 && 
            moveInst.getOperands().get(0) instanceof AArch64Reg &&
            moveInst.getDefReg() != null) {
            
            AArch64Reg src = (AArch64Reg) moveInst.getOperands().get(0);
            AArch64Reg dst = moveInst.getDefReg();
            
            return src.equals(dst);
        }
        
        return false;
    }
    
    private boolean canRemoveInstruction(AArch64Instruction inst, AArch64Block block) {
        if (hasSideEffects(inst)) {
            return false;
        }
        
        // 如果指令定义的寄存器没有被使用，可以删除
        if (inst.getDefReg() instanceof AArch64PhyReg) {
            LivenessAnalyzer.LivenessInfo liveness = livenessInfoMap.get(block);
            if (liveness != null && !liveness.getLiveOut().contains(inst.getDefReg())) {
                return !isRegisterUsedLater(inst.getDefReg(), inst, block);
            }
        }
        
        return false;
    }
    
    private boolean hasSideEffects(AArch64Instruction instruction) {
        return instruction instanceof AArch64Store ||
               instruction instanceof AArch64StorePair ||
               instruction instanceof AArch64Call ||
               instruction instanceof AArch64BlrCall ||
               instruction instanceof AArch64Branch ||
               instruction instanceof AArch64Jump ||
               instruction instanceof AArch64Ret ||
               instruction instanceof AArch64Compare ||
               instruction instanceof AArch64Syscall;
    }
    
    private boolean isRegisterUsedLater(AArch64Reg reg, AArch64Instruction fromInst, AArch64Block block) {
        // 简化实现：保守地认为物理寄存器都会被使用
        return true;
    }
    
    private boolean canCombineStoreLoad(AArch64Store store, AArch64Load load) {
        // 检查是否访问相同的内存地址
        if (store.getOperands().size() >= 3 && load.getOperands().size() >= 2) {
            return store.getOperands().get(1).equals(load.getOperands().get(0)) &&
                   store.getOperands().get(2).equals(load.getOperands().get(1));
        }
        return false;
    }
} 
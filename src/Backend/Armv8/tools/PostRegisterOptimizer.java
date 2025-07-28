package Backend.Armv8.tools;

import Backend.Armv8.Instruction.*;
import Backend.Armv8.Operand.*;
import Backend.Armv8.Structure.*;
import java.util.*;

/**
 * 寄存器分配后的优化器
 * 参考example实现，进行多种后端优化
 */
public class PostRegisterOptimizer {
    
    private final Armv8Function function;
    private LinkedHashMap<Armv8Block, LivenessAnalyzer.LivenessInfo> livenessInfoMap;
    private LinkedHashSet<Armv8Reg> modifiedRegs;
    private boolean hasOptimizations = false;
    
    public PostRegisterOptimizer(Armv8Function function) {
        this.function = function;
        this.modifiedRegs = new LinkedHashSet<>();
    }
    
    /**
     * 执行所有的寄存器分配后优化
     */
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
        
        // 最后一轮清理
        finalCleanup();
        
        if (round > 1) {
            System.out.println("函数 " + function.getName() + " 后端优化完成，共 " + (round - 1) + " 轮");
        }
    }
    
    /**
     * 删除冗余的move指令
     */
    private boolean removeRedundantMoves() {
        boolean changed = false;
        
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            Iterator<Armv8Instruction> iterator = instructions.iterator();
            
            while (iterator.hasNext()) {
                Armv8Instruction instruction = iterator.next();
                
                if (instruction instanceof Armv8Move) {
                    Armv8Move moveInst = (Armv8Move) instruction;
                    
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
    
    /**
     * 优化算术运算
     */
    private boolean optimizeArithmetic() {
        boolean changed = false;
        
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            
            for (int i = 0; i < instructions.size(); i++) {
                Armv8Instruction inst = instructions.get(i);
                
                if (inst instanceof Armv8Binary) {
                    Armv8Binary binInst = (Armv8Binary) inst;
                    
                    // 优化 add reg, reg, 0
                    if (binInst.getInstType() == Armv8Binary.Armv8BinaryType.add &&
                        binInst.getOperands().size() >= 2 &&
                        binInst.getOperands().get(1) instanceof Armv8Imm) {
                        
                        Armv8Imm imm = (Armv8Imm) binInst.getOperands().get(1);
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
                                Armv8Move moveInst = new Armv8Move(
                                    binInst.getDefReg(),
                                    (Armv8Reg) binInst.getOperands().get(0),
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
                    if (binInst.getInstType() == Armv8Binary.Armv8BinaryType.sub &&
                        binInst.getOperands().size() >= 2 &&
                        binInst.getOperands().get(1) instanceof Armv8Imm) {
                        
                        Armv8Imm imm = (Armv8Imm) binInst.getOperands().get(1);
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
                                Armv8Move moveInst = new Armv8Move(
                                    binInst.getDefReg(),
                                    (Armv8Reg) binInst.getOperands().get(0),
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
    
    /**
     * 删除未使用的指令
     */
    private boolean removeUnusedInstructions() {
        boolean changed = false;
        
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            Iterator<Armv8Instruction> iterator = instructions.iterator();
            
            while (iterator.hasNext()) {
                Armv8Instruction inst = iterator.next();
                
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
    
    /**
     * 合并内存操作
     */
    private boolean combineMemoryOperations() {
        boolean changed = false;
        
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            
            for (int i = 0; i < instructions.size() - 1; i++) {
                Armv8Instruction current = instructions.get(i);
                Armv8Instruction next = instructions.get(i + 1);
                
                // store + load 优化
                if (current instanceof Armv8Store && next instanceof Armv8Load) {
                    if (canCombineStoreLoad((Armv8Store) current, (Armv8Load) next)) {
                        // store reg1, addr; load reg2, addr -> store reg1, addr; mov reg2, reg1
                        Armv8Store store = (Armv8Store) current;
                        Armv8Load load = (Armv8Load) next;
                        
                        if (store.getOperands().size() >= 3 && 
                            store.getOperands().get(0) instanceof Armv8Reg) {
                            Armv8Move moveInst = new Armv8Move(
                                load.getDefReg(),
                                (Armv8Reg) store.getOperands().get(0),
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
    
    /**
     * 优化条件分支
     */
    private boolean optimizeConditionalBranches() {
        boolean changed = false;
        
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            
            // 查找连续的比较和分支指令
            for (int i = 0; i < instructions.size() - 1; i++) {
                Armv8Instruction current = instructions.get(i);
                Armv8Instruction next = instructions.get(i + 1);
                
                if (current instanceof Armv8Compare && next instanceof Armv8Branch) {
                    // 可以在这里添加比较分支的优化逻辑
                    // 例如：cmp reg, 0; beq -> cbz reg
                    // 暂时保持简单实现
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 最终清理
     */
    private void finalCleanup() {
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            
            // 删除连续的相同move指令
            for (int i = 0; i < instructions.size() - 1; i++) {
                Armv8Instruction current = instructions.get(i);
                Armv8Instruction next = instructions.get(i + 1);
                
                if (current instanceof Armv8Move && next instanceof Armv8Move) {
                    Armv8Move move1 = (Armv8Move) current;
                    Armv8Move move2 = (Armv8Move) next;
                    
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
    
    // 辅助方法
    
    /**
     * 检查move指令是否是冗余的
     */
    private boolean isRedundantMove(Armv8Move moveInst) {
        if (moveInst.getOperands().size() > 0 && 
            moveInst.getOperands().get(0) instanceof Armv8Reg &&
            moveInst.getDefReg() != null) {
            
            Armv8Reg src = (Armv8Reg) moveInst.getOperands().get(0);
            Armv8Reg dst = moveInst.getDefReg();
            
            return src.equals(dst);
        }
        
        return false;
    }
    
    /**
     * 检查指令是否可以删除
     */
    private boolean canRemoveInstruction(Armv8Instruction inst, Armv8Block block) {
        // 有副作用的指令不能删除
        if (hasSideEffects(inst)) {
            return false;
        }
        
        // 如果指令定义的寄存器没有被使用，可以删除
        if (inst.getDefReg() instanceof Armv8PhyReg) {
            LivenessAnalyzer.LivenessInfo liveness = livenessInfoMap.get(block);
            if (liveness != null && !liveness.getLiveOut().contains(inst.getDefReg())) {
                return !isRegisterUsedLater(inst.getDefReg(), inst, block);
            }
        }
        
        return false;
    }
    
    /**
     * 检查指令是否有副作用
     */
    private boolean hasSideEffects(Armv8Instruction instruction) {
        return instruction instanceof Armv8Store ||
               instruction instanceof Armv8StorePair ||
               instruction instanceof Armv8Call ||
               instruction instanceof Armv8BlrCall ||
               instruction instanceof Armv8Branch ||
               instruction instanceof Armv8Jump ||
               instruction instanceof Armv8Ret ||
               instruction instanceof Armv8Compare ||
               instruction instanceof Armv8Syscall;
    }
    
    /**
     * 检查寄存器是否在后续被使用
     */
    private boolean isRegisterUsedLater(Armv8Reg reg, Armv8Instruction fromInst, Armv8Block block) {
        // 简化实现：保守地认为物理寄存器都会被使用
        return true;
    }
    
    /**
     * 检查store和load是否可以合并
     */
    private boolean canCombineStoreLoad(Armv8Store store, Armv8Load load) {
        // 检查是否访问相同的内存地址
        if (store.getOperands().size() >= 3 && load.getOperands().size() >= 2) {
            // 简化检查：基址寄存器和偏移量是否相同
            return store.getOperands().get(1).equals(load.getOperands().get(0)) &&
                   store.getOperands().get(2).equals(load.getOperands().get(1));
        }
        return false;
    }
} 
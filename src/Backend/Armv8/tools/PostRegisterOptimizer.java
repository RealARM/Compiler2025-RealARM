package Backend.Armv8.tools;

import Backend.Armv8.Instruction.*;
import Backend.Armv8.Operand.*;
import Backend.Armv8.Structure.*;
import java.util.*;

/**
 * 寄存器分配后的优化器
 * 用于删除不必要的move指令和执行其他简单优化
 */
public class PostRegisterOptimizer {
    
    private final Armv8Function function;
    private boolean hasChanges = false;
    
    public PostRegisterOptimizer(Armv8Function function) {
        this.function = function;
    }
    
    /**
     * 执行所有的寄存器分配后优化
     */
    public void optimize() {
        do {
            hasChanges = false;
            
            // 删除无用的move指令
            removeRedundantMoves();
            
            // 删除死代码
            removeDeadCode();
            
            // 合并相邻的栈操作
            combineStackOperations();
            
        } while (hasChanges);
        
        if (hasChanges) {
            System.out.println("函数 " + function.getName() + " 的寄存器分配后优化完成");
        }
    }
    
    /**
     * 删除冗余的move指令
     * 例如：mov x1, x1 这样的指令
     */
    private void removeRedundantMoves() {
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            Iterator<Armv8Instruction> iterator = instructions.iterator();
            
            while (iterator.hasNext()) {
                Armv8Instruction instruction = iterator.next();
                
                if (instruction instanceof Armv8Move) {
                    Armv8Move moveInst = (Armv8Move) instruction;
                    
                    // 检查是否是冗余的move指令
                    if (isRedundantMove(moveInst)) {
                        iterator.remove();
                        hasChanges = true;
                        System.out.println("删除冗余的move指令: " + moveInst);
                    }
                }
            }
        }
    }
    
    /**
     * 检查move指令是否是冗余的
     */
    private boolean isRedundantMove(Armv8Move moveInst) {
        // 检查源操作数和目标寄存器是否相同
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
     * 删除死代码（定义了但从未使用的寄存器）
     */
    private void removeDeadCode() {
        // 构建活跃性信息
        LinkedHashMap<Armv8Block, LivenessAnalyzer.LivenessInfo> livenessMap = 
            LivenessAnalyzer.analyzeLiveness(function);
        
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            Iterator<Armv8Instruction> iterator = instructions.iterator();
            
            while (iterator.hasNext()) {
                Armv8Instruction instruction = iterator.next();
                
                // 检查是否是死代码
                if (isDeadCode(instruction, livenessMap.get(block))) {
                    iterator.remove();
                    hasChanges = true;
                    System.out.println("删除死代码: " + instruction);
                }
            }
        }
    }
    
    /**
     * 检查指令是否是死代码
     */
    private boolean isDeadCode(Armv8Instruction instruction, LivenessAnalyzer.LivenessInfo livenessInfo) {
        // 如果指令有副作用，不能删除
        if (hasSideEffects(instruction)) {
            return false;
        }
        
        // 如果指令定义的寄存器不在活跃出口集合中，可能是死代码
        Armv8Reg defReg = instruction.getDefReg();
        if (defReg instanceof Armv8PhyReg && 
            !livenessInfo.getLiveOut().contains(defReg)) {
            
            // 进一步检查是否真的没有被使用
            return !isRegisterUsedLater(defReg, instruction);
        }
        
        return false;
    }
    
    /**
     * 检查指令是否有副作用
     */
    private boolean hasSideEffects(Armv8Instruction instruction) {
        // 以下指令类型有副作用，不能删除
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
     * 检查寄存器是否在后续指令中被使用
     */
    private boolean isRegisterUsedLater(Armv8Reg reg, Armv8Instruction currentInst) {
        // 简化实现：假设物理寄存器都可能被后续使用
        // 实际应该进行更精确的数据流分析
        return true;
    }
    
    /**
     * 合并相邻的栈操作
     * 例如：多个连续的栈指针调整可以合并为一个
     */
    private void combineStackOperations() {
        for (Armv8Block block : function.getBlocks()) {
            List<Armv8Instruction> instructions = block.getInstructions();
            
            for (int i = 0; i < instructions.size() - 1; i++) {
                Armv8Instruction current = instructions.get(i);
                Armv8Instruction next = instructions.get(i + 1);
                
                // 检查是否可以合并栈操作
                if (canCombineStackOperations(current, next)) {
                    // 合并操作
                    Armv8Instruction combined = combineStackInstructions(current, next);
                    if (combined != null) {
                        instructions.set(i, combined);
                        instructions.remove(i + 1);
                        hasChanges = true;
                        System.out.println("合并栈操作: " + current + " 和 " + next + " -> " + combined);
                        i--; // 重新检查当前位置
                    }
                }
            }
        }
    }
    
    /**
     * 检查两个指令是否可以合并栈操作
     */
    private boolean canCombineStackOperations(Armv8Instruction inst1, Armv8Instruction inst2) {
        // 简化实现：检查是否都是对栈指针的加法或减法操作
        if (inst1 instanceof Armv8Binary && inst2 instanceof Armv8Binary) {
            Armv8Binary bin1 = (Armv8Binary) inst1;
            Armv8Binary bin2 = (Armv8Binary) inst2;
            
            // 检查是否都是对栈指针的操作
            if (isStackPointerOperation(bin1) && isStackPointerOperation(bin2)) {
                return bin1.getInstType() == bin2.getInstType() &&
                       (bin1.getInstType() == Armv8Binary.Armv8BinaryType.add ||
                        bin1.getInstType() == Armv8Binary.Armv8BinaryType.sub);
            }
        }
        
        return false;
    }
    
    /**
     * 检查指令是否是栈指针操作
     */
    private boolean isStackPointerOperation(Armv8Binary binInst) {
        if (binInst.getDefReg() instanceof Armv8CPUReg) {
            Armv8CPUReg defReg = (Armv8CPUReg) binInst.getDefReg();
            if (defReg.equals(Armv8CPUReg.getArmv8SpReg())) {
                // 检查源操作数是否也是栈指针
                if (binInst.getOperands().size() > 0 &&
                    binInst.getOperands().get(0) instanceof Armv8CPUReg) {
                    Armv8CPUReg srcReg = (Armv8CPUReg) binInst.getOperands().get(0);
                    return srcReg.equals(Armv8CPUReg.getArmv8SpReg());
                }
            }
        }
        return false;
    }
    
    /**
     * 合并两个栈指令
     */
    private Armv8Instruction combineStackInstructions(Armv8Instruction inst1, Armv8Instruction inst2) {
        if (inst1 instanceof Armv8Binary && inst2 instanceof Armv8Binary) {
            Armv8Binary bin1 = (Armv8Binary) inst1;
            Armv8Binary bin2 = (Armv8Binary) inst2;
            
            // 获取立即数
            if (bin1.getOperands().size() >= 2 && bin2.getOperands().size() >= 2 &&
                bin1.getOperands().get(1) instanceof Armv8Imm &&
                bin2.getOperands().get(1) instanceof Armv8Imm) {
                
                Armv8Imm imm1 = (Armv8Imm) bin1.getOperands().get(1);
                Armv8Imm imm2 = (Armv8Imm) bin2.getOperands().get(1);
                
                long combinedValue;
                if (bin1.getInstType() == Armv8Binary.Armv8BinaryType.add) {
                    combinedValue = imm1.getValue() + imm2.getValue();
                } else {
                    combinedValue = imm1.getValue() + imm2.getValue(); // 两个减法
                }
                
                // 创建新的合并指令
                ArrayList<Armv8Operand> operands = new ArrayList<>();
                operands.add(bin1.getOperands().get(0));
                operands.add(new Armv8Imm(combinedValue));
                
                return new Armv8Binary(operands, bin1.getDefReg(), bin1.getInstType());
            }
        }
        
        return null;
    }
    
    /**
     * 删除空的基本块
     */
    private void removeEmptyBlocks() {
        Iterator<Armv8Block> blockIterator = function.getBlocks().iterator();
        
        while (blockIterator.hasNext()) {
            Armv8Block block = blockIterator.next();
            
            if (block.getInstructions().isEmpty() && block.getPreds().size() <= 1) {
                // 更新前驱和后继关系
                for (Armv8Block pred : block.getPreds()) {
                    pred.getSuccs().remove(block);
                    pred.getSuccs().addAll(block.getSuccs());
                }
                
                for (Armv8Block succ : block.getSuccs()) {
                    succ.getPreds().remove(block);
                    succ.getPreds().addAll(block.getPreds());
                }
                
                blockIterator.remove();
                hasChanges = true;
                System.out.println("删除空基本块: " + block.getName());
            }
        }
    }
    
    /**
     * 获取优化统计信息
     */
    public void printOptimizationStats() {
        int totalInstructions = 0;
        for (Armv8Block block : function.getBlocks()) {
            totalInstructions += block.getInstructions().size();
        }
        
        System.out.println("=== 优化后统计 ===");
        System.out.println("函数: " + function.getName());
        System.out.println("基本块数量: " + function.getBlocks().size());
        System.out.println("指令总数: " + totalInstructions);
    }
} 
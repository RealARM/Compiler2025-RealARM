package MiddleEnd.Optimization.Cleanup;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.MoveInstruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.Optimization.Analysis.PhiEliminationUtils;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.*;

/**
 * PHI指令消除优化Pass
 * 将SSA形式中的PHI指令转换为普通的Move指令
 * 使用拓扑排序和破环算法处理循环依赖
 */
public class RemovePhiPass implements Optimizer.ModuleOptimizer {
    
    private static final boolean DEBUG = true;

    @Override
    public String getName() {
        return "RemovePhiPass";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        // 对每个函数进行PHI消除
        for (Function function : module.functions()) {
            if (module.libFunctions().contains(function)) {
                continue; // 跳过库函数
            }
            
            if (runOnFunction(function)) {
                changed = true;
            }
        }
        
        return changed;
    }
    
    /**
     * 对单个函数进行PHI消除
     */
    private boolean runOnFunction(Function function) {
        if (DEBUG) {
            System.out.println("RemovePhiPass: 处理函数 " + function.getName());
        }
        
        // 步骤1：收集所有PHI指令
        List<PhiInstruction> allPhis = collectPhiInstructions(function);
        
        if (allPhis.isEmpty()) {
            if (DEBUG) {
                System.out.println("  没有PHI指令，跳过处理");
            }
            return false;
        }
        
        if (DEBUG) {
            System.out.println("  总共找到 " + allPhis.size() + " 个PHI指令");
        }
        
        // 步骤2：为每个PHI指令生成对应的Move指令，并立即替换引用
        processPhiInstructions(function, allPhis);
        
        // 步骤5：删除所有PHI指令
        removePhiInstructions(allPhis);
        
        if (DEBUG) {
            System.out.println("RemovePhiPass: 函数 " + function.getName() + " 处理完成");
        }
        
        return true;
    }
    
    /**
     * 收集函数中的所有PHI指令
     */
    private List<PhiInstruction> collectPhiInstructions(Function function) {
        List<PhiInstruction> allPhis = new ArrayList<>();
        
        for (BasicBlock bb : function.getBasicBlocks()) {
            for (Instruction inst : new ArrayList<>(bb.getInstructions())) {
                if (inst instanceof PhiInstruction) {
                    allPhis.add((PhiInstruction) inst);
                    if (DEBUG) {
                        System.out.println("  发现PHI指令: " + inst.toString());
                    }
                }
            }
        }
        
        return allPhis;
    }
    
    /**
     * 处理PHI指令：使用正确的PHI消除算法
     */
    private void processPhiInstructions(Function function, List<PhiInstruction> allPhis) {
        if (DEBUG) {
            System.out.println("  开始处理PHI指令...");
        }
        
        // 步骤1：为每个PHI指令创建代表性的Move指令并插入
        Map<PhiInstruction, MoveInstruction> phiToRepresentativeMove = new HashMap<>();
        Map<BasicBlock, List<Instruction>> waitAddedMoves = new LinkedHashMap<>();
        
        // 初始化每个基本块的Move指令列表
        for (PhiInstruction phi : allPhis) {
            for (BasicBlock bb : phi.getIncomingBlocks()) {
                waitAddedMoves.computeIfAbsent(bb, k -> new ArrayList<>());
            }
        }
        
        // 为每个PHI指令生成Move指令
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  处理PHI: " + phi.getName() + " 有 " + phi.getIncomingBlocks().size() + " 个输入");
            }
            
            for (BasicBlock incomingBlock : phi.getIncomingBlocks()) {
                Value incomingValue = phi.getIncomingValue(incomingBlock);
                
                // 创建Move指令：phi_var = mov incoming_value
                MoveInstruction move = new MoveInstruction(phi.getName(), phi.getType(), incomingValue);
                waitAddedMoves.get(incomingBlock).add(move);
                
                // 将第一个Move指令作为该PHI的代表性Move指令
                if (!phiToRepresentativeMove.containsKey(phi)) {
                    phiToRepresentativeMove.put(phi, move);
                }
                
                if (DEBUG) {
                    System.out.println("    在 " + incomingBlock.getName() + " 中添加: " + phi.getName() + " = mov " + incomingValue.getName());
                }
            }
        }
        
        // 步骤2：插入Move指令，使用循环依赖处理
        for (Map.Entry<BasicBlock, List<Instruction>> entry : waitAddedMoves.entrySet()) {
            BasicBlock bb = entry.getKey();
            List<Instruction> moves = entry.getValue();
            
            if (!moves.isEmpty()) {
                if (DEBUG) {
                    System.out.println("  在 " + bb.getName() + " 中插入 " + moves.size() + " 个Move指令");
                }
                
                // 使用工具类处理循环依赖
                PhiEliminationUtils.insertMovesWithCycleResolution(bb, moves, DEBUG);
            }
        }
        
        // 步骤3：更新所有对PHI指令的引用，指向代表性的Move指令
        updatePhiReferences(function, phiToRepresentativeMove);
        
        if (DEBUG) {
            System.out.println("  PHI指令处理完成，已更新所有引用");
        }
    }
    
    /**
     * 替换对特定PHI指令的所有引用
     */
    private void replacePhiUsages(Function function, PhiInstruction phi, MoveInstruction replacement) {
        if (DEBUG) {
            System.out.println("    替换对 " + phi.getName() + " 的引用为 " + replacement.getName());
        }
        
        for (BasicBlock bb : function.getBasicBlocks()) {
            for (Instruction inst : bb.getInstructions()) {
                if (inst == phi || inst == replacement) {
                    continue;
                }
                
                // 检查指令的所有操作数
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    Value operand = inst.getOperand(i);
                    if (operand == phi) {
                        if (DEBUG) {
                            System.out.println("      在 " + bb.getName() + " 中替换 " + inst.toString());
                        }
                        inst.setOperand(i, replacement);
                    }
                }
            }
        }
    }
    
    /**
     * 为每个PHI指令生成对应的Move指令
     */
    private Map<BasicBlock, List<Instruction>> generateMoveInstructions(List<PhiInstruction> allPhis, 
                                                                       Map<PhiInstruction, MoveInstruction> phiToMoveMap) {
        Map<BasicBlock, List<Instruction>> waitAddedMoves = new LinkedHashMap<>();
        
        // 初始化每个基本块的Move指令列表
        for (PhiInstruction phi : allPhis) {
            for (BasicBlock bb : phi.getIncomingBlocks()) {
                waitAddedMoves.computeIfAbsent(bb, k -> new ArrayList<>());
            }
        }
        
        // 为每个PHI指令生成Move指令
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  处理PHI: " + phi.getName() + " 有 " + phi.getIncomingBlocks().size() + " 个输入");
            }
            
            for (BasicBlock incomingBlock : phi.getIncomingBlocks()) {
                Value incomingValue = phi.getIncomingValue(incomingBlock);
                
                // 创建Move指令：phi_var = mov incoming_value
                MoveInstruction move = new MoveInstruction(phi.getName(), phi.getType(), incomingValue);
                waitAddedMoves.get(incomingBlock).add(move);
                
                // 记录PHI到Move的映射（使用第一个Move指令作为代表）
                if (!phiToMoveMap.containsKey(phi)) {
                    phiToMoveMap.put(phi, move);
                }
                
                if (DEBUG) {
                    System.out.println("    在 " + incomingBlock.getName() + " 中添加: " + move.toString());
                }
            }
        }
        
        return waitAddedMoves;
    }
    
    /**
     * 在各个基本块中插入Move指令
     */
    private void insertMoveInstructions(Map<BasicBlock, List<Instruction>> waitAddedMoves) {
        for (Map.Entry<BasicBlock, List<Instruction>> entry : waitAddedMoves.entrySet()) {
            BasicBlock bb = entry.getKey();
            List<Instruction> moves = entry.getValue();
            
            if (!moves.isEmpty()) {
                if (DEBUG) {
                    System.out.println("  在 " + bb.getName() + " 中插入 " + moves.size() + " 个Move指令");
                }
                
                // 使用工具类处理循环依赖
                PhiEliminationUtils.insertMovesWithCycleResolution(bb, moves, DEBUG);
            }
        }
    }
    
    /**
     * 更新所有对PHI指令的引用，将它们指向代表性的Move指令
     */
    private void updatePhiReferences(Function function, Map<PhiInstruction, MoveInstruction> phiToMoveMap) {
        if (DEBUG) {
            System.out.println("  开始更新PHI引用...");
        }
        
        int replacementCount = 0;
        
        // 遍历所有基本块和指令
        for (BasicBlock bb : function.getBasicBlocks()) {
            for (Instruction inst : bb.getInstructions()) {
                // 跳过PHI指令本身，但不跳过Move指令！
                if (inst instanceof PhiInstruction) {
                    continue;
                }
                
                // 检查指令的所有操作数
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    Value operand = inst.getOperand(i);
                    
                    // 如果操作数是PHI指令，则替换为代表性的Move指令
                    if (phiToMoveMap.containsKey(operand)) {
                        MoveInstruction representativeMove = phiToMoveMap.get(operand);
                        
                        if (DEBUG) {
                            System.out.println("    替换 " + inst.toString() + " 中的PHI引用 " + operand.getName() + " 为Move指令 " + representativeMove.getName());
                        }
                        
                        inst.setOperand(i, representativeMove);
                        replacementCount++;
                    }
                }
            }
        }
        
        if (DEBUG) {
            System.out.println("  PHI引用更新完成，共替换 " + replacementCount + " 处引用");
        }
    }
    
    /**
     * 删除所有PHI指令
     */
    private void removePhiInstructions(List<PhiInstruction> allPhis) {
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  删除PHI指令: " + phi.getName());
            }
            phi.removeFromParent();
        }
    }
} 
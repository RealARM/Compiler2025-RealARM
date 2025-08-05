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
 * PHI指令消除
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
        
        for (Function function : module.functions()) {
            if (module.libFunctions().contains(function)) {
                continue;
            }
            
            if (runOnFunction(function)) {
                changed = true;
            }
        }
        
        return changed;
    }
    
    private boolean runOnFunction(Function function) {
        if (DEBUG) {
            System.out.println("RemovePhiPass: 处理函数 " + function.getName());
        }
        
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
        
        processPhiInstructions(function, allPhis);
        
        removePhiInstructions(allPhis);
        
        if (DEBUG) {
            System.out.println("RemovePhiPass: 函数 " + function.getName() + " 处理完成");
        }
        
        return true;
    }
    
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
    
    private void processPhiInstructions(Function function, List<PhiInstruction> allPhis) {
        if (DEBUG) {
            System.out.println("  开始处理PHI指令...");
        }
        
        Map<PhiInstruction, MoveInstruction> phiToRepresentativeMove = new HashMap<>();
        Map<BasicBlock, List<Instruction>> waitAddedMoves = new LinkedHashMap<>();
        
        for (PhiInstruction phi : allPhis) {
            for (BasicBlock bb : phi.getIncomingBlocks()) {
                waitAddedMoves.computeIfAbsent(bb, k -> new ArrayList<>());
            }
        }
        
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  处理PHI: " + phi.getName() + " 有 " + phi.getIncomingBlocks().size() + " 个输入");
            }
            
            for (BasicBlock incomingBlock : phi.getIncomingBlocks()) {
                Value incomingValue = phi.getIncomingValue(incomingBlock);
                
                MoveInstruction move = new MoveInstruction(phi.getName(), phi.getType(), incomingValue);
                waitAddedMoves.get(incomingBlock).add(move);
                
                if (!phiToRepresentativeMove.containsKey(phi)) {
                    phiToRepresentativeMove.put(phi, move);
                }
                
                if (DEBUG) {
                    System.out.println("    在 " + incomingBlock.getName() + " 中添加: " + phi.getName() + " = mov " + incomingValue.getName());
                }
            }
        }
        
        for (Map.Entry<BasicBlock, List<Instruction>> entry : waitAddedMoves.entrySet()) {
            BasicBlock bb = entry.getKey();
            List<Instruction> moves = entry.getValue();
            
            if (!moves.isEmpty()) {
                if (DEBUG) {
                    System.out.println("  在 " + bb.getName() + " 中插入 " + moves.size() + " 个Move指令");
                }
                
                PhiEliminationUtils.insertMovesWithCycleResolution(bb, moves, DEBUG);
            }
        }
        
        if (DEBUG) {
            System.out.println("  PHI代表Move映射 (代表Move所在基本块):");
            for (java.util.Map.Entry<PhiInstruction, MoveInstruction> entry : phiToRepresentativeMove.entrySet()) {
                BasicBlock repBB = entry.getValue().getParent();
                System.out.println("    " + entry.getKey().getName() + " -> " + (repBB != null ? repBB.getName() : "<unknown>") );
            }
        }
        
        updatePhiReferences(function, phiToRepresentativeMove);
        
        if (DEBUG) {
            System.out.println("  PHI指令处理完成，已更新所有引用");
        }
    }
    
    private void replacePhiUsages(Function function, PhiInstruction phi, MoveInstruction replacement) {
        if (DEBUG) {
            System.out.println("    替换对 " + phi.getName() + " 的引用为 " + replacement.getName());
        }
        
        for (BasicBlock bb : function.getBasicBlocks()) {
            for (Instruction inst : bb.getInstructions()) {
                if (inst == phi || inst == replacement) {
                    continue;
                }
                
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
    
    private Map<BasicBlock, List<Instruction>> generateMoveInstructions(List<PhiInstruction> allPhis, 
                                                                       Map<PhiInstruction, MoveInstruction> phiToMoveMap) {
        Map<BasicBlock, List<Instruction>> waitAddedMoves = new LinkedHashMap<>();
        
        for (PhiInstruction phi : allPhis) {
            for (BasicBlock bb : phi.getIncomingBlocks()) {
                waitAddedMoves.computeIfAbsent(bb, k -> new ArrayList<>());
            }
        }
        
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  处理PHI: " + phi.getName() + " 有 " + phi.getIncomingBlocks().size() + " 个输入");
            }
            
            for (BasicBlock incomingBlock : phi.getIncomingBlocks()) {
                Value incomingValue = phi.getIncomingValue(incomingBlock);
                
                MoveInstruction move = new MoveInstruction(phi.getName(), phi.getType(), incomingValue);
                waitAddedMoves.get(incomingBlock).add(move);
                
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
    
    private void insertMoveInstructions(Map<BasicBlock, List<Instruction>> waitAddedMoves) {
        for (Map.Entry<BasicBlock, List<Instruction>> entry : waitAddedMoves.entrySet()) {
            BasicBlock bb = entry.getKey();
            List<Instruction> moves = entry.getValue();
            
            if (!moves.isEmpty()) {
                if (DEBUG) {
                    System.out.println("  在 " + bb.getName() + " 中插入 " + moves.size() + " 个Move指令");
                }
                
                PhiEliminationUtils.insertMovesWithCycleResolution(bb, moves, DEBUG);
            }
        }
    }
    
    private void updatePhiReferences(Function function, Map<PhiInstruction, MoveInstruction> phiToMoveMap) {
        if (DEBUG) {
            System.out.println("  开始更新PHI引用...");
        }
        Map<BasicBlock, java.util.Set<BasicBlock>> dominatorMap = MiddleEnd.Optimization.Analysis.DominatorAnalysis.computeDominators(function);
        
        int replacementCount = 0;
        
        for (BasicBlock bb : function.getBasicBlocks()) {
            for (Instruction inst : bb.getInstructions()) {
                if (inst instanceof PhiInstruction) {
                    continue;
                }
                
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    Value operand = inst.getOperand(i);
                    
                    if (phiToMoveMap.containsKey(operand)) {
                        MoveInstruction representativeMove = phiToMoveMap.get(operand);
                        
                        boolean dominates = false;
                        BasicBlock repBB = null;
                        if (representativeMove != null && representativeMove.getParent() != null) {
                            repBB = representativeMove.getParent();
                            java.util.Set<BasicBlock> domSet = dominatorMap.get(bb);
                            dominates = domSet != null && domSet.contains(repBB);
                        }
                        if (DEBUG) {
                            if (repBB != null) {
                                System.out.println("    替换 " + inst.toString() + " 中的PHI引用 " + operand.getName() + " 为Move指令 " + representativeMove.getName() +
                                        " (" + repBB.getName() + " -> " + bb.getName() + " 支配? " + dominates + ")");
                            } else {
                                System.out.println("    替换 " + inst.toString() + " 中的PHI引用 " + operand.getName() + " 为Move指令 " + representativeMove.getName());
                            }
                        }
                        if (dominates || repBB == null) {
                                inst.setOperand(i, representativeMove);
                                replacementCount++;
                        } else {
                            if (DEBUG) {
                                System.out.println("      跳过替换，因为代表Move不支配使用位置");
                            }
                        }
                    }
                }
            }
        }
        
        if (DEBUG) {
            System.out.println("  PHI引用更新完成，共替换 " + replacementCount + " 处引用");
        }
    }
    
    private void removePhiInstructions(List<PhiInstruction> allPhis) {
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  删除PHI指令: " + phi.getName());
            }
            phi.removeFromParent();
        }
    }
} 
package Backend.RegisterManager;

import Backend.Structure.AArch64Block;
import Backend.Structure.AArch64Function;
import Backend.Utils.LivenessAnalyzer;
import Backend.Utils.Pair;
import Backend.Value.Base.*;
import Backend.Value.Instruction.DataMovement.*;
import Backend.Value.Operand.Register.*;

import java.util.*;

/**
 * 干扰图构建器
 * 负责收集候选寄存器、构建干扰图和处理Move指令
 */
public class InterferenceGraphBuilder {
    
    private final RegisterAllocationState state;
    
    public InterferenceGraphBuilder(RegisterAllocationState state) {
        this.state = state;
    }
    

    public void collectCandidateRegisters() {
        LinkedHashSet<AArch64VirReg> candidates = new LinkedHashSet<>();
        
        for (AArch64Block block : state.getFunction().getBlocks()) {
            for (AArch64Instruction instruction : block.getInstructions()) {
                // 收集定义的虚拟寄存器
                if (instruction.getDefReg() instanceof AArch64VirReg) {
                    AArch64VirReg virtualReg = (AArch64VirReg) instruction.getDefReg();
                    if (virtualReg.isFloat() == state.isFloatingPoint()) {
                        candidates.add(virtualReg);
                        ensureGraphNodeExists(virtualReg);
                    }
                }
                
                // 收集使用的虚拟寄存器
                for (AArch64Operand operand : instruction.getOperands()) {
                    if (operand instanceof AArch64VirReg) {
                        AArch64VirReg virtualReg = (AArch64VirReg) operand;
                        if (virtualReg.isFloat() == state.isFloatingPoint()) {
                            candidates.add(virtualReg);
                            ensureGraphNodeExists(virtualReg);
                        }
                    }
                }
            }
        }
        
        // System.out.println("收集到 " + candidates.size() + " 个虚拟寄存器");
    }
    

    public void buildInterferenceGraph() {
        for (AArch64Block block : state.getFunction().getBlocks()) {
            LivenessAnalyzer.LivenessInfo blockLiveness = state.getLivenessInfo().get(block);
            if (blockLiveness == null) continue;
            
            LinkedHashSet<AArch64Reg> liveRegisters = new LinkedHashSet<>();
            // 只处理当前类型的寄存器
            for (AArch64Reg register : blockLiveness.getLiveOut()) {
                if (register instanceof AArch64VirReg && 
                    ((AArch64VirReg) register).isFloat() == state.isFloatingPoint()) {
                    liveRegisters.add(register);
                }
            }
            
            // 反向遍历指令构建干扰图
            List<AArch64Instruction> instructionList = new ArrayList<>(block.getInstructions());
            Collections.reverse(instructionList);
            
            for (AArch64Instruction instruction : instructionList) {
                // 处理move指令的特殊情况
                if (instruction instanceof AArch64Move) {
                    processMoveInstruction((AArch64Move) instruction, liveRegisters);
                }
                
                // 为定义的寄存器添加干扰边
                if (instruction.getDefReg() instanceof AArch64VirReg) {
                    AArch64VirReg definedReg = (AArch64VirReg) instruction.getDefReg();
                    if (definedReg.isFloat() == state.isFloatingPoint()) {
                        ensureGraphNodeExists(definedReg);
                        
                        // 为定义的寄存器与所有活跃寄存器添加干扰边
                        for (AArch64Reg liveReg : liveRegisters) {
                            if (!liveReg.equals(definedReg)) {
                                addInterferenceEdge(definedReg, liveReg);
                            }
                        }
                        
                        liveRegisters.remove(definedReg);
                    }
                }
                
                // 添加使用的寄存器到活跃集合
                for (AArch64Operand operand : instruction.getOperands()) {
                    if (operand instanceof AArch64VirReg) {
                        AArch64VirReg usedReg = (AArch64VirReg) operand;
                        if (usedReg.isFloat() == state.isFloatingPoint()) {
                            ensureGraphNodeExists(usedReg);
                            liveRegisters.add(usedReg);
                        }
                    }
                }
            }
        }
        
        int totalEdges = 0;
        for (LinkedHashSet<AArch64Operand> neighbors : state.getAdjacencyGraph().values()) {
            totalEdges += neighbors.size();
        }
        // System.out.println("干扰图构建完成，冲突边数: " + (totalEdges / 2)); // 除以2因为是无向图
    }
    

    private void processMoveInstruction(AArch64Move moveInstruction, LinkedHashSet<AArch64Reg> liveRegisters) {
        if (moveInstruction.getOperands().size() > 0 && 
            moveInstruction.getOperands().get(0) instanceof AArch64Reg &&
            moveInstruction.getDefReg() instanceof AArch64Reg) {
            
            AArch64Reg sourceReg = (AArch64Reg) moveInstruction.getOperands().get(0);
            AArch64Reg targetReg = moveInstruction.getDefReg();
            
            if (sourceReg instanceof AArch64VirReg && targetReg instanceof AArch64VirReg &&
                ((AArch64VirReg) sourceReg).isFloat() == state.isFloatingPoint() &&
                ((AArch64VirReg) targetReg).isFloat() == state.isFloatingPoint()) {
                
                liveRegisters.remove(sourceReg);
                associateMoveWithNode(sourceReg, moveInstruction);
                associateMoveWithNode(targetReg, moveInstruction);
                state.getActiveMoveInstructions().add(moveInstruction);
            }
        }
    }
    

    private void ensureGraphNodeExists(AArch64Reg register) {
        if (!state.getAdjacencyGraph().containsKey(register)) {
            state.getAdjacencyGraph().put(register, new LinkedHashSet<>());
            state.getNodeDegrees().put(register, 0);
            state.getNodeMoveMap().put(register, new LinkedHashSet<>());
            state.getNodeAliases().put(register, register);
        }
    }
    

    private void associateMoveWithNode(AArch64Reg register, AArch64Move moveInstruction) {
        ensureGraphNodeExists(register);
        if (!state.getNodeMoveMap().containsKey(register)) {
            state.getNodeMoveMap().put(register, new LinkedHashSet<>());
        }
        state.getNodeMoveMap().get(register).add(moveInstruction);
    }
    

    private void addInterferenceEdge(AArch64Reg firstReg, AArch64Reg secondReg) {
        if (firstReg.equals(secondReg)) return;
        
        ensureGraphNodeExists(firstReg);
        ensureGraphNodeExists(secondReg);
        
        // 检查是否已经存在干扰边
        if (!state.getAdjacencyGraph().get(firstReg).contains(secondReg)) {
            state.addInterferenceEdge(firstReg, secondReg);
        }
    }
}
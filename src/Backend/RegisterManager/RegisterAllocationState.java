package Backend.RegisterManager;

import Backend.Structure.AArch64Block;
import Backend.Structure.AArch64Function;
import Backend.Utils.LivenessAnalyzer;
import Backend.Value.Base.*;
import Backend.Value.Instruction.DataMovement.*;
import Backend.Value.Operand.Register.*;

import java.util.*;

/**
 * 寄存器分配状态类
 */
public class RegisterAllocationState {
    
    private static final int INTEGER_REGISTER_COUNT = 10;  // 整型寄存器数量 (x8-x28, 排除x8)
    private static final int FLOATING_REGISTER_COUNT = 24; // 浮点寄存器数量 (v8-v31)
    
    private final AArch64Function function;
    private final boolean isFloatingPoint;
    private final int availableColors;
    private LinkedHashMap<AArch64Block, LivenessAnalyzer.LivenessInfo> livenessInfo;
    
    private LinkedHashMap<AArch64Operand, LinkedHashSet<AArch64Operand>> adjacencyGraph; // 干扰图邻接表
    private LinkedHashMap<AArch64Operand, AArch64Operand> nodeAliases;                  // 节点别名
    private LinkedHashMap<AArch64Reg, Integer> registerColors;                          // 最终颜色分配结果
    private LinkedHashMap<AArch64Operand, Integer> nodeDegrees;                          // 节点度数
    private LinkedHashSet<AArch64Operand> spilledNodes;                                 // 需要溢出的节点
    private LinkedHashMap<AArch64Operand, LinkedHashSet<AArch64Move>> nodeMoveMap;      // 节点关联的move指令
    private LinkedHashSet<AArch64Instruction> activeMoveInstructions;                   // 活跃的move指令


    public RegisterAllocationState(AArch64Function function, boolean isFloatingPoint) {
        this.function = function;
        this.isFloatingPoint = isFloatingPoint;
        this.availableColors = isFloatingPoint ? FLOATING_REGISTER_COUNT : INTEGER_REGISTER_COUNT;
        initializeCoreDataStructures();
    }
    
    /**
     * 初始化核心数据结构
     */
    private void initializeCoreDataStructures() {
        adjacencyGraph = new LinkedHashMap<>();
        nodeDegrees = new LinkedHashMap<>();
        nodeAliases = new LinkedHashMap<>();
        registerColors = new LinkedHashMap<>();
        
        nodeMoveMap = new LinkedHashMap<>();
        activeMoveInstructions = new LinkedHashSet<>();
        
        spilledNodes = new LinkedHashSet<>();
        
        livenessInfo = LivenessAnalyzer.analyzeLiveness(function);
    }
    
    /**
     * 重置核心状态
     */
    public void reset() {
        initializeCoreDataStructures();
    }
    
    public AArch64Function getFunction() { 
        return function; 
    }
    
    public boolean isFloatingPoint() { 
        return isFloatingPoint; 
    }
    
    public int getAvailableColors() { 
        return availableColors; 
    }
    
    public LinkedHashMap<AArch64Block, LivenessAnalyzer.LivenessInfo> getLivenessInfo() { 
        return livenessInfo; 
    }
    
    public LinkedHashMap<AArch64Operand, LinkedHashSet<AArch64Operand>> getAdjacencyGraph() { 
        return adjacencyGraph; 
    }
    
    public LinkedHashMap<AArch64Operand, Integer> getNodeDegrees() { 
        return nodeDegrees; 
    }
    
    public LinkedHashMap<AArch64Operand, AArch64Operand> getNodeAliases() { 
        return nodeAliases; 
    }
    
    public LinkedHashMap<AArch64Reg, Integer> getRegisterColors() { 
        return registerColors; 
    }
    
    public LinkedHashMap<AArch64Operand, LinkedHashSet<AArch64Move>> getNodeMoveMap() { 
        return nodeMoveMap; 
    }
    
    public LinkedHashSet<AArch64Instruction> getActiveMoveInstructions() { 
        return activeMoveInstructions; 
    }
    
    public LinkedHashSet<AArch64Operand> getSpilledNodes() { 
        return spilledNodes; 
    }
    
    /**
     * 添加干扰边
     */
    public void addInterferenceEdge(AArch64Operand node1, AArch64Operand node2) {
        adjacencyGraph.computeIfAbsent(node1, k -> new LinkedHashSet<>()).add(node2);
        adjacencyGraph.computeIfAbsent(node2, k -> new LinkedHashSet<>()).add(node1);
        
        nodeDegrees.put(node1, nodeDegrees.getOrDefault(node1, 0) + 1);
        nodeDegrees.put(node2, nodeDegrees.getOrDefault(node2, 0) + 1);
    }
    
    /**
     * 获取所有候选虚拟寄存器
     */
    public LinkedHashSet<AArch64VirReg> getCandidateRegisters() {
        LinkedHashSet<AArch64VirReg> candidates = new LinkedHashSet<>();
        for (AArch64Block block : function.getBlocks()) {
            for (AArch64Instruction instruction : block.getInstructions()) {
                // 收集定义的虚拟寄存器
                if (instruction.getDefReg() instanceof AArch64VirReg) {
                    AArch64VirReg virReg = (AArch64VirReg) instruction.getDefReg();
                    if (virReg.isFloat() == isFloatingPoint) {
                        candidates.add(virReg);
                    }
                }
                // 收集使用的虚拟寄存器
                for (AArch64Operand operand : instruction.getOperands()) {
                    if (operand instanceof AArch64VirReg) {
                        AArch64VirReg virReg = (AArch64VirReg) operand;
                        if (virReg.isFloat() == isFloatingPoint) {
                            candidates.add(virReg);
                        }
                    }
                }
            }
        }
        return candidates;
    }
}
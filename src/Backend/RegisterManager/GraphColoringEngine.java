package Backend.RegisterManager;

import Backend.Value.Base.*;
import Backend.Value.Instruction.DataMovement.*;
import Backend.Value.Operand.Register.*;

import java.util.*;

/**
 * 图着色器
 * 负责图着色算法的核心逻辑，包括简化、合并、冻结、溢出选择和颜色分配
 */
public class GraphColoringEngine {
    
    private final RegisterAllocationState state;
    private final WorklistManager worklists;
    
    public GraphColoringEngine(RegisterAllocationState state) {
        this.state = state;
        this.worklists = new WorklistManager();
    }
    
    /**
     * 内部管理器
     */
    private static class WorklistManager {
        // 节点信息
        private final LinkedHashSet<AArch64Operand> simplifyWorklist;    // 可简化的节点
        private final LinkedHashSet<AArch64Operand> spillWorklist;       // 高度数节点
        private final LinkedHashSet<AArch64Operand> coalescedNodes;      // 已合并节点
        private final LinkedHashSet<AArch64Reg> coloredNodes;            // 已着色节点
        private final LinkedHashSet<AArch64Operand> freezeWorklist;      // move相关的节点
        private final Stack<AArch64Operand> selectStack;                 // 选择栈
        
        // 消除冗余的move指令
        private final LinkedHashSet<AArch64Instruction> coalescedMoves;  // 成功合并的move
        private final LinkedHashSet<AArch64Instruction> constrainedMoves; // 冲突的move
        private final LinkedHashSet<AArch64Instruction> frozenMoves;     // 冻结的move
        private final LinkedHashSet<AArch64Instruction> worklistMoves;   // 待处理的move
        
        public WorklistManager() {
            simplifyWorklist = new LinkedHashSet<>();
            spillWorklist = new LinkedHashSet<>();
            coalescedNodes = new LinkedHashSet<>();
            coloredNodes = new LinkedHashSet<>();
            freezeWorklist = new LinkedHashSet<>();
            selectStack = new Stack<>();
            
            coalescedMoves = new LinkedHashSet<>();
            constrainedMoves = new LinkedHashSet<>();
            frozenMoves = new LinkedHashSet<>();
            worklistMoves = new LinkedHashSet<>();
        }
        
        public void reset() {
            simplifyWorklist.clear();
            freezeWorklist.clear();
            spillWorklist.clear();
            coalescedNodes.clear();
            coloredNodes.clear();
            selectStack.clear();
            
            coalescedMoves.clear();
            constrainedMoves.clear();
            frozenMoves.clear();
            worklistMoves.clear();
        }
        
        // Getter方法
        public LinkedHashSet<AArch64Operand> getSimplifyWorklist() { return simplifyWorklist; }
        public LinkedHashSet<AArch64Operand> getFreezeWorklist() { return freezeWorklist; }
        public LinkedHashSet<AArch64Operand> getSpillWorklist() { return spillWorklist; }
        public LinkedHashSet<AArch64Operand> getCoalescedNodes() { return coalescedNodes; }
        public LinkedHashSet<AArch64Reg> getColoredNodes() { return coloredNodes; }
        public Stack<AArch64Operand> getSelectStack() { return selectStack; }
        
        public LinkedHashSet<AArch64Instruction> getCoalescedMoves() { return coalescedMoves; }
        public LinkedHashSet<AArch64Instruction> getConstrainedMoves() { return constrainedMoves; }
        public LinkedHashSet<AArch64Instruction> getFrozenMoves() { return frozenMoves; }
        public LinkedHashSet<AArch64Instruction> getWorklistMoves() { return worklistMoves; }
        
        public boolean allWorklistsEmpty() {
            return simplifyWorklist.isEmpty() && 
                   worklistMoves.isEmpty() && 
                   freezeWorklist.isEmpty() && 
                   spillWorklist.isEmpty();
        }
    }
    
    /**
     * 将节点分类到各个工作列表中
     */
    public void categorizeNodesToWorklists() {
        // 重置工作列表
        worklists.reset();
        
        // 收集候选节点并分类
        for (AArch64VirReg candidate : state.getCandidateRegisters()) {
            int nodeDegree = state.getNodeDegrees().getOrDefault(candidate, 0);
            if (nodeDegree >= state.getAvailableColors()) {
                worklists.getSpillWorklist().add(candidate);
            } else if (isNodeMoveRelated(candidate)) {
                worklists.getFreezeWorklist().add(candidate);
            } else {
                worklists.getSimplifyWorklist().add(candidate);
            }
        }
        
        // 初始化move指令工作列表
        for (AArch64Instruction moveInst : state.getActiveMoveInstructions()) {
            worklists.getWorklistMoves().add(moveInst);
        }
    }
    
    /**
     * 图简化主循环
     */
    public void performGraphSimplificationLoop() {
        while (!worklists.allWorklistsEmpty()) {
            if (!worklists.getSimplifyWorklist().isEmpty()) {
                executeSimplificationStep();
            } else if (!worklists.getWorklistMoves().isEmpty()) {
                executeCoalescingStep();
            } else if (!worklists.getFreezeWorklist().isEmpty()) {
                executeFreezingStep();
            } else if (!worklists.getSpillWorklist().isEmpty()) {
                executeSpillSelectionStep();
            }
        }
    }
    
    /**
     * 执行简化步骤
     */
    private void executeSimplificationStep() {
        AArch64Operand nodeToRemove = worklists.getSimplifyWorklist().iterator().next();
        worklists.getSimplifyWorklist().remove(nodeToRemove);
        worklists.getSelectStack().push(nodeToRemove);
        
        for (AArch64Operand adjacentNode : getActiveAdjacentNodes(nodeToRemove)) {
            decrementNodeDegree(adjacentNode);
        }
    }
    
    /**
     * 执行合并步骤
     */
    private void executeCoalescingStep() {
        AArch64Instruction moveInstruction = worklists.getWorklistMoves().iterator().next();
        worklists.getWorklistMoves().remove(moveInstruction);
        
        if (!(moveInstruction instanceof AArch64Move)) return;
        AArch64Move moveOp = (AArch64Move) moveInstruction;
        
        if (moveOp.getOperands().size() == 0 || !(moveOp.getOperands().get(0) instanceof AArch64Reg)) {
            return;
        }
        
        AArch64Reg sourceNode = (AArch64Reg) moveOp.getOperands().get(0);
        AArch64Reg targetNode = moveOp.getDefReg();
        
        sourceNode = (AArch64Reg) resolveNodeAlias(sourceNode);
        targetNode = (AArch64Reg) resolveNodeAlias(targetNode);
        
        AArch64Reg primaryNode;
        AArch64Reg secondaryNode;
        if (targetNode instanceof AArch64PhyReg) {
            primaryNode = targetNode;
            secondaryNode = sourceNode;
        } else {
            primaryNode = sourceNode;
            secondaryNode = targetNode;
        }
        
        if (primaryNode.equals(secondaryNode)) {
            worklists.getCoalescedMoves().add(moveInstruction);
        } else if (secondaryNode instanceof AArch64PhyReg || 
                   hasInterferenceConflict(primaryNode, secondaryNode)) {
            worklists.getConstrainedMoves().add(moveInstruction);
        } else {
            worklists.getCoalescedMoves().add(moveInstruction);
        }
    }
    
    /**
     * 执行冻结步骤
     */
    private void executeFreezingStep() {
        AArch64Operand nodeToFreeze = worklists.getFreezeWorklist().iterator().next();
        worklists.getFreezeWorklist().remove(nodeToFreeze);
        worklists.getSimplifyWorklist().add(nodeToFreeze);
        freezeAssociatedMoves(nodeToFreeze);
    }
    
    /**
     * 执行溢出选择步骤
     */
    private void executeSpillSelectionStep() {
        AArch64Operand spillCandidate = selectOptimalSpillCandidate();
        worklists.getSpillWorklist().remove(spillCandidate);
        worklists.getSimplifyWorklist().add(spillCandidate);
        freezeAssociatedMoves(spillCandidate);
    }
    
    /**
     * 尝试为所有节点分配颜色
     * @return true表示成功分配所有节点，false表示有节点需要溢出
     */
    public boolean attemptColorAssignment() {
        Set<Integer> colorOptions = new HashSet<>();
        for (int i = 0; i < state.getAvailableColors(); i++) {
            colorOptions.add(i);
        }
        
        while (!worklists.getSelectStack().isEmpty()) {
            AArch64Operand currentNode = worklists.getSelectStack().pop();
            
            if (currentNode instanceof AArch64VirReg) {
                Set<Integer> restrictedColors = new HashSet<>();
                
                LinkedHashSet<AArch64Operand> neighbors = state.getAdjacencyGraph().get(currentNode);
                if (neighbors != null) {
                    for (AArch64Operand neighborNode : neighbors) {
                        AArch64Operand resolvedNeighbor = resolveNodeAlias(neighborNode);
                        if (worklists.getColoredNodes().contains(resolvedNeighbor) || 
                            resolvedNeighbor instanceof AArch64PhyReg) {
                            Integer neighborColor = state.getRegisterColors().get(resolvedNeighbor);
                            if (neighborColor != null) {
                                restrictedColors.add(neighborColor);
                            }
                        }
                    }
                }
                
                Set<Integer> allowedColors = new HashSet<>(colorOptions);
                allowedColors.removeAll(restrictedColors);
                
                if (allowedColors.isEmpty()) {
                    state.getSpilledNodes().add(currentNode);
                    System.out.println("寄存器 " + currentNode + " 溢出");
                } else {
                    int selectedColor = allowedColors.iterator().next();
                    state.getRegisterColors().put((AArch64Reg) currentNode, selectedColor);
                    worklists.getColoredNodes().add((AArch64Reg) currentNode);
                    System.out.println("为寄存器 " + currentNode + " 分配颜色 " + selectedColor);
                }
            }
        }
        
        // 为合并的节点分配颜色
        for (AArch64Operand coalescedNode : worklists.getCoalescedNodes()) {
            AArch64Operand aliasNode = resolveNodeAlias(coalescedNode);
            if (state.getRegisterColors().containsKey(aliasNode)) {
                state.getRegisterColors().put((AArch64Reg) coalescedNode, 
                                            state.getRegisterColors().get(aliasNode));
                System.out.println("合并节点 " + coalescedNode + " 继承颜色 " + 
                                 state.getRegisterColors().get(aliasNode));
            }
        }
        
        System.out.println("颜色分配完成，溢出节点数: " + state.getSpilledNodes().size());
        return state.getSpilledNodes().isEmpty();
    }
    
    /**
     * 冻结相关的Move指令
     */
    private void freezeAssociatedMoves(AArch64Operand node) {
        LinkedHashSet<AArch64Move> associatedMoves = state.getNodeMoveMap().get(node);
        if (associatedMoves != null) {
            for (AArch64Move moveInstruction : associatedMoves) {
                worklists.getWorklistMoves().remove(moveInstruction);
                worklists.getFrozenMoves().add(moveInstruction);
            }
        }
    }
    
    /**
     * 选择度数最高的节点溢出
     */
    private AArch64Operand selectOptimalSpillCandidate() {
        AArch64Operand bestCandidate = null;
        int highestDegree = -1;
        
        for (AArch64Operand operand : worklists.getSpillWorklist()) {
            if (state.getNodeDegrees().containsKey(operand) && 
                state.getNodeDegrees().get(operand) > highestDegree) {
                highestDegree = state.getNodeDegrees().get(operand);
                bestCandidate = operand;
            }
        }
        
        return bestCandidate != null ? bestCandidate : worklists.getSpillWorklist().iterator().next();
    }
    
    /**
     * 检查节点是否与Move指令相关
     */
    private boolean isNodeMoveRelated(AArch64Operand node) {
        LinkedHashSet<AArch64Move> moves = state.getNodeMoveMap().get(node);
        return moves != null && !moves.isEmpty();
    }
    
    /**
     * 获取活跃的相邻节点
     */
    private LinkedHashSet<AArch64Operand> getActiveAdjacentNodes(AArch64Operand node) {
        LinkedHashSet<AArch64Operand> result = new LinkedHashSet<>();
        LinkedHashSet<AArch64Operand> allNeighbors = state.getAdjacencyGraph().get(node);
        if (allNeighbors != null) {
            for (AArch64Operand neighbor : allNeighbors) {
                // 排除已在选择栈中和已合并的节点
                if (!worklists.getSelectStack().contains(neighbor) && 
                    !worklists.getCoalescedNodes().contains(neighbor)) {
                    result.add(neighbor);
                }
            }
        }
        return result;
    }
    
    /**
     * 递减节点度数
     */
    private void decrementNodeDegree(AArch64Operand node) {
        int currentDegree = state.getNodeDegrees().getOrDefault(node, 0);
        state.getNodeDegrees().put(node, currentDegree - 1);
        
        // 如果度数降到K-1，需要重新分类
        if (currentDegree == state.getAvailableColors()) {
            worklists.getSpillWorklist().remove(node);
            if (isNodeMoveRelated(node)) {
                worklists.getFreezeWorklist().add(node);
            } else {
                worklists.getSimplifyWorklist().add(node);
            }
        }
    }
    
    /**
     * 解析节点别名
     */
    private AArch64Operand resolveNodeAlias(AArch64Operand node) {
        AArch64Operand alias = state.getNodeAliases().get(node);
        if (alias != null && worklists.getCoalescedNodes().contains(node)) {
            return resolveNodeAlias(alias);
        }
        return node;
    }
    
    /**
     * 检查两个节点是否有干扰冲突
     */
    private boolean hasInterferenceConflict(AArch64Reg node1, AArch64Reg node2) {
        LinkedHashSet<AArch64Operand> neighbors = state.getAdjacencyGraph().get(node1);
        return neighbors != null && neighbors.contains(node2);
    }
}
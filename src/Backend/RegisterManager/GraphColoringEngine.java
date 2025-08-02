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
    private final NodeScheduler nodeScheduler;
    
    public GraphColoringEngine(RegisterAllocationState state) {
        this.state = state;
        this.nodeScheduler = new NodeScheduler();
    }
    
    private static class NodeScheduler {

        private final LinkedHashSet<AArch64Operand> highDegreeQueue;     // 高度数节点队列
        private final Stack<AArch64Operand> eliminationStack;            // 消除栈
        private final LinkedHashSet<AArch64Instruction> successfulMerges; // 成功合并的move
        private final LinkedHashSet<AArch64Instruction> blockedMoves;     // 冲突的move
        private final LinkedHashSet<AArch64Instruction> frozenMoves;      // 冻结的move
        private final LinkedHashSet<AArch64Instruction> pendingMoves;     // 待处理的move
        private final LinkedHashSet<AArch64Operand> readyToSimplify;     // 可简化的节点
        private final LinkedHashSet<AArch64Operand> mergedNodes;         // 已合并节点
        private final LinkedHashSet<AArch64Reg> successfullyColored;     // 已着色节点
        private final LinkedHashSet<AArch64Operand> moveConstrainedNodes; // move约束的节点
        
        public NodeScheduler() {
            readyToSimplify = new LinkedHashSet<>();
            highDegreeQueue = new LinkedHashSet<>();
            mergedNodes = new LinkedHashSet<>();
            successfullyColored = new LinkedHashSet<>();
            moveConstrainedNodes = new LinkedHashSet<>();
            eliminationStack = new Stack<>();
            
            successfulMerges = new LinkedHashSet<>();
            blockedMoves = new LinkedHashSet<>();
            frozenMoves = new LinkedHashSet<>();
            pendingMoves = new LinkedHashSet<>();
        }
        
        public void reset() {
            readyToSimplify.clear();
            moveConstrainedNodes.clear();
            highDegreeQueue.clear();
            mergedNodes.clear();
            successfullyColored.clear();
            eliminationStack.clear();
            
            successfulMerges.clear();
            blockedMoves.clear();
            frozenMoves.clear();
            pendingMoves.clear();
        }
        
        public LinkedHashSet<AArch64Operand> getReadyToSimplify() { return readyToSimplify; }
        
        public LinkedHashSet<AArch64Operand> getMoveConstrainedNodes() { return moveConstrainedNodes; }
        
        public LinkedHashSet<AArch64Operand> getHighDegreeQueue() { return highDegreeQueue; }
        
        public LinkedHashSet<AArch64Operand> getMergedNodes() { return mergedNodes; }
        
        public LinkedHashSet<AArch64Reg> getSuccessfullyColored() { return successfullyColored; }
        
        public Stack<AArch64Operand> getEliminationStack() { return eliminationStack; }
        
        public LinkedHashSet<AArch64Instruction> getSuccessfulMerges() { return successfulMerges; }
        
        public LinkedHashSet<AArch64Instruction> getBlockedMoves() { return blockedMoves; }
        
        public LinkedHashSet<AArch64Instruction> getFrozenMoves() { return frozenMoves; }
        
        public LinkedHashSet<AArch64Instruction> getPendingMoves() { return pendingMoves; }
        
        public boolean allQueuesEmpty() {
            return readyToSimplify.isEmpty() && 
                   pendingMoves.isEmpty() && 
                   moveConstrainedNodes.isEmpty() && 
                   highDegreeQueue.isEmpty();
        }
    }
    
    public void categorizeNodesToWorklists() {
        nodeScheduler.reset();

        for (AArch64VirReg candidate : state.getCandidateRegisters()) {
            int nodeDegree = state.getNodeDegrees().getOrDefault(candidate, 0);
            if (nodeDegree >= state.getAvailableColors()) {
                nodeScheduler.getHighDegreeQueue().add(candidate);
            } else if (isNodeMoveRelated(candidate)) {
                nodeScheduler.getMoveConstrainedNodes().add(candidate);
            } else {
                nodeScheduler.getReadyToSimplify().add(candidate);
            }
        }
        
        for (AArch64Instruction moveInst : state.getActiveMoveInstructions()) {
            nodeScheduler.getPendingMoves().add(moveInst);
        }
    }
    
    public void performGraphSimplificationLoop() {
        while (!nodeScheduler.allQueuesEmpty()) {
            if (!nodeScheduler.getReadyToSimplify().isEmpty()) {
                executeSimplificationStep();
            } else if (!nodeScheduler.getPendingMoves().isEmpty()) {
                executeCoalescingStep();
            } else if (!nodeScheduler.getMoveConstrainedNodes().isEmpty()) {
                executeFreezingStep();
            } else if (!nodeScheduler.getHighDegreeQueue().isEmpty()) {
                executeSpillSelectionStep();
            }
        }
    }
    

    private void executeSimplificationStep() {
        AArch64Operand nodeToRemove = nodeScheduler.getReadyToSimplify().iterator().next();
        nodeScheduler.getReadyToSimplify().remove(nodeToRemove);
        nodeScheduler.getEliminationStack().push(nodeToRemove);
        
        for (AArch64Operand adjacentNode : getActiveAdjacentNodes(nodeToRemove)) {
            decrementNodeDegree(adjacentNode);
        }
    }
    
    private void executeCoalescingStep() {
        AArch64Instruction moveInstruction = nodeScheduler.getPendingMoves().iterator().next();
        nodeScheduler.getPendingMoves().remove(moveInstruction);
        
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
            nodeScheduler.getSuccessfulMerges().add(moveInstruction);
        } else if (secondaryNode instanceof AArch64PhyReg || 
                   hasInterferenceConflict(primaryNode, secondaryNode)) {
            nodeScheduler.getBlockedMoves().add(moveInstruction);
        } else {
            nodeScheduler.getSuccessfulMerges().add(moveInstruction);
        }
    }
    
    private void executeFreezingStep() {
        AArch64Operand nodeToFreeze = nodeScheduler.getMoveConstrainedNodes().iterator().next();
        nodeScheduler.getMoveConstrainedNodes().remove(nodeToFreeze);
        nodeScheduler.getReadyToSimplify().add(nodeToFreeze);
        freezeAssociatedMoves(nodeToFreeze);
    }
    
    private void executeSpillSelectionStep() {
        AArch64Operand spillCandidate = selectOptimalSpillCandidate();
        nodeScheduler.getHighDegreeQueue().remove(spillCandidate);
        nodeScheduler.getReadyToSimplify().add(spillCandidate);
        freezeAssociatedMoves(spillCandidate);
    }
    
    public boolean attemptColorAssignment() {
        Set<Integer> colorOptions = new HashSet<>();
        for (int i = 0; i < state.getAvailableColors(); i++) {
            colorOptions.add(i);
        }
        
        while (!nodeScheduler.getEliminationStack().isEmpty()) {
            AArch64Operand currentNode = nodeScheduler.getEliminationStack().pop();
            
            if (currentNode instanceof AArch64VirReg) {
                Set<Integer> restrictedColors = new HashSet<>();
                
                LinkedHashSet<AArch64Operand> neighbors = state.getAdjacencyGraph().get(currentNode);
                if (neighbors != null) {
                    for (AArch64Operand neighborNode : neighbors) {
                        AArch64Operand resolvedNeighbor = resolveNodeAlias(neighborNode);
                        if (nodeScheduler.getSuccessfullyColored().contains(resolvedNeighbor) || 
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
                    nodeScheduler.getSuccessfullyColored().add((AArch64Reg) currentNode);
                    System.out.println("为寄存器 " + currentNode + " 分配颜色 " + selectedColor);
                }
            }
        }
        
        // 为合并的节点分配颜色
        for (AArch64Operand coalescedNode : nodeScheduler.getMergedNodes()) {
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
    
    private void freezeAssociatedMoves(AArch64Operand node) {
        LinkedHashSet<AArch64Move> associatedMoves = state.getNodeMoveMap().get(node);
        if (associatedMoves != null) {
            for (AArch64Move moveInstruction : associatedMoves) {
                nodeScheduler.getPendingMoves().remove(moveInstruction);
                nodeScheduler.getFrozenMoves().add(moveInstruction);
            }
        }
    }
    
    private AArch64Operand selectOptimalSpillCandidate() {
        AArch64Operand bestCandidate = null;
        int highestDegree = -1;
        
        for (AArch64Operand operand : nodeScheduler.getHighDegreeQueue()) {
            if (state.getNodeDegrees().containsKey(operand) && 
                state.getNodeDegrees().get(operand) > highestDegree) {
                highestDegree = state.getNodeDegrees().get(operand);
                bestCandidate = operand;
            }
        }
        
        return bestCandidate != null ? bestCandidate : nodeScheduler.getHighDegreeQueue().iterator().next();
    }
    
    private boolean isNodeMoveRelated(AArch64Operand node) {
        LinkedHashSet<AArch64Move> moves = state.getNodeMoveMap().get(node);
        return moves != null && !moves.isEmpty();
    }
    
    private LinkedHashSet<AArch64Operand> getActiveAdjacentNodes(AArch64Operand node) {
        LinkedHashSet<AArch64Operand> result = new LinkedHashSet<>();
        LinkedHashSet<AArch64Operand> allNeighbors = state.getAdjacencyGraph().get(node);
        if (allNeighbors != null) {
            for (AArch64Operand neighbor : allNeighbors) {
                // 排除已在消除栈中和已合并的节点
                if (!nodeScheduler.getEliminationStack().contains(neighbor) && 
                    !nodeScheduler.getMergedNodes().contains(neighbor)) {
                    result.add(neighbor);
                }
            }
        }
        return result;
    }
    
    private void decrementNodeDegree(AArch64Operand node) {
        int currentDegree = state.getNodeDegrees().getOrDefault(node, 0);
        state.getNodeDegrees().put(node, currentDegree - 1);
        
        // 如果度数降到K-1，需要重新分类
        if (currentDegree == state.getAvailableColors()) {
            nodeScheduler.getHighDegreeQueue().remove(node);
            if (isNodeMoveRelated(node)) {
                nodeScheduler.getMoveConstrainedNodes().add(node);
            } else {
                nodeScheduler.getReadyToSimplify().add(node);
            }
        }
    }
    
    private AArch64Operand resolveNodeAlias(AArch64Operand node) {
        AArch64Operand alias = state.getNodeAliases().get(node);
        if (alias != null && nodeScheduler.getMergedNodes().contains(node)) {
            return resolveNodeAlias(alias);
        }
        return node;
    }
    
    private boolean hasInterferenceConflict(AArch64Reg node1, AArch64Reg node2) {
        LinkedHashSet<AArch64Operand> neighbors = state.getAdjacencyGraph().get(node1);
        return neighbors != null && neighbors.contains(node2);
    }
}
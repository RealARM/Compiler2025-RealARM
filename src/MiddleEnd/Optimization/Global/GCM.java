package MiddleEnd.Optimization.Global;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.*;

/**
 * 全局代码移动优化（Global Code Motion）
 * 将指令移动到更合适的位置，以减少循环中的指令执行次数，同时保持程序语义不变
 */
public class GCM implements Optimizer.ModuleOptimizer {
    
    private final Set<Instruction> visited = new LinkedHashSet<>();
    
    private boolean debug = false;
    
    private final int MAX_BLOCKS_THRESHOLD = 1000;
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        if (debug) System.out.println("[GCM] Starting optimization on module");
        
        for (Function function : module.functions()) {
            if (function.getBasicBlocks().size() > 1) {
                int blockCount = function.getBasicBlocks().size();
                if (debug) {
                    System.out.println("[GCM] Processing function: " + function.getName() + " with " + blockCount + " blocks");
                }
                
                if (blockCount > MAX_BLOCKS_THRESHOLD) {
                    if (debug) {
                        System.out.println("[GCM] Skipping function: " + function.getName() + 
                                         " - too many blocks (" + blockCount + " > " + MAX_BLOCKS_THRESHOLD + ")");
                    }
                    continue;
                }
                
                runGCMForFunction(function);
                changed = true;
            } else if (debug) {
                System.out.println("[GCM] Skipping function: " + function.getName() + " (single block)");
            }
        }
        
        if (debug) System.out.println("[GCM] Optimization completed, changed: " + changed);
        return changed;
    }
    
    /**
     */
    private void runGCMForFunction(Function function) {
        visited.clear();
        
        long startTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Running loop analysis for: " + function.getName());
        LoopAnalysis.runLoopInfo(function);
        long loopAnalysisTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Loop analysis completed in " + (loopAnalysisTime - startTime) + "ms");
        
        if (debug) System.out.println("[GCM] Computing dominator tree for: " + function.getName());
        
        if (debug) {
            System.out.println("[GCM] Function entry block: " + function.getEntryBlock());
            System.out.println("[GCM] Starting dominator tree computation...");
        }
        
        try {
            DominatorAnalysis.computeDominatorTree(function);
            long domTreeTime = System.currentTimeMillis();
            if (debug) System.out.println("[GCM] Dominator tree computed in " + (domTreeTime - loopAnalysisTime) + "ms");
        } catch (Exception e) {
            System.err.println("[GCM] Error computing dominator tree: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        
        if (debug) {
            int bbWithIdom = 0;
            for (BasicBlock bb : function.getBasicBlocks()) {
                if (bb.getIdominator() != null) {
                    bbWithIdom++;
                }
            }
            System.out.println("[GCM] Dominator tree stats: " + bbWithIdom + "/" + function.getBasicBlocks().size() + " blocks have an immediate dominator");
        }
        
        if (debug) System.out.println("[GCM] Getting post-order traversal...");
        List<BasicBlock> postOrder = DominatorAnalysis.getDomPostOrder(function);
        if (debug) System.out.println("[GCM] Post-order traversal completed with " + postOrder.size() + " blocks");
        
        Collections.reverse(postOrder);
        
        if (debug && postOrder.size() != function.getBasicBlocks().size()) {
            System.out.println("[GCM] Warning: Post-order size (" + postOrder.size() + 
                              ") doesn't match block count (" + function.getBasicBlocks().size() + ")");
        }
        
        List<Instruction> instructions = new ArrayList<>();
        int instCount = 0;
        if (debug) System.out.println("[GCM] Collecting instructions from all blocks...");
        for (BasicBlock bb : postOrder) {
            instructions.addAll(bb.getInstructions());
            instCount += bb.getInstructions().size();
            
            if (debug && instCount % 1000 == 0) {
                System.out.println("[GCM] Collected " + instCount + " instructions so far...");
            }
        }
        
        if (debug) System.out.println("[GCM] Total instructions to process: " + instructions.size());
        
        long earlyStartTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Starting early scheduling phase");
        int earlyMoved = 0;
        int processedCount = 0;
        for (Instruction instruction : instructions) {
            boolean wasMoved = scheduleEarly(instruction, function);
            if (wasMoved) earlyMoved++;
            
            processedCount++;
            if (debug && processedCount % 1000 == 0) {
                System.out.println("[GCM] Early scheduling progress: " + processedCount + "/" + instructions.size() + 
                                  " instructions, " + earlyMoved + " moved");
            }
        }
        long earlyEndTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Early scheduling completed, moved " + earlyMoved + 
                                      " instructions in " + (earlyEndTime - earlyStartTime) + "ms");
        
        visited.clear();
        
        long lateStartTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Starting late scheduling phase");
        Collections.reverse(instructions);
        int lateMoved = 0;
        processedCount = 0;
        for (Instruction instruction : instructions) {
            if (instruction.getParent() != null) {
                boolean wasMoved = scheduleLate(instruction);
                if (wasMoved) lateMoved++;
            } else if (debug) {
                System.out.println("[GCM] Warning: Skipping instruction with null parent: " + instruction);
            }
            
            processedCount++;
            if (debug && processedCount % 1000 == 0) {
                System.out.println("[GCM] Late scheduling progress: " + processedCount + "/" + instructions.size() + 
                                  " instructions, " + lateMoved + " moved");
            }
        }
        long lateEndTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Late scheduling completed, moved " + lateMoved + 
                                     " instructions in " + (lateEndTime - lateStartTime) + "ms");
        
        long totalTime = System.currentTimeMillis() - startTime;
        if (debug) System.out.println("[GCM] Total optimization time for function " + function.getName() + 
                                     ": " + totalTime + "ms");
    }
    
    /**
     * 将指令移动到尽可能早的位置，但确保在其所有操作数之后
     */
    private boolean scheduleEarly(Instruction instruction, Function function) {
        if (visited.contains(instruction) || isPinned(instruction)) {
            return false;
        }
        
        visited.add(instruction);
        
        BasicBlock originalParent = instruction.getParent();
        
        BasicBlock entry = function.getEntryBlock();
        instruction.removeFromParent();
        instruction.insertBefore(entry.getTerminator());
        
        if (debug) System.out.println("[GCM] Early: Initially moved " + instruction + " to entry block");
        
        boolean moved = false;
        
        for (Value operand : instruction.getOperands()) {
            if (operand instanceof Instruction operandInst) {
                scheduleEarly(operandInst, function);
                
                if (operandInst.getParent() != null && instruction.getParent() != null) {
                    if (instruction.getParent().getDomLevel() < operandInst.getParent().getDomLevel()) {
                        BasicBlock oldParent = instruction.getParent();
                        instruction.removeFromParent();
                        instruction.insertBefore(operandInst.getParent().getTerminator());
                        moved = true;
                        if (debug) System.out.println("[GCM] Early: Moved " + instruction + " from " + oldParent + " to " + operandInst.getParent() + " (after operand)");
                    }
                }
            }
        }
        
        return moved || (originalParent != instruction.getParent());
    }
    
    private boolean scheduleLate(Instruction instruction) {
        if (visited.contains(instruction) || isPinned(instruction) || instruction.getParent() == null) {
            return false;
        }
        
        visited.add(instruction);
        BasicBlock originalParent = instruction.getParent();
        
        BasicBlock lca = null;
        
        if (debug) System.out.println("[GCM] Late: Processing " + instruction + " in " + originalParent);
        
        for (User user : instruction.getUsers()) {
            if (user instanceof Instruction userInst) {
                if (userInst.getParent() == null) {
                    if (debug) System.out.println("[GCM] Warning: User instruction has null parent: " + userInst);
                    continue;
                }
                
                scheduleLate(userInst);
                
                BasicBlock useBB;
                if (userInst instanceof PhiInstruction phi) {
                    for (Map.Entry<BasicBlock, Value> entry : phi.getIncomingValues().entrySet()) {
                        if (entry.getValue() == instruction) {
                            useBB = entry.getKey();
                            if (useBB != null) {
                                lca = findLCA(lca, useBB);
                                if (debug) System.out.println("[GCM] Late: Found phi use in " + useBB + " by phi " + phi + ", LCA now " + lca);
                            }
                        }
                    }
                } else {
                    useBB = userInst.getParent();
                    if (useBB != null) {
                        lca = findLCA(lca, useBB);
                        if (debug) System.out.println("[GCM] Late: Found use in " + useBB + " by " + userInst + ", LCA now " + lca);
                    }
                }
            }
        }
        
        boolean moved = false;
        
        if (lca != null) {
            BasicBlock best = lca;
            
            BasicBlock currentLca = lca;
            while (currentLca != instruction.getParent()) {
                currentLca = currentLca.getIdominator();
                if (currentLca == null) {
                    break;
                }
                
                if (currentLca.getLoopDepth() < best.getLoopDepth() || 
                        (currentLca.getSuccessors().size() == 1 && currentLca.getSuccessors().contains(best))) {
                    if (debug) System.out.println("[GCM] Late: Found better block " + currentLca + " with loop depth " + currentLca.getLoopDepth() + " vs " + best.getLoopDepth());
                    best = currentLca;
                }
            }
            
            if (instruction.getParent() != best) {
                if (debug) System.out.println("[GCM] Late: Moving " + instruction + " from " + instruction.getParent() + " to " + best);
                instruction.removeFromParent();
                instruction.insertBefore(best.getTerminator());
                moved = true;
            }
        }
        
        if (instruction.getParent() != null) {
            BasicBlock currentBB = instruction.getParent();
            for (Instruction inst : currentBB.getInstructions()) {
                if (inst != instruction && !(inst instanceof PhiInstruction) && 
                        inst.getOperands().contains(instruction)) {
                    if (debug) System.out.println("[GCM] Late: Fine-tuning - moving " + instruction + " before its first use " + inst);
                    instruction.removeFromParent();
                    instruction.insertBefore(inst);
                    moved = true;
                    break;
                }
            }
        }
        
        return moved || (originalParent != instruction.getParent());
    }

    private BasicBlock findLCA(BasicBlock bb1, BasicBlock bb2) {
        if (bb1 == null) {
            return bb2;
        }
        if (bb2 == null) {
            return bb1;
        }
        
        BasicBlock origBB1 = bb1;
        BasicBlock origBB2 = bb2;
        
        boolean verboseDebug = false;
        
        if (verboseDebug && debug) {
            System.out.println("[GCM] Finding LCA of " + bb1 + "(level:" + bb1.getDomLevel() + ") and " 
                             + bb2 + "(level:" + bb2.getDomLevel() + ")");
        }
        
        int steps = 0;
        int maxSteps = 10000;
        
        while (bb1.getDomLevel() < bb2.getDomLevel()) {
            steps++;
            if (steps > maxSteps) {
                System.err.println("[GCM] Warning: Exceeded " + maxSteps + " steps while equalizing dom levels. Possible infinite loop.");
                System.err.println("[GCM] Original blocks: " + origBB1 + " and " + origBB2);
                System.err.println("[GCM] Current blocks: " + bb1 + "(level:" + bb1.getDomLevel() 
                                 + ") and " + bb2 + "(level:" + bb2.getDomLevel() + ")");
                return bb1;
            }
            
            bb2 = bb2.getIdominator();
            if (bb2 == null) {
                if (debug) System.out.println("[GCM] Warning: bb2 reached null while finding LCA of " + origBB1 + " and " + origBB2);
                return bb1;
            }
            
            if (verboseDebug && debug && steps % 100 == 0) {
                System.out.println("[GCM] LCA step " + steps + ": bb2 now " + bb2 + "(level:" + bb2.getDomLevel() + ")");
            }
        }
        
        steps = 0;
        while (bb2.getDomLevel() < bb1.getDomLevel()) {
            steps++;
            if (steps > maxSteps) {
                System.err.println("[GCM] Warning: Exceeded " + maxSteps + " steps while equalizing dom levels. Possible infinite loop.");
                System.err.println("[GCM] Original blocks: " + origBB1 + " and " + origBB2);
                System.err.println("[GCM] Current blocks: " + bb1 + "(level:" + bb1.getDomLevel() 
                                 + ") and " + bb2 + "(level:" + bb2.getDomLevel() + ")");
                return bb2;
            }
            
            bb1 = bb1.getIdominator();
            if (bb1 == null) {
                if (debug) System.out.println("[GCM] Warning: bb1 reached null while finding LCA of " + origBB1 + " and " + origBB2);
                return bb2;
            }
            
            if (verboseDebug && debug && steps % 100 == 0) {
                System.out.println("[GCM] LCA step " + steps + ": bb1 now " + bb1 + "(level:" + bb1.getDomLevel() + ")");
            }
        }
        
        steps = 0;
        while (bb1 != bb2) {
            steps++;
            if (steps > maxSteps) {
                System.err.println("[GCM] Warning: Exceeded " + maxSteps + " steps while finding common ancestor. Possible infinite loop.");
                System.err.println("[GCM] Original blocks: " + origBB1 + " and " + origBB2);
                System.err.println("[GCM] Current blocks: " + bb1 + " and " + bb2);
                return bb1;
            }
            
            bb1 = bb1.getIdominator();
            bb2 = bb2.getIdominator();
            
            if (bb1 == null || bb2 == null) {
                if (debug) System.out.println("[GCM] Error: Failed to find LCA for " + origBB1 + " and " + origBB2);
                return (bb1 != null) ? bb1 : bb2;
            }
            
            if (verboseDebug && debug && steps % 100 == 0) {
                System.out.println("[GCM] LCA step " + steps + ": bb1=" + bb1 + ", bb2=" + bb2);
            }
        }
        
        return bb1;
    }
    
    private boolean isPinned(Instruction instruction) {
        boolean pinned = instruction instanceof BranchInstruction ||
               instruction instanceof PhiInstruction ||
               instruction instanceof ReturnInstruction ||
               instruction instanceof StoreInstruction ||
               instruction instanceof LoadInstruction ||
               instruction instanceof CallInstruction;
                
        if (debug && pinned) {
            System.out.println("[GCM] Instruction " + instruction + " is pinned (cannot be moved)");
        }
        
        return pinned;
    }
    
    @Override
    public String getName() {
        return "GCM";
    }
} 
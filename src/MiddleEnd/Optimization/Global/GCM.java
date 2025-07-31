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
    
    // 已访问的指令集合
    private final Set<Instruction> visited = new LinkedHashSet<>();
    
    // 调试模式
    private boolean debug = false;
    
    // 基本块数量阈值，超过此阈值的函数将跳过GCM优化
    private final int MAX_BLOCKS_THRESHOLD = 1000;
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        if (debug) System.out.println("[GCM] Starting optimization on module");
        
        // 对模块中每个函数应用GCM优化
        for (Function function : module.functions()) {
            // 只处理有多个基本块的函数
            if (function.getBasicBlocks().size() > 1) {
                int blockCount = function.getBasicBlocks().size();
                if (debug) {
                    System.out.println("[GCM] Processing function: " + function.getName() + " with " + blockCount + " blocks");
                }
                
                // 检查基本块数量是否超过阈值
                if (blockCount > MAX_BLOCKS_THRESHOLD) {
                    if (debug) {
                        System.out.println("[GCM] Skipping function: " + function.getName() + 
                                         " - too many blocks (" + blockCount + " > " + MAX_BLOCKS_THRESHOLD + ")");
                    }
                    continue; // 跳过此函数
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
     * 对单个函数应用GCM优化
     */
    private void runGCMForFunction(Function function) {
        // 清空已访问指令集
        visited.clear();
        
        long startTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Running loop analysis for: " + function.getName());
        // 运行循环信息分析
        LoopAnalysis.runLoopInfo(function);
        long loopAnalysisTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Loop analysis completed in " + (loopAnalysisTime - startTime) + "ms");
        
        if (debug) System.out.println("[GCM] Computing dominator tree for: " + function.getName());
        
        // 添加更详细的调试输出来跟踪支配树计算进度
        if (debug) {
            System.out.println("[GCM] Function entry block: " + function.getEntryBlock());
            System.out.println("[GCM] Starting dominator tree computation...");
        }
        
        try {
            // 计算支配树关系，初始化支配级别和直接支配者
            DominatorAnalysis.computeDominatorTree(function);
            long domTreeTime = System.currentTimeMillis();
            if (debug) System.out.println("[GCM] Dominator tree computed in " + (domTreeTime - loopAnalysisTime) + "ms");
        } catch (Exception e) {
            System.err.println("[GCM] Error computing dominator tree: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        
        // 添加检查以验证支配树是否正确构建
        if (debug) {
            int bbWithIdom = 0;
            for (BasicBlock bb : function.getBasicBlocks()) {
                if (bb.getIdominator() != null) {
                    bbWithIdom++;
                }
            }
            System.out.println("[GCM] Dominator tree stats: " + bbWithIdom + "/" + function.getBasicBlocks().size() + " blocks have an immediate dominator");
        }
        
        // 获取后序遍历的基本块列表并反转（成为前序遍历）
        if (debug) System.out.println("[GCM] Getting post-order traversal...");
        List<BasicBlock> postOrder = DominatorAnalysis.getDomPostOrder(function);
        if (debug) System.out.println("[GCM] Post-order traversal completed with " + postOrder.size() + " blocks");
        
        Collections.reverse(postOrder);
        
        if (debug && postOrder.size() != function.getBasicBlocks().size()) {
            System.out.println("[GCM] Warning: Post-order size (" + postOrder.size() + 
                              ") doesn't match block count (" + function.getBasicBlocks().size() + ")");
        }
        
        // 收集所有指令
        List<Instruction> instructions = new ArrayList<>();
        int instCount = 0;
        if (debug) System.out.println("[GCM] Collecting instructions from all blocks...");
        for (BasicBlock bb : postOrder) {
            instructions.addAll(bb.getInstructions());
            instCount += bb.getInstructions().size();
            
            // 每处理1000个基本块打印一次进度
            if (debug && instCount % 1000 == 0) {
                System.out.println("[GCM] Collected " + instCount + " instructions so far...");
            }
        }
        
        if (debug) System.out.println("[GCM] Total instructions to process: " + instructions.size());
        
        // 第一阶段：尽早调度指令
        long earlyStartTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Starting early scheduling phase");
        int earlyMoved = 0;
        int processedCount = 0;
        for (Instruction instruction : instructions) {
            boolean wasMoved = scheduleEarly(instruction, function);
            if (wasMoved) earlyMoved++;
            
            // 每处理1000条指令打印一次进度
            processedCount++;
            if (debug && processedCount % 1000 == 0) {
                System.out.println("[GCM] Early scheduling progress: " + processedCount + "/" + instructions.size() + 
                                  " instructions, " + earlyMoved + " moved");
            }
        }
        long earlyEndTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Early scheduling completed, moved " + earlyMoved + 
                                      " instructions in " + (earlyEndTime - earlyStartTime) + "ms");
        
        // 重置已访问指令集
        visited.clear();
        
        // 第二阶段：尽晚调度指令（反转指令列表）
        long lateStartTime = System.currentTimeMillis();
        if (debug) System.out.println("[GCM] Starting late scheduling phase");
        Collections.reverse(instructions);
        int lateMoved = 0;
        processedCount = 0;
        for (Instruction instruction : instructions) {
            // 确保指令有父块
            if (instruction.getParent() != null) {
                boolean wasMoved = scheduleLate(instruction);
                if (wasMoved) lateMoved++;
            } else if (debug) {
                System.out.println("[GCM] Warning: Skipping instruction with null parent: " + instruction);
            }
            
            // 每处理1000条指令打印一次进度
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
     * 第一阶段：尽早调度指令
     * 将指令移动到尽可能早的位置，但确保在其所有操作数之后
     * @return 是否移动了指令
     */
    private boolean scheduleEarly(Instruction instruction, Function function) {
        // 如果指令已被访问或是固定指令，则跳过
        if (visited.contains(instruction) || isPinned(instruction)) {
            return false;
        }
        
        visited.add(instruction);
        
        BasicBlock originalParent = instruction.getParent();
        
        // 初始将指令移到入口块的最后一条指令之前
        BasicBlock entry = function.getEntryBlock();
        instruction.removeFromParent();
        instruction.insertBefore(entry.getTerminator());
        
        if (debug) System.out.println("[GCM] Early: Initially moved " + instruction + " to entry block");
        
        boolean moved = false;
        
        // 确保指令在其所有操作数之后
        for (Value operand : instruction.getOperands()) {
            if (operand instanceof Instruction operandInst) {
                // 递归处理操作数指令
                scheduleEarly(operandInst, function);
                
                // 确保操作数指令有父块
                if (operandInst.getParent() != null && instruction.getParent() != null) {
                    // 如果当前指令的基本块支配级别低于操作数指令的基本块，则移动当前指令
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
    
    /**
     * 第二阶段：尽晚调度指令
     * 将指令移动到尽可能晚的位置，但确保仍然支配其所有使用者
     * @return 是否移动了指令
     */
    private boolean scheduleLate(Instruction instruction) {
        // 如果指令已被访问或是固定指令，则跳过
        if (visited.contains(instruction) || isPinned(instruction) || instruction.getParent() == null) {
            return false;
        }
        
        visited.add(instruction);
        BasicBlock originalParent = instruction.getParent();
        
        // 找到所有使用该指令的指令的LCA（最低共同祖先）
        BasicBlock lca = null;
        
        if (debug) System.out.println("[GCM] Late: Processing " + instruction + " in " + originalParent);
        
        for (User user : instruction.getUsers()) {
            if (user instanceof Instruction userInst) {
                // 跳过没有父块的使用者
                if (userInst.getParent() == null) {
                    if (debug) System.out.println("[GCM] Warning: User instruction has null parent: " + userInst);
                    continue;
                }
                
                // 递归处理使用者指令
                scheduleLate(userInst);
                
                BasicBlock useBB;
                // 特殊处理Phi指令
                if (userInst instanceof PhiInstruction phi) {
                    // 找到Phi指令中使用当前指令的前驱块
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
                    // 非Phi指令，直接使用其所在基本块
                    useBB = userInst.getParent();
                    if (useBB != null) {
                        lca = findLCA(lca, useBB);
                        if (debug) System.out.println("[GCM] Late: Found use in " + useBB + " by " + userInst + ", LCA now " + lca);
                    }
                }
            }
        }
        
        boolean moved = false;
        
        // 如果指令有使用者，找到最佳放置位置
        if (lca != null) {
            BasicBlock best = lca;
            
            // 从LCA向上遍历支配树，寻找循环嵌套最浅的块
            BasicBlock currentLca = lca;
            while (currentLca != instruction.getParent()) {
                currentLca = currentLca.getIdominator();
                if (currentLca == null) {
                    break;
                }
                
                // 如果找到循环深度更小的块，或者是直接跳转到best的块，更新best
                if (currentLca.getLoopDepth() < best.getLoopDepth() || 
                        (currentLca.getSuccessors().size() == 1 && currentLca.getSuccessors().contains(best))) {
                    if (debug) System.out.println("[GCM] Late: Found better block " + currentLca + " with loop depth " + currentLca.getLoopDepth() + " vs " + best.getLoopDepth());
                    best = currentLca;
                }
            }
            
            // 移动指令到最佳位置
            if (instruction.getParent() != best) {
                if (debug) System.out.println("[GCM] Late: Moving " + instruction + " from " + instruction.getParent() + " to " + best);
                instruction.removeFromParent();
                instruction.insertBefore(best.getTerminator());
                moved = true;
            }
        }
        
        // 最后一次调整：如果在当前块中有使用者，移动到第一个使用者之前
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
    
    /**
     * 寻找两个基本块的最低共同祖先（在支配树中）
     */
    private BasicBlock findLCA(BasicBlock bb1, BasicBlock bb2) {
        // 如果其中一个为空，返回另一个
        if (bb1 == null) {
            return bb2;
        }
        if (bb2 == null) {
            return bb1;
        }
        
        // 保存原始块以便调试
        BasicBlock origBB1 = bb1;
        BasicBlock origBB2 = bb2;
        
        // 添加LCA计算的详细日志
        boolean verboseDebug = false; // 只在需要非常详细调试时启用
        
        if (verboseDebug && debug) {
            System.out.println("[GCM] Finding LCA of " + bb1 + "(level:" + bb1.getDomLevel() + ") and " 
                             + bb2 + "(level:" + bb2.getDomLevel() + ")");
        }
        
        // 使支配级别相同
        int steps = 0;
        int maxSteps = 10000; // 安全措施，防止无限循环
        
        while (bb1.getDomLevel() < bb2.getDomLevel()) {
            steps++;
            if (steps > maxSteps) {
                System.err.println("[GCM] Warning: Exceeded " + maxSteps + " steps while equalizing dom levels. Possible infinite loop.");
                System.err.println("[GCM] Original blocks: " + origBB1 + " and " + origBB2);
                System.err.println("[GCM] Current blocks: " + bb1 + "(level:" + bb1.getDomLevel() 
                                 + ") and " + bb2 + "(level:" + bb2.getDomLevel() + ")");
                return bb1; // 中断执行并返回当前结果
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
                return bb2; // 中断执行并返回当前结果
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
        
        // 同时向上遍历直到找到共同祖先
        steps = 0;
        while (bb1 != bb2) {
            steps++;
            if (steps > maxSteps) {
                System.err.println("[GCM] Warning: Exceeded " + maxSteps + " steps while finding common ancestor. Possible infinite loop.");
                System.err.println("[GCM] Original blocks: " + origBB1 + " and " + origBB2);
                System.err.println("[GCM] Current blocks: " + bb1 + " and " + bb2);
                return bb1; // 中断执行并返回当前结果
            }
            
            bb1 = bb1.getIdominator();
            bb2 = bb2.getIdominator();
            
            // 安全检查
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
    
    /**
     * 判断指令是否为固定指令（不能移动的指令）
     */
    private boolean isPinned(Instruction instruction) {
        // 以下类型的指令不能移动
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
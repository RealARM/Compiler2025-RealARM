package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.*;

/**
 * 循环不变代码移动优化（Loop Invariant Code Motion）
 * 将循环内的不变计算移动到循环外，减少重复计算
 */
public class LoopInvariantCodeMotion implements Optimizer.ModuleOptimizer {

    private final boolean debug = false;
    
    private final int MAX_BLOCKS_THRESHOLD = 1000;
    
    private int totalMovedInstructions = 0;
    
    @Override
    public String getName() {
        return "LoopInvariantCodeMotion";
    }

    @Override
    public boolean run(Module module) {
        boolean modified = false;
        totalMovedInstructions = 0;
        
        if (debug) {
            System.out.println("[LICM] Starting Loop Invariant Code Motion optimization");
        }
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            if (function.getBasicBlocks().size() > MAX_BLOCKS_THRESHOLD) {
                if (debug) {
                    System.out.println("[LICM] Skipping function " + function.getName() + 
                                     " - too many blocks (" + function.getBasicBlocks().size() + ")");
                }
                continue;
            }
            
            if (debug) {
                System.out.println("[LICM] Processing function: " + function.getName());
            }
            
            List<Loop> allLoops = LoopAnalysis.getAllLoopsInDFSOrder(function);
            
            if (debug) {
                System.out.println("[LICM] Found " + allLoops.size() + " loops");
            }
            
            for (Loop loop : allLoops) {
                boolean loopModified = processLoop(loop);
                modified |= loopModified;
            }
            
            validateFunction(function);
        }
        
        if (debug) {
            System.out.println("[LICM] Optimization completed. Total moved instructions: " + totalMovedInstructions);
        }
        
        return modified;
    }
    
    private boolean processLoop(Loop loop) {
        if (debug) {
            System.out.println("[LICM] Processing loop with header: " + loop.getHeader());
        }
        
        BasicBlock preheader = getOrCreatePreheader(loop);
        if (preheader == null) {
            if (debug) {
                System.out.println("[LICM] Cannot create preheader for loop");
            }
            return false;
        }
        
        Set<Instruction> invariants = identifyLoopInvariants(loop);
        
        if (debug) {
            System.out.println("[LICM] Found " + invariants.size() + " loop invariant instructions");
        }
        
        boolean modified = false;
        for (Instruction inst : invariants) {
            if (canSafelyMove(inst, loop)) {
                moveToPreheader(inst, preheader);
                totalMovedInstructions++;
                modified = true;
                
                if (debug) {
                    System.out.println("[LICM] Moved instruction: " + inst);
                }
            } else if (debug) {
                System.out.println("[LICM] Cannot safely move instruction: " + inst);
            }
        }
        
        return modified;
    }

    private Set<Instruction> identifyLoopInvariants(Loop loop) {
        Set<Instruction> invariants = new HashSet<>();
        Set<Instruction> processed = new HashSet<>();
        boolean changed = true;
        
        while (changed) {
            changed = false;
            
            for (BasicBlock block : loop.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (!processed.contains(inst) && isLoopInvariant(inst, loop, invariants)) {
                        invariants.add(inst);
                        processed.add(inst);
                        changed = true;
                    }
                }
            }
        }
        
        return invariants;
    }
    
    private boolean isLoopInvariant(Instruction inst, Loop loop, Set<Instruction> knownInvariants) {
        if (!canBeInvariant(inst)) {
            return false;
        }
        
        if (!loop.contains(inst)) {
            return false;
        }
        
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            
            if (operand instanceof Constant) {
                continue;
            }
            
            if (operand instanceof Instruction operandInst) {
                if (!loop.contains(operandInst)) {
                    continue;
                }
                
                if (knownInvariants.contains(operandInst)) {
                    continue;
                }
                
                return false;
            }
        }
        
        return true;
    }
    
    private boolean canBeInvariant(Instruction inst) {
        if (inst instanceof BranchInstruction ||
            inst instanceof PhiInstruction ||
            inst instanceof ReturnInstruction ||
            inst instanceof StoreInstruction ||
            inst instanceof LoadInstruction ||
            inst instanceof CallInstruction ||
            inst instanceof AllocaInstruction) {
            return false;
        }

        return true;
    }
    
    private boolean canSafelyMove(Instruction inst, Loop loop) {
        for (User user : inst.getUsers()) {
            if (user instanceof Instruction userInst) {
                if (loop.contains(userInst)) {
                    return false;
                }
            }
        }
        
        if (inst.getParent() != loop.getHeader()) {
            return false;
        }
        
        return true;
    }
    
    private BasicBlock getOrCreatePreheader(Loop loop) {
        BasicBlock preheader = loop.getPreheader();
        
        if (preheader != null) {
            return preheader;
        }
        
        BasicBlock header = loop.getHeader();
        Function function = header.getParentFunction();
        
        List<BasicBlock> nonLatchPreds = new ArrayList<>();
        for (BasicBlock pred : header.getPredecessors()) {
            if (!loop.getLatchBlocks().contains(pred)) {
                nonLatchPreds.add(pred);
            }
        }
        
        if (nonLatchPreds.isEmpty()) {
            return null;
        }
        
        if (nonLatchPreds.size() == 1) {
            BasicBlock singlePred = nonLatchPreds.get(0);
            if (singlePred.getSuccessors().size() == 1 && singlePred.getSuccessors().contains(header)) {
                return singlePred;
            }
        }
        
        if (nonLatchPreds.size() > 1) {
            if (debug) {
                System.out.println("[LICM] Multiple non-latch predecessors, skipping preheader creation for: " + header.getName());
            }
            return null;
        }
        
        return null;
    }
    

    
    private void moveToPreheader(Instruction inst, BasicBlock preheader) {
        inst.removeFromParent();
        
        inst.insertBefore(preheader.getTerminator());
    }
    
    private void validateFunction(Function function) {
        for (BasicBlock block : function.getBasicBlocks()) {
            if (block.getInstructions().isEmpty()) {
                if (debug) {
                    System.out.println("[LICM] Warning: Empty basic block found: " + block.getName());
                }
                continue;
            }
            
            Instruction lastInst = block.getLastInstruction();
            if (lastInst == null) {
                if (debug) {
                    System.out.println("[LICM] Warning: Basic block without last instruction: " + block.getName());
                }
            } else if (!(lastInst instanceof TerminatorInstruction)) {
                if (debug) {
                    System.out.println("[LICM] Warning: Basic block without terminator: " + block.getName());
                    System.out.println("[LICM] Last instruction: " + lastInst);
                }
            }
        }
    }
} 
package MiddleEnd.Optimization.Global;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;

import java.util.*;

/**
 * 全局值编号(Global Value Numbering)优化
 * 识别并消除冗余计算
 */
public class GVN implements Optimizer.ModuleOptimizer {

    private final Map<String, Value> gvnMap = new HashMap<>();
    private final Set<Instruction> visited = new HashSet<>();
    
    private BasicBlock currentBlock;
    
    private Map<BasicBlock, Set<BasicBlock>> dominatorMap;
    private Map<BasicBlock, BasicBlock> immediateDominators;
    private List<BasicBlock> domOrderBlocks;
    
    private final boolean debug = false;
    
    private final int MAX_BLOCKS_THRESHOLD = 1000;

    @Override
    public String getName() {
        return "GlobalValueNumbering";
    }

    @Override
    public boolean run(Module module) {
        boolean modified = false;
        
        if (debug) System.out.println("[GVN] Starting optimization on module");
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            int blockCount = function.getBasicBlocks().size();
            if (debug) {
                System.out.println("[GVN] Processing function: " + function.getName() + " with " + blockCount + " blocks");
            }
            
            if (blockCount > MAX_BLOCKS_THRESHOLD) {
                if (debug) {
                    System.out.println("[GVN] Skipping function: " + function.getName() + 
                                     " - too many blocks (" + blockCount + " > " + MAX_BLOCKS_THRESHOLD + ")");
                }
                continue;
            }
            
            gvnMap.clear();
            visited.clear();
            
            try {
                if (debug) System.out.println("[GVN] Computing dominators for function: " + function.getName());
                computeDominators(function);
                
                boolean changed = runGVNOnFunction(function);
                modified |= changed;
                
                if (debug) System.out.println("[GVN] Completed processing " + function.getName() + ", changed: " + changed);
            } catch (Exception e) {
                System.err.println("[GVN] Error processing function " + function.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        if (debug) System.out.println("[GVN] Optimization completed, modified: " + modified);
        return modified;
    }

    private boolean runGVNOnFunction(Function function) {
        boolean modified = false;
        
        // 按照支配树的顺序处理基本块
        List<BasicBlock> blocks = domOrderBlocks;
        
        for (BasicBlock block : blocks) {
            currentBlock = block;
            
            // 处理块内的每条指令
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            for (Instruction inst : instructions) {
                if (!visited.contains(inst) && canApplyGVN(inst)) {
                    modified |= processInstruction(inst);
                }
            }
        }
        
        return modified;
    }

    private void computeDominators(Function function) {
        dominatorMap = DominatorAnalysis.computeDominators(function);
        immediateDominators = DominatorAnalysis.computeImmediateDominators(dominatorMap);
        domOrderBlocks = DominatorAnalysis.getBlocksInDominatorOrder(function, immediateDominators);
    }

    private boolean processInstruction(Instruction inst) {
        if (visited.contains(inst)) {
            return false;
        }
        
        visited.add(inst);
        
        String hash = computeHash(inst);
        if (hash == null) {
            return false;
        }
        
        if (gvnMap.containsKey(hash)) {
            Value existingValue = gvnMap.get(hash);
            
            if (existingValue instanceof Instruction existingInst) {
                BasicBlock existingBlock = existingInst.getParent();
                
                if (!dominates(existingBlock, currentBlock)) {
                    gvnMap.put(hash, inst);
                    return false;
                }
            }
            
            for (User user : new ArrayList<>(inst.getUsers())) {
                for (int i = 0; i < user.getOperandCount(); i++) {
                    if (user.getOperand(i) == inst) {
                        user.setOperand(i, existingValue);
                    }
                }
            }
            
            inst.removeFromParent();
            
            return true;
        } else {
            gvnMap.put(hash, inst);
            return false;
        }
    }

    private String computeHash(Instruction inst) {
        if (inst instanceof BinaryInstruction binary) {
            OpCode opcode = binary.getOpCode();
            Value left = binary.getOperand(0);
            Value right = binary.getOperand(1);
            
            if (isCommutative(opcode) && left.toString().compareTo(right.toString()) > 0) {
                Value temp = left;
                left = right;
                right = temp;
            }
            
            return opcode.toString() + "_" + left + "_" + right;
        }
        else if (inst instanceof CompareInstruction cmp) {
            OpCode compareType = cmp.getCompareType();
            OpCode predicate = cmp.getPredicate();
            Value left = cmp.getOperand(0);
            Value right = cmp.getOperand(1);
            
            return compareType.toString() + "_" + predicate.toString() + "_" + left + "_" + right;
        }
        
        return null;
    }

    private boolean isCommutative(OpCode opcode) {
        return opcode == OpCode.ADD || opcode == OpCode.MUL || 
               opcode == OpCode.AND || opcode == OpCode.OR || opcode == OpCode.XOR;
    }

    private boolean dominates(BasicBlock block1, BasicBlock block2) {
        if (block1 == block2) {
            return true;
        }
        
        Set<BasicBlock> dominated = dominatorMap.get(block1);
        return dominated != null && dominated.contains(block2);
    }

    private boolean hasSideEffects(Instruction inst) {
        if (inst.getOpcodeName().equals("store")) {
            return true;
        }
        
        if (inst.getOpcodeName().equals("call")) {
            return true;
        }
        
        return false;
    }

    private boolean canApplyGVN(Instruction inst) {
        if (hasSideEffects(inst)) {
            return false;
        }
        
        if (inst.isTerminator()) {
            return false;
        }
        
        if (inst instanceof PhiInstruction) {
            return false;
        }
        
        return inst instanceof BinaryInstruction || 
               inst instanceof CompareInstruction;
    }
} 
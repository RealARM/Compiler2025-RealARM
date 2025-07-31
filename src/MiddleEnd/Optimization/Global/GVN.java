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

    // 哈希值到指令的映射
    private final Map<String, Value> gvnMap = new HashMap<>();
    
    // 已访问的指令集合
    private final Set<Instruction> visited = new HashSet<>();
    
    // 当前处理的基本块
    private BasicBlock currentBlock;
    
    // 支配关系记录
    private Map<BasicBlock, Set<BasicBlock>> dominatorMap;
    private Map<BasicBlock, BasicBlock> immediateDominators;
    private List<BasicBlock> domOrderBlocks;
    
    // 调试模式
    private final boolean debug = false;
    
    // 基本块数量阈值，超过此阈值的函数将跳过GVN优化
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
            // 跳过外部函数
            if (function.isExternal()) {
                continue;
            }
            
            int blockCount = function.getBasicBlocks().size();
            if (debug) {
                System.out.println("[GVN] Processing function: " + function.getName() + " with " + blockCount + " blocks");
            }
            
            // 检查基本块数量是否超过阈值
            if (blockCount > MAX_BLOCKS_THRESHOLD) {
                if (debug) {
                    System.out.println("[GVN] Skipping function: " + function.getName() + 
                                     " - too many blocks (" + blockCount + " > " + MAX_BLOCKS_THRESHOLD + ")");
                }
                continue; // 跳过此函数
            }
            
            // 初始化数据结构
            gvnMap.clear();
            visited.clear();
            
            // 计算支配关系
            try {
                if (debug) System.out.println("[GVN] Computing dominators for function: " + function.getName());
                computeDominators(function);
                
                // 执行GVN
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

    /**
     * 在函数上执行GVN优化
     */
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

    /**
     * 计算函数中的支配关系
     */
    private void computeDominators(Function function) {
        // 使用DominatorAnalysis工具计算支配关系
        dominatorMap = DominatorAnalysis.computeDominators(function);
        immediateDominators = DominatorAnalysis.computeImmediateDominators(dominatorMap);
        domOrderBlocks = DominatorAnalysis.getBlocksInDominatorOrder(function, immediateDominators);
    }

    /**
     * 处理单条指令
     */
    private boolean processInstruction(Instruction inst) {
        if (visited.contains(inst)) {
            return false;
        }
        
        visited.add(inst);
        
        // 计算指令哈希值
        String hash = computeHash(inst);
        if (hash == null) {
            return false;
        }
        
        // 检查是否有等价指令
        if (gvnMap.containsKey(hash)) {
            Value existingValue = gvnMap.get(hash);
            
            // 检查支配关系：现有值必须支配当前指令
            if (existingValue instanceof Instruction existingInst) {
                BasicBlock existingBlock = existingInst.getParent();
                
                // 如果现有指令不支配当前指令，则不能共享
                if (!dominates(existingBlock, currentBlock)) {
                    // 将当前指令添加到GVN映射
                    gvnMap.put(hash, inst);
                    return false;
                }
            }
            
            // 替换当前指令的所有使用为已有值
            // 遍历所有使用当前指令的用户，将它们改为使用已有值
            for (User user : new ArrayList<>(inst.getUsers())) {
                for (int i = 0; i < user.getOperandCount(); i++) {
                    if (user.getOperand(i) == inst) {
                        user.setOperand(i, existingValue);
                    }
                }
            }
            
            // 从父块中移除当前指令
            inst.removeFromParent();
            
            return true;
        } else {
            // 将当前指令添加到GVN映射
            gvnMap.put(hash, inst);
            return false;
        }
    }

    /**
     * 计算指令的哈希值
     */
    private String computeHash(Instruction inst) {
        // 二元运算指令
        if (inst instanceof BinaryInstruction binary) {
            OpCode opcode = binary.getOpCode();
            Value left = binary.getOperand(0);
            Value right = binary.getOperand(1);
            
            // 如果是可交换的操作且左右操作数是常量，确保它们的顺序一致
            if (isCommutative(opcode) && left.toString().compareTo(right.toString()) > 0) {
                Value temp = left;
                left = right;
                right = temp;
            }
            
            return opcode.toString() + "_" + left + "_" + right;
        }
        // 比较指令
        else if (inst instanceof CompareInstruction cmp) {
            OpCode compareType = cmp.getCompareType();
            OpCode predicate = cmp.getPredicate();
            Value left = cmp.getOperand(0);
            Value right = cmp.getOperand(1);
            
            return compareType.toString() + "_" + predicate.toString() + "_" + left + "_" + right;
        }
        
        // 其他类型指令暂不处理
        return null;
    }

    /**
     * 判断操作是否可交换
     */
    private boolean isCommutative(OpCode opcode) {
        return opcode == OpCode.ADD || opcode == OpCode.MUL || 
               opcode == OpCode.AND || opcode == OpCode.OR || opcode == OpCode.XOR;
    }

    /**
     * 检查block1是否支配block2
     */
    private boolean dominates(BasicBlock block1, BasicBlock block2) {
        if (block1 == block2) {
            return true;
        }
        
        Set<BasicBlock> dominated = dominatorMap.get(block1);
        return dominated != null && dominated.contains(block2);
    }

    /**
     * 判断指令是否有副作用
     */
    private boolean hasSideEffects(Instruction inst) {
        // 内存写入指令有副作用
        if (inst.getOpcodeName().equals("store")) {
            return true;
        }
        
        // 函数调用可能有副作用
        if (inst.getOpcodeName().equals("call")) {
            return true;
        }
        
        return false;
    }

    /**
     * 判断指令是否可以应用GVN
     */
    private boolean canApplyGVN(Instruction inst) {
        // 跳过有副作用的指令
        if (hasSideEffects(inst)) {
            return false;
        }
        
        // 跳过终结指令
        if (inst.isTerminator()) {
            return false;
        }
        
        // 跳过Phi指令
        if (inst instanceof PhiInstruction) {
            return false;
        }
        
        // 目前只处理二元运算和比较指令
        return inst instanceof BinaryInstruction || 
               inst instanceof CompareInstruction;
    }
} 
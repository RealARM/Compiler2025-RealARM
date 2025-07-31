package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Value.*;

import java.util.*;

/**
 * 支配关系分析工具
 * 用于计算支配关系、支配树和支配边界
 */
public class DominatorAnalysis {
    
    /**
     * 计算函数的支配关系
     * @param function 待分析的函数
     * @return 支配关系映射（键：基本块，值：被该基本块支配的所有基本块集合）
     */
    public static Map<BasicBlock, Set<BasicBlock>> computeDominators(Function function) {
        // 支配关系映射：block -> 所有被block支配的块
        Map<BasicBlock, Set<BasicBlock>> dominatorMap = new HashMap<>();
        
        List<BasicBlock> blocks = function.getBasicBlocks();
        if (blocks.isEmpty()) {
            return dominatorMap;
        }
        
        // 初始化支配关系
        for (BasicBlock block : blocks) {
            dominatorMap.put(block, new HashSet<>());
        }
        
        BasicBlock entry = function.getEntryBlock();
        if (entry == null) {
            return dominatorMap; // 没有入口块，返回空结果
        }
        
        // 对于入口块，它只支配自己
        dominatorMap.get(entry).add(entry);
        
        // 对于其他所有块，初始支配集合为所有块（后续会迭代缩小）
        for (BasicBlock block : blocks) {
            if (block != entry) {
                Set<BasicBlock> dominatedBlocks = dominatorMap.get(block);
                dominatedBlocks.addAll(blocks); // 初始化为所有块
            }
        }
        
        // 迭代计算支配关系，直到不再变化
        boolean changed;
        int iterationCount = 0;
        do {
            changed = false;
            iterationCount++;
            
            if (iterationCount > 1000) {
                System.out.println("[DominatorAnalysis] WARNING: Excessive iterations in computeDominators: " + iterationCount);
                break;
            }
            
            // 对除入口块外的每个块进行更新
            for (BasicBlock block : blocks) {
                if (block == entry) {
                    continue;
                }
                
                // 创建临时集合保存所有块
                Set<BasicBlock> newDomSet = new HashSet<>(blocks);
                
                // 处理该块的所有前驱
                List<BasicBlock> predecessors = block.getPredecessors();
                if (predecessors.isEmpty()) {
                    continue;
                }
                
                for (BasicBlock pred : predecessors) {
                    Set<BasicBlock> predDomSet = dominatorMap.get(pred);
                    if (!predDomSet.isEmpty()) {  // 只取已有支配关系的前驱
                        newDomSet.retainAll(predDomSet);  // 交集操作
                    }
                }
                
                // 每个块至少支配自己
                newDomSet.add(block);
                
                // 检查是否有变化
                Set<BasicBlock> oldDomSet = dominatorMap.get(block);
                if (!newDomSet.equals(oldDomSet)) {
                    dominatorMap.put(block, newDomSet);
                    changed = true;
                }
            }
            
        } while (changed);
        
        return dominatorMap;
    }
    
    /**
     * 计算直接支配关系
     * @param dominators 支配关系映射
     * @return 直接支配关系映射（键：基本块，值：直接支配该基本块的基本块）
     */
    public static Map<BasicBlock, BasicBlock> computeImmediateDominators(
            Map<BasicBlock, Set<BasicBlock>> dominators) {
        try {
            Map<BasicBlock, BasicBlock> idoms = new HashMap<>();
            
            for (BasicBlock block : dominators.keySet()) {
                // 获取支配block的所有块（不包括block自身）
                Set<BasicBlock> domSet = new HashSet<>(dominators.get(block));
                domSet.remove(block); // 移除自身
                
                // 如果没有支配者（只有入口块会出现这种情况），跳过
                if (domSet.isEmpty()) {
                    continue;
                }
                
                // 找出直接支配者
                BasicBlock idom = null;
                for (BasicBlock dominator : domSet) {
                    boolean isIdom = true;
                    
                    // 检查是否有其他块位于当前块和候选直接支配者之间
                    for (BasicBlock other : domSet) {
                        if (other != dominator && dominators.get(other).contains(dominator)) {
                            isIdom = false;
                            break;
                        }
                    }
                    
                    if (isIdom) {
                        idom = dominator;
                        break;
                    }
                }
                
                if (idom != null) {
                    idoms.put(block, idom);
                }
            }
            
            return idoms;
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }
    
    /**
     * 计算支配边界
     * @param function 待分析的函数
     * @param dominators 支配关系映射
     * @param idoms 直接支配关系映射
     * @return 支配边界映射（键：基本块，值：该基本块的支配边界集合）
     */
    public static Map<BasicBlock, Set<BasicBlock>> computeDominanceFrontiers(
            Function function, Map<BasicBlock, Set<BasicBlock>> dominators, 
            Map<BasicBlock, BasicBlock> idoms) {
        Map<BasicBlock, Set<BasicBlock>> frontiers = new HashMap<>();
        
        // 初始化所有块的支配边界为空集
        for (BasicBlock block : function.getBasicBlocks()) {
            frontiers.put(block, new HashSet<>());
        }
        
        // 计算每个块的支配边界
        for (BasicBlock block : function.getBasicBlocks()) {
            // 如果块有多个前驱，可能是支配边界
            if (block.getPredecessors().size() >= 2) {
                for (BasicBlock pred : block.getPredecessors()) {
                    // 从前驱开始向上遍历支配树
                    BasicBlock runner = pred;
                    while (runner != idoms.get(block) && runner != null) {
                        frontiers.get(runner).add(block);
                        runner = idoms.get(runner);
                    }
                }
            }
        }
        
        return frontiers;
    }
    
    /**
     * 获取按支配树前序遍历排列的基本块列表
     * @param function 待分析的函数
     * @param idoms 直接支配关系映射
     * @return 按支配树前序遍历排列的基本块列表
     */
    public static List<BasicBlock> getBlocksInDominatorOrder(Function function, 
            Map<BasicBlock, BasicBlock> idoms) {
        List<BasicBlock> result = new ArrayList<>();
        
        // 构建反向idoms映射：一个节点直接支配哪些节点
        Map<BasicBlock, List<BasicBlock>> domChildren = new HashMap<>();
        for (BasicBlock block : function.getBasicBlocks()) {
            domChildren.put(block, new ArrayList<>());
        }
        
        for (Map.Entry<BasicBlock, BasicBlock> entry : idoms.entrySet()) {
            BasicBlock child = entry.getKey();
            BasicBlock parent = entry.getValue();
            domChildren.get(parent).add(child);
        }
        
        // 前序遍历支配树
        Stack<BasicBlock> stack = new Stack<>();
        Set<BasicBlock> visited = new HashSet<>();
        
        BasicBlock entry = function.getEntryBlock();
        if (entry != null) {
            stack.push(entry);
            visited.add(entry);
        }
        
        while (!stack.isEmpty()) {
            BasicBlock block = stack.pop();
            result.add(block);
            
            // 按反序将子节点入栈，以保证正确的前序遍历顺序
            List<BasicBlock> children = domChildren.get(block);
            for (int i = children.size() - 1; i >= 0; i--) {
                BasicBlock child = children.get(i);
                if (!visited.contains(child)) {
                    stack.push(child);
                    visited.add(child);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 获取按支配树后序遍历排列的基本块列表
     * @param function 待分析的函数
     * @return 按支配树后序遍历排列的基本块列表
     */
    public static List<BasicBlock> getDomPostOrder(Function function) {
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        
        // 获取入口基本块
        BasicBlock entry = function.getEntryBlock();
        if (entry == null) {
            return result;
        }
        
        // 执行后序遍历
        postOrderTraversal(entry, visited, result);
        
        return result;
    }
    
    /**
     * 支配树的后序遍历辅助方法
     * @param block 当前基本块
     * @param visited 已访问的基本块集合
     * @param result 结果列表
     */
    private static void postOrderTraversal(BasicBlock block, Set<BasicBlock> visited, 
            List<BasicBlock> result) {
        if (visited.contains(block)) {
            return;
        }
        
        visited.add(block);
        
        // 遍历当前块支配的子节点
        for (BasicBlock succ : block.getSuccessors()) {
            if (!visited.contains(succ)) {
                postOrderTraversal(succ, visited, result);
            }
        }
        
        // 后序遍历：先添加所有子节点，再添加自身
        result.add(block);
    }
    
    /**
     * 计算并设置支配树（直接支配者和支配级别）
     * @param function 待分析的函数
     */
    public static void computeDominatorTree(Function function) {
        try {
            // 计算支配关系
            Map<BasicBlock, Set<BasicBlock>> dominators = computeDominators(function);
            
            // 计算直接支配者关系
            Map<BasicBlock, BasicBlock> idoms = computeImmediateDominators(dominators);
            
            // 重置所有块的直接支配者和支配级别
            for (BasicBlock block : function.getBasicBlocks()) {
                block.setIdominator(null);
                block.setDomLevel(0);
            }
            
            // 设置直接支配者关系
            for (Map.Entry<BasicBlock, BasicBlock> entry : idoms.entrySet()) {
                BasicBlock block = entry.getKey();
                BasicBlock idom = entry.getValue();
                block.setIdominator(idom);
            }
            
            // 计算支配级别（入口块级别为0，其他块级别为其直接支配者级别+1）
            BasicBlock entry = function.getEntryBlock();
            if (entry != null) {
                computeDomLevelDFS(entry, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 深度优先搜索计算支配级别
     * @param block 当前块
     * @param level 当前级别
     */
    private static void computeDomLevelDFS(BasicBlock block, int level) {
        try {
            block.setDomLevel(level);
            
            // 添加一个深度检查，防止过深递归
            if (level > 1000) {
                return;
            }
            
            // 遍历当前块直接支配的所有块
            List<BasicBlock> dominated = findDominatedBlocks(block);
            
            for (BasicBlock dom : dominated) {
                computeDomLevelDFS(dom, level + 1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 查找被指定块直接支配的所有块
     * @param dominator 支配者
     * @return 被直接支配的块列表
     */
    private static List<BasicBlock> findDominatedBlocks(BasicBlock dominator) {
        try {
            List<BasicBlock> dominated = new ArrayList<>();
            Function function = dominator.getParentFunction();
            
            if (function == null) {
                return dominated;
            }
            
            for (BasicBlock block : function.getBasicBlocks()) {
                if (block == null) {
                    continue;
                }
                
                BasicBlock idom = block.getIdominator();
                if (idom == dominator) {
                    dominated.add(block);
                }
            }
            
            return dominated;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
} 
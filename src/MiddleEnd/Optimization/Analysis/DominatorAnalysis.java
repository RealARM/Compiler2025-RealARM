package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Value.*;

import java.util.*;

/**
 * 支配关系分析工具
 * 用于计算支配关系、支配树和支配边界
 */
public class DominatorAnalysis {
    
    public static Map<BasicBlock, Set<BasicBlock>> computeDominators(Function function) {
        Map<BasicBlock, Set<BasicBlock>> dominatorMap = new HashMap<>();
        
        List<BasicBlock> blocks = function.getBasicBlocks();
        if (blocks.isEmpty()) {
            return dominatorMap;
        }
        
        for (BasicBlock block : blocks) {
            dominatorMap.put(block, new HashSet<>());
        }
        
        BasicBlock entry = function.getEntryBlock();
        if (entry == null) {
            return dominatorMap;
        }
        
        dominatorMap.get(entry).add(entry);
        
        // 对于其他所有块，初始支配集合为所有块（后续会迭代缩小）
        for (BasicBlock block : blocks) {
            if (block != entry) {
                Set<BasicBlock> dominatedBlocks = dominatorMap.get(block);
                dominatedBlocks.addAll(blocks);
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
            
            for (BasicBlock block : blocks) {
                if (block == entry) {
                    continue;
                }
                
                Set<BasicBlock> newDomSet = new HashSet<>(blocks);
                
                List<BasicBlock> predecessors = block.getPredecessors();
                if (predecessors.isEmpty()) {
                    continue;
                }
                
                for (BasicBlock pred : predecessors) {
                    Set<BasicBlock> predDomSet = dominatorMap.get(pred);
                    if (!predDomSet.isEmpty()) {
                        newDomSet.retainAll(predDomSet);
                    }
                }
                
                newDomSet.add(block);
                
                Set<BasicBlock> oldDomSet = dominatorMap.get(block);
                if (!newDomSet.equals(oldDomSet)) {
                    dominatorMap.put(block, newDomSet);
                    changed = true;
                }
            }
            
        } while (changed);
        
        return dominatorMap;
    }
    
    public static Map<BasicBlock, BasicBlock> computeImmediateDominators(
            Map<BasicBlock, Set<BasicBlock>> dominators) {
        try {
            Map<BasicBlock, BasicBlock> idoms = new HashMap<>();
            
            for (BasicBlock block : dominators.keySet()) {
                Set<BasicBlock> domSet = new HashSet<>(dominators.get(block));
                domSet.remove(block);
                
                if (domSet.isEmpty()) {
                    continue;
                }
                
                BasicBlock idom = null;
                for (BasicBlock dominator : domSet) {
                    boolean isIdom = true;
                    
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
    
    public static Map<BasicBlock, Set<BasicBlock>> computeDominanceFrontiers(
            Function function, Map<BasicBlock, Set<BasicBlock>> dominators, 
            Map<BasicBlock, BasicBlock> idoms) {
        Map<BasicBlock, Set<BasicBlock>> frontiers = new HashMap<>();
        
        for (BasicBlock block : function.getBasicBlocks()) {
            frontiers.put(block, new HashSet<>());
        }
        
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
    
    public static List<BasicBlock> getBlocksInDominatorOrder(Function function, 
            Map<BasicBlock, BasicBlock> idoms) {
        List<BasicBlock> result = new ArrayList<>();
        
        Map<BasicBlock, List<BasicBlock>> domChildren = new HashMap<>();
        for (BasicBlock block : function.getBasicBlocks()) {
            domChildren.put(block, new ArrayList<>());
        }
        
        for (Map.Entry<BasicBlock, BasicBlock> entry : idoms.entrySet()) {
            BasicBlock child = entry.getKey();
            BasicBlock parent = entry.getValue();
            domChildren.get(parent).add(child);
        }
        
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
    
    private static void postOrderTraversal(BasicBlock block, Set<BasicBlock> visited, 
            List<BasicBlock> result) {
        if (visited.contains(block)) {
            return;
        }
        
        visited.add(block);
        
        for (BasicBlock succ : block.getSuccessors()) {
            if (!visited.contains(succ)) {
                postOrderTraversal(succ, visited, result);
            }
        }
        
        result.add(block);
    }

    public static void computeDominatorTree(Function function) {
        try {
            Map<BasicBlock, Set<BasicBlock>> dominators = computeDominators(function);
            
            Map<BasicBlock, BasicBlock> idoms = computeImmediateDominators(dominators);
            
            for (BasicBlock block : function.getBasicBlocks()) {
                block.setIdominator(null);
                block.setDomLevel(0);
            }
            
            for (Map.Entry<BasicBlock, BasicBlock> entry : idoms.entrySet()) {
                BasicBlock block = entry.getKey();
                BasicBlock idom = entry.getValue();
                block.setIdominator(idom);
            }
            
            BasicBlock entry = function.getEntryBlock();
            if (entry != null) {
                computeDomLevelDFS(entry, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
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
package IR.Pass.Utils;

import IR.Value.BasicBlock;
import IR.Value.Function;
import IR.Value.Instructions.Instruction;

import java.util.*;

/**
 * 循环分析工具类
 * 用于识别和分析IR中的循环结构
 */
public class LoopAnalysis {
    
    /**
     * 为函数中的所有基本块计算循环深度信息
     * @param function 待分析的函数
     */
    public static void runLoopInfo(Function function) {
        // 重置所有块的循环深度
        resetLoopDepth(function);
        
        // 识别自然循环
        identifyNaturalLoops(function);
    }
    
    /**
     * 重置所有基本块的循环深度为0
     */
    private static void resetLoopDepth(Function function) {
        for (BasicBlock bb : function.getBasicBlocks()) {
            bb.setLoopDepth(0);
        }
    }
    
    /**
     * 识别函数中的自然循环并计算循环深度
     */
    private static void identifyNaturalLoops(Function function) {
        // 寻找回边
        for (BasicBlock header : function.getBasicBlocks()) {
            for (BasicBlock pred : header.getPredecessors()) {
                // 如果前驱支配当前块，则形成回边
                if (isDominated(pred, header)) {
                    // 找到了回边 pred -> header，识别循环
                    identifyLoop(header, pred);
                }
            }
        }
    }
    
    /**
     * 判断block是否被header支配
     */
    private static boolean isDominated(BasicBlock block, BasicBlock header) {
        // 当前简化实现：检查header是否是block的支配者
        // 需要支配关系的计算，这里使用简化的方法
        // 在实际应用中，应使用完整的支配关系计算
        
        // 如果block是header本身，则被支配
        if (block == header) {
            return true;
        }
        
        // 如果block的直接支配者是header，则被支配
        if (block.getIdominator() == header) {
            return true;
        }
        
        // 递归检查block的直接支配者是否被header支配
        BasicBlock idom = block.getIdominator();
        if (idom != null && idom != block) {
            return isDominated(idom, header);
        }
        
        return false;
    }
    
    /**
     * 识别并标记循环
     * @param header 循环头
     * @param latch 循环尾（回边源）
     */
    private static void identifyLoop(BasicBlock header, BasicBlock latch) {
        // 收集循环体内的所有基本块
        Set<BasicBlock> loopBlocks = new HashSet<>();
        collectLoopBlocks(header, latch, loopBlocks);
        
        // 增加循环体内所有块的循环深度
        for (BasicBlock bb : loopBlocks) {
            bb.setLoopDepth(bb.getLoopDepth() + 1);
        }
    }
    
    /**
     * 收集循环体内的所有基本块
     * 从latch块开始反向遍历，直到到达header（但不包括header本身）
     */
    private static void collectLoopBlocks(BasicBlock header, BasicBlock latch, 
            Set<BasicBlock> loopBlocks) {
        if (loopBlocks.contains(latch) || latch == header) {
            return;
        }
        
        loopBlocks.add(latch);
        
        // 递归处理latch的所有前驱
        for (BasicBlock pred : latch.getPredecessors()) {
            collectLoopBlocks(header, pred, loopBlocks);
        }
    }
} 
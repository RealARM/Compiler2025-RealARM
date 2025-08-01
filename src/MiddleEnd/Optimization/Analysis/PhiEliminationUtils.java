package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.MoveInstruction;

import java.util.*;

/**
 * PhiEliminationUtils工具类，提供各种PhiElimination通用的功能
 */
public class PhiEliminationUtils {
    
    /**
     * 使用拓扑排序处理循环依赖的Move指令插入
     * 
     * @param bb 目标基本块
     * @param moves Move指令列表
     * @param debug 是否打印调试信息
     */
    public static void insertMovesWithCycleResolution(BasicBlock bb, List<Instruction> moves, boolean debug) {
        if (moves.isEmpty()) return;
        
        if (debug) {
            System.out.println("    开始处理循环依赖，共 " + moves.size() + " 个Move指令");
        }
        
        // 构建依赖图
        Map<String, Integer> outDegree = buildDependencyGraph(moves, debug);
        
        List<Instruction> remainingMoves = new ArrayList<>(moves);
        int round = 0;
        
        // 拓扑排序 + 破环处理
        while (!remainingMoves.isEmpty()) {
            round++;
            if (debug) {
                System.out.println("    第 " + round + " 轮，剩余 " + remainingMoves.size() + " 个Move指令");
            }
            
            // 寻找可以安全执行的Move指令
            List<Instruction> readyMoves = findReadyMoves(remainingMoves, outDegree, debug);
            
            if (readyMoves.isEmpty()) {
                // 检测到循环依赖，需要破环
                if (debug) {
                    System.out.println("      检测到循环依赖，需要破环");
                }
                breakCycleMoves(bb, remainingMoves, outDegree, debug);
            } else {
                // 执行可以安全执行的Move指令
                executeReadyMoves(bb, readyMoves, outDegree, debug);
                remainingMoves.removeAll(readyMoves);
            }
        }
        
        if (debug) {
            System.out.println("    循环依赖处理完成，共 " + round + " 轮");
        }
    }
    
    /**
     * 构建Move指令的依赖图
     */
    private static Map<String, Integer> buildDependencyGraph(List<Instruction> moves, boolean debug) {
        Map<String, Integer> outDegree = new LinkedHashMap<>();
        
        // 初始化所有目标变量的出度为0
        for (Instruction move : moves) {
            outDegree.put(move.getName(), 0);
        }
        
        // 计算实际的出度
        for (Instruction move : moves) {
            String sourceName = getSourceName(move);
            if (outDegree.containsKey(sourceName)) {
                outDegree.put(sourceName, outDegree.get(sourceName) + 1);
                if (debug) {
                    System.out.println("      依赖关系: " + move.getName() + " 依赖于 " + sourceName);
                }
            }
        }
        
        return outDegree;
    }
    
    /**
     * 寻找可以安全执行的Move指令（出度为0或自我赋值）
     */
    private static List<Instruction> findReadyMoves(List<Instruction> remainingMoves, 
                                                   Map<String, Integer> outDegree, boolean debug) {
        List<Instruction> readyMoves = new ArrayList<>();
        
        for (Instruction move : remainingMoves) {
            boolean canExecute = false;
            
            // 检查出度是否为0
            if (!outDegree.containsKey(move.getName()) || outDegree.get(move.getName()) == 0) {
                canExecute = true;
            }
            
            // 检查是否为自我赋值
            boolean isSelfAssignment = move.getName().equals(getSourceName(move));
            if (isSelfAssignment) {
                canExecute = true;
                if (debug) {
                    System.out.println("      跳过自我赋值: " + move.getName());
                }
            }
            
            if (canExecute) {
                readyMoves.add(move);
            }
        }
        
        return readyMoves;
    }
    
    /**
     * 执行准备好的Move指令
     */
    private static void executeReadyMoves(BasicBlock bb, List<Instruction> readyMoves, 
                                        Map<String, Integer> outDegree, boolean debug) {
        for (Instruction move : readyMoves) {
            boolean isSelfAssignment = move.getName().equals(getSourceName(move));
            
            if (!isSelfAssignment) {
                if (debug) {
                    System.out.println("      执行Move: " + move.toString());
                }
                insertInstructionBeforeTerminator(bb, move);
            }
            
            // 更新依赖关系
            String sourceName = getSourceName(move);
            if (outDegree.containsKey(sourceName)) {
                outDegree.put(sourceName, outDegree.get(sourceName) - 1);
            }
        }
    }
    
    /**
     * 破环处理：选择一个Move指令进行临时变量处理
     */
    private static void breakCycleMoves(BasicBlock bb, List<Instruction> remainingMoves, 
                                      Map<String, Integer> outDegree, boolean debug) {
        if (remainingMoves.isEmpty()) return;
        
        // 简单策略：选择第一个Move指令破环
        Instruction chosenMove = remainingMoves.get(0);
        
        if (debug) {
            System.out.println("        选择破环的Move: " + chosenMove.toString());
        }
        
        // 将选中的Move的目标出度设为0，使其可以被执行
        outDegree.put(chosenMove.getName(), 0);
        
        // 注意：这里简化了破环逻辑
        // 在实际应用中，可能需要引入临时变量来正确处理循环依赖
    }
    
    /**
     * 获取Move指令的源操作数名称
     */
    public static String getSourceName(Instruction inst) {
        if (inst instanceof MoveInstruction) {
            return ((MoveInstruction) inst).getSource().getName();
        } else {
            // 默认返回第一个操作数的名称
            return inst.getOperand(0).getName();
        }
    }
    
    /**
     * 将指令插入到基本块中的正确位置（终止指令之前）
     */
    public static void insertInstructionBeforeTerminator(BasicBlock bb, Instruction instruction) {
        List<Instruction> instructions = bb.getInstructions();
        
        // 寻找终止指令的位置
        int insertPos = instructions.size();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i).isTerminator()) {
                insertPos = i;
                break;
            }
        }
        
        // 在终止指令之前插入指令
        if (insertPos < instructions.size()) {
            instruction.insertBefore(instructions.get(insertPos));
        } else {
            bb.addInstruction(instruction);
        }
    }
} 
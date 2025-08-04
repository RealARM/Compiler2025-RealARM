package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.MoveInstruction;

import java.util.*;

/**
 * PhiEliminationUtils工具类，提供各种PhiElimination通用的功能
 */
public class PhiEliminationUtils {
    
    public static void insertMovesWithCycleResolution(BasicBlock bb, List<Instruction> moves, boolean debug) {
        if (moves.isEmpty()) return;
        
        if (debug) {
            System.out.println("    开始处理循环依赖，共 " + moves.size() + " 个Move指令");
        }
        
        Map<String, Integer> outDegree = buildDependencyGraph(moves, debug);
        
        List<Instruction> remainingMoves = new ArrayList<>(moves);
        int round = 0;
        
        while (!remainingMoves.isEmpty()) {
            round++;
            if (debug) {
                System.out.println("    第 " + round + " 轮，剩余 " + remainingMoves.size() + " 个Move指令");
            }
            
            List<Instruction> readyMoves = findReadyMoves(remainingMoves, outDegree, debug);
            
            if (readyMoves.isEmpty()) {
                if (debug) {
                    System.out.println("      检测到循环依赖，需要破环");
                }
                breakCycleMoves(bb, remainingMoves, outDegree, debug);
            } else {
                executeReadyMoves(bb, readyMoves, outDegree, debug);
                remainingMoves.removeAll(readyMoves);
            }
        }
        
        if (debug) {
            System.out.println("    循环依赖处理完成，共 " + round + " 轮");
        }
    }
    
    private static Map<String, Integer> buildDependencyGraph(List<Instruction> moves, boolean debug) {
        Map<String, Integer> outDegree = new LinkedHashMap<>();
        
        for (Instruction move : moves) {
            outDegree.put(move.getName(), 0);
        }
        
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
    
    private static List<Instruction> findReadyMoves(List<Instruction> remainingMoves, 
                                                   Map<String, Integer> outDegree, boolean debug) {
        List<Instruction> readyMoves = new ArrayList<>();
        
        for (Instruction move : remainingMoves) {
            boolean canExecute = false;
            
            if (!outDegree.containsKey(move.getName()) || outDegree.get(move.getName()) == 0) {
                canExecute = true;
            }
            
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
            
            String sourceName = getSourceName(move);
            if (outDegree.containsKey(sourceName)) {
                outDegree.put(sourceName, outDegree.get(sourceName) - 1);
            }
        }
    }
    
        private static void breakCycleMoves(BasicBlock bb, List<Instruction> remainingMoves, 
                                      Map<String, Integer> outDegree, boolean debug) {
        if (remainingMoves.isEmpty()) return;

        Instruction rawChosen = remainingMoves.get(0);
        if (!(rawChosen instanceof MoveInstruction)) {
            outDegree.put(rawChosen.getName(), 0);
            return;
        }
        MoveInstruction chosenMove = (MoveInstruction) rawChosen;

        if (debug) {
            System.out.println("        选择破环的Move并插入临时变量: " + chosenMove.toString());
        }

        String tmpName = chosenMove.getName() + "_phi_tmp";
        MoveInstruction saveInst = new MoveInstruction(tmpName, chosenMove.getType(), chosenMove.getSource());
        insertInstructionBeforeTerminator(bb, saveInst);

        chosenMove.replaceOperand(chosenMove.getSource(), saveInst);

        outDegree.put(chosenMove.getName(), 0);
        String oldSourceName = saveInst.getSource().getName();
        if (outDegree.containsKey(oldSourceName)) {
            outDegree.put(oldSourceName, outDegree.get(oldSourceName) - 1);
        }
    }
    
    public static String getSourceName(Instruction inst) {
        if (inst instanceof MoveInstruction) {
            return ((MoveInstruction) inst).getSource().getName();
        } else {
            return inst.getOperand(0).getName();
        }
    }
    
    public static void insertInstructionBeforeTerminator(BasicBlock bb, Instruction instruction) {
        List<Instruction> instructions = bb.getInstructions();
        
        int insertPos = instructions.size();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i).isTerminator()) {
                insertPos = i;
                break;
            }
        }
        
        if (insertPos < instructions.size()) {
            instruction.insertBefore(instructions.get(insertPos));
        } else {
            bb.addInstruction(instruction);
        }
    }
} 
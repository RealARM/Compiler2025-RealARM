package IR.Pass;

import IR.Module;
import IR.Value.BasicBlock;
import IR.Value.Function;
import IR.Value.Value;
import IR.Value.Instructions.Instruction;
import IR.Value.Instructions.MoveInstruction;
import IR.Value.Instructions.PhiInstruction;
import IR.Pass.Utils.PhiEliminationUtils;

import java.util.*;

/**
 * PHI指令消除优化Pass
 * 将SSA形式中的PHI指令转换为普通的Move指令
 * 使用拓扑排序和破环算法处理循环依赖
 */
public class RemovePhiPass implements Pass.IRPass {
    
    private static final boolean DEBUG = false;

    @Override
    public String getName() {
        return "RemovePhiPass";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        // 对每个函数进行PHI消除
        for (Function function : module.functions()) {
            if (module.libFunctions().contains(function)) {
                continue; // 跳过库函数
            }
            
            if (runOnFunction(function)) {
                changed = true;
            }
        }
        
        return changed;
    }
    
    /**
     * 对单个函数进行PHI消除
     */
    private boolean runOnFunction(Function function) {
        if (DEBUG) {
            System.out.println("RemovePhiPass: 处理函数 " + function.getName());
        }
        
        // 步骤1：收集所有PHI指令
        List<PhiInstruction> allPhis = collectPhiInstructions(function);
        
        if (allPhis.isEmpty()) {
            if (DEBUG) {
                System.out.println("  没有PHI指令，跳过处理");
            }
            return false;
        }
        
        if (DEBUG) {
            System.out.println("  总共找到 " + allPhis.size() + " 个PHI指令");
        }
        
        // 步骤2：为每个PHI指令生成对应的Move指令
        Map<BasicBlock, List<Instruction>> waitAddedMoves = generateMoveInstructions(allPhis);
        
        // 步骤3：在每个基本块中插入Move指令（处理循环依赖）
        insertMoveInstructions(waitAddedMoves);
        
        // 步骤4：删除所有PHI指令
        removePhiInstructions(allPhis);
        
        if (DEBUG) {
            System.out.println("RemovePhiPass: 函数 " + function.getName() + " 处理完成");
        }
        
        return true;
    }
    
    /**
     * 收集函数中的所有PHI指令
     */
    private List<PhiInstruction> collectPhiInstructions(Function function) {
        List<PhiInstruction> allPhis = new ArrayList<>();
        
        for (BasicBlock bb : function.getBasicBlocks()) {
            for (Instruction inst : new ArrayList<>(bb.getInstructions())) {
                if (inst instanceof PhiInstruction) {
                    allPhis.add((PhiInstruction) inst);
                    if (DEBUG) {
                        System.out.println("  发现PHI指令: " + inst.toString());
                    }
                }
            }
        }
        
        return allPhis;
    }
    
    /**
     * 为每个PHI指令生成对应的Move指令
     */
    private Map<BasicBlock, List<Instruction>> generateMoveInstructions(List<PhiInstruction> allPhis) {
        Map<BasicBlock, List<Instruction>> waitAddedMoves = new LinkedHashMap<>();
        
        // 初始化每个基本块的Move指令列表
        for (PhiInstruction phi : allPhis) {
            for (BasicBlock bb : phi.getIncomingBlocks()) {
                waitAddedMoves.computeIfAbsent(bb, k -> new ArrayList<>());
            }
        }
        
        // 为每个PHI指令生成Move指令
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  处理PHI: " + phi.getName() + " 有 " + phi.getIncomingBlocks().size() + " 个输入");
            }
            
            for (BasicBlock incomingBlock : phi.getIncomingBlocks()) {
                Value incomingValue = phi.getIncomingValue(incomingBlock);
                
                // 创建Move指令：phi_var = mov incoming_value
                MoveInstruction move = new MoveInstruction(phi.getName(), phi.getType(), incomingValue);
                waitAddedMoves.get(incomingBlock).add(move);
                
                if (DEBUG) {
                    System.out.println("    在 " + incomingBlock.getName() + " 中添加: " + move.toString());
                }
            }
        }
        
        return waitAddedMoves;
    }
    
    /**
     * 在各个基本块中插入Move指令
     */
    private void insertMoveInstructions(Map<BasicBlock, List<Instruction>> waitAddedMoves) {
        for (Map.Entry<BasicBlock, List<Instruction>> entry : waitAddedMoves.entrySet()) {
            BasicBlock bb = entry.getKey();
            List<Instruction> moves = entry.getValue();
            
            if (!moves.isEmpty()) {
                if (DEBUG) {
                    System.out.println("  在 " + bb.getName() + " 中插入 " + moves.size() + " 个Move指令");
                }
                
                // 使用工具类处理循环依赖
                PhiEliminationUtils.insertMovesWithCycleResolution(bb, moves, DEBUG);
            }
        }
    }
    
    /**
     * 删除所有PHI指令
     */
    private void removePhiInstructions(List<PhiInstruction> allPhis) {
        for (PhiInstruction phi : allPhis) {
            if (DEBUG) {
                System.out.println("  删除PHI指令: " + phi.getName());
            }
            phi.removeFromParent();
        }
    }
} 
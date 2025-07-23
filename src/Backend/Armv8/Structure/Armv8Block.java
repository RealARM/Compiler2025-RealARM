package Backend.Armv8.Structure;

import Backend.Armv8.Instruction.Armv8Instruction;
import Backend.Armv8.Instruction.Armv8Jump;
import Backend.Armv8.Instruction.Armv8Branch;
import Backend.Armv8.Operand.Armv8Label;
import Backend.Armv8.tools.Armv8Tools;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.ListIterator;

public class Armv8Block extends Armv8Label {
    private LinkedList<Armv8Instruction> instructions = new LinkedList<>();
    private LinkedHashSet<Armv8Block> preds = new LinkedHashSet<>();
    private LinkedHashSet<Armv8Block> succs = new LinkedHashSet<>();
    private boolean hasReturnInstruction = false; // 标记块是否包含返回指令

    public Armv8Block(String name) {
        super(name);
    }

    public void addArmv8Instruction(Armv8Instruction instruction) {
        instructions.add(instruction);
        // 如果添加的是返回指令，设置标记
        if (instruction instanceof Armv8Instruction.Armv8Ret) {
            hasReturnInstruction = true;
        }
    }

    public void setHasReturnInstruction(boolean hasRet) {
        this.hasReturnInstruction = hasRet;
    }

    public boolean hasReturnInstruction() {
        return hasReturnInstruction;
    }

    public void addPreds(Armv8Block block) {
        preds.add(block);
    }

    public void addSuccs(Armv8Block block) {
        succs.add(block);
    }

    public LinkedHashSet<Armv8Block> getPreds() {
        return this.preds;
    }

    public LinkedHashSet<Armv8Block> getSuccs() {
        return this.succs;
    }
    
    public LinkedList<Armv8Instruction> getInstructions() {
        return this.instructions;
    }

    public void removePred(Armv8Block block) {
        preds.remove(block);
    }

    public void removeSucc(Armv8Block block) {
        succs.remove(block);
    }
    
    /**
     * 获取最后一条指令
     * @return 最后一条指令，如果没有指令则返回null
     */
    public Armv8Instruction getLastInstruction() {
        return instructions.isEmpty() ? null : instructions.getLast();
    }
    
    /**
     * 在指定指令前插入新指令
     * @param target 目标指令
     * @param newInst 要插入的新指令
     */
    public void insertBeforeInst(Armv8Instruction target, Armv8Instruction newInst) {
        int index = instructions.indexOf(target);
        if (index != -1) {
            instructions.add(index, newInst);
        }
    }

    // 添加兼容方法
    public String getName() {
        return getLabelName();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getLabelName()).append(":\n");
        for (Armv8Instruction instruction : instructions) {
            sb.append("\t");
            // 避免循环调用，对于跳转指令特殊处理
            if (instruction instanceof Armv8Jump || 
                instruction instanceof Armv8Branch) {
                // 只显示指令名称和目标标签名，不调用指令的toString
                if (instruction instanceof Armv8Jump) {
                    sb.append("b\t");
                } else if (instruction instanceof Armv8Branch) {
                    Armv8Branch branch = (Armv8Branch) instruction;
                    sb.append("b").append(Armv8Tools.getCondString(branch.getType())).append("\t");
                }
                
                // 获取目标块名称
                if (instruction.getOperands().size() > 0 && 
                    instruction.getOperands().get(0) instanceof Armv8Block) {
                    Armv8Block targetBlock = (Armv8Block) instruction.getOperands().get(0);
                    sb.append(targetBlock.getLabelName());
                } else {
                    sb.append(instruction);
                }
            } else {
                // 其他指令正常调用toString
                sb.append(instruction);
            }
            sb.append("\n");
        }
        return sb.toString();
    }
} 
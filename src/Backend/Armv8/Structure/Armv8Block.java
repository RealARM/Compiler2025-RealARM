package Backend.Armv8.Structure;

import Backend.Armv8.Instruction.Armv8Instruction;
import Backend.Armv8.Operand.Armv8Label;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.ListIterator;

public class Armv8Block extends Armv8Label {
    private LinkedList<Armv8Instruction> instructions = new LinkedList<>();
    private LinkedHashSet<Armv8Block> preds = new LinkedHashSet<>();
    private LinkedHashSet<Armv8Block> succs = new LinkedHashSet<>();

    public Armv8Block(String name) {
        super(name);
    }

    public void addArmv8Instruction(Armv8Instruction instruction) {
        instructions.add(instruction);
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
            sb.append("\t").append(instruction).append("\n");
        }
        return sb.toString();
    }
} 
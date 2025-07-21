package Backend.Armv8.Structure;

import Backend.Armv8.Instruction.Armv8Instruction;
import java.util.ArrayList;
import java.util.LinkedList;

public class Armv8Function {
    private String name;
    private ArrayList<Armv8Block> blocks = new ArrayList<>();
    private LinkedList<Armv8Block> blockList = new LinkedList<>();
    private int stackSize = 0;

    public Armv8Function(String name) {
        this.name = name;
    }

    public void addBlock(Armv8Block block) {
        this.blocks.add(block);
        this.blockList.add(block);
    }

    public void addBlock(int index, Armv8Block block) {
        this.blocks.add(index, block);
    }

    public String getName() {
        return this.name;
    }

    public ArrayList<Armv8Block> getBlocks() {
        return this.blocks;
    }

    public LinkedList<Armv8Block> getBlockList() {
        return this.blockList;
    }

    public void setStackSize(int stackSize) {
        this.stackSize = stackSize;
    }

    public int getStackSize() {
        return this.stackSize;
    }
    
    // 在ARMv8中，被调用者保存的寄存器是x19-x29和SP
    // 需要在函数开始时保存这些寄存器，并在返回前恢复
    private String generatePrologue() {
        StringBuilder sb = new StringBuilder();
        // 保存被调用者保存的寄存器和链接寄存器
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        // 设置帧指针
        sb.append("\tmov x29, sp\n");
        // 如果需要，分配栈空间
        if (stackSize > 0) {
            sb.append("\tsub sp, sp, #").append(stackSize).append("\n");
        }
        return sb.toString();
    }
    
    private String generateEpilogue() {
        StringBuilder sb = new StringBuilder();
        // 如果栈指针被修改，恢复它
        if (stackSize > 0) {
            sb.append("\tadd sp, sp, #").append(stackSize).append("\n");
        }
        // 恢复被调用者保存的寄存器和链接寄存器
        sb.append("\tldp x29, x30, [sp], #16\n");
        sb.append("\tret\n");
        return sb.toString();
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(".global ").append(name).append("\n");
        sb.append(name).append(":\n");
        
        // 生成函数序言（保存寄存器，分配栈空间）
        sb.append(generatePrologue());
        
        // 输出基本块
        for (Armv8Block block : blocks) {
            sb.append(block.toString());
        }
        
        // 只有当最后一个块没有返回指令时才生成结语
        if (!blocks.isEmpty()) {
            Armv8Block lastBlock = blocks.get(blocks.size() - 1);
            if (lastBlock.getInstructions().isEmpty() || 
                !(lastBlock.getInstructions().getLast() instanceof Armv8Instruction.Armv8Ret)) {
                sb.append(generateEpilogue());
            }
        }
        
        return sb.toString();
    }
} 
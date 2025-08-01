package Backend.Structure;

import Backend.Utils.AArch64Tools;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Instruction.ControlFlow.AArch64Branch;
import Backend.Value.Instruction.ControlFlow.AArch64Jump;
import Backend.Value.Operand.Symbol.AArch64Label;

import java.util.LinkedHashSet;
import java.util.LinkedList;

public class AArch64Block extends AArch64Label {
    private LinkedList<AArch64Instruction> instructions = new LinkedList<>();
    private LinkedHashSet<AArch64Block> preds = new LinkedHashSet<>();
    private LinkedHashSet<AArch64Block> succs = new LinkedHashSet<>();
    private boolean hasReturnInstruction = false;

    public AArch64Block(String name) {
        super(name);
    }

    public void addAArch64Instruction(AArch64Instruction instruction) {
        instructions.add(instruction);
        // 如果添加的是返回指令，设置标记
        if (instruction instanceof AArch64Instruction.AArch64Ret) {
            hasReturnInstruction = true;
        }
    }

    public void setHasReturnInstruction(boolean hasRet) {
        this.hasReturnInstruction = hasRet;
    }

    public boolean hasReturnInstruction() {
        return hasReturnInstruction;
    }

    public void addPreds(AArch64Block block) {
        preds.add(block);
    }

    public void addSuccs(AArch64Block block) {
        succs.add(block);
    }

    public LinkedHashSet<AArch64Block> getPreds() {
        return this.preds;
    }

    public LinkedHashSet<AArch64Block> getSuccs() {
        return this.succs;
    }
    
    public LinkedList<AArch64Instruction> getInstructions() {
        return this.instructions;
    }

    public void removePred(AArch64Block block) {
        preds.remove(block);
    }

    public void removeSucc(AArch64Block block) {
        succs.remove(block);
    }
    

    public AArch64Instruction getLastInstruction() {
        return instructions.isEmpty() ? null : instructions.getLast();
    }
    

    public void insertBeforeInst(AArch64Instruction target, AArch64Instruction newInst) {
        int index = instructions.indexOf(target);
        if (index != -1) {
            instructions.add(index, newInst);
        }
    }

    public String getName() {
        return getLabelName();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getLabelName()).append(":\n");
        for (AArch64Instruction instruction : instructions) {
            sb.append("\t");
            // 避免循环调用，对于跳转指令特殊处理
            if (instruction instanceof AArch64Jump || 
                instruction instanceof AArch64Branch) {
                // 只显示指令名称和目标标签名，不调用指令的toString
                if (instruction instanceof AArch64Jump) {
                    sb.append("b\t");
                } else if (instruction instanceof AArch64Branch) {
                    AArch64Branch branch = (AArch64Branch) instruction;
                    sb.append("b").append(AArch64Tools.getCondString(branch.getType())).append("\t");
                }
                
                // 获取目标块名称
                if (instruction.getOperands().size() > 0 && 
                    instruction.getOperands().get(0) instanceof AArch64Block) {
                    AArch64Block targetBlock = (AArch64Block) instruction.getOperands().get(0);
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
package Backend.Value.Instruction.Comparison;

import Backend.Structure.AArch64Block;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ARMv8比较并分支如果为零指令
 * 如果寄存器值为零则跳转
 */
public class AArch64Cbz extends AArch64Instruction {
    
    public AArch64Cbz(AArch64Reg reg, AArch64Block targetBlock) {
        super(null, new ArrayList<>(Arrays.asList(reg, targetBlock)));
        
        // 设置前驱后继关系
        targetBlock.addPreds(targetBlock);
    }
    
    public void setPredSucc(AArch64Block block) {
        assert getOperands().get(1) instanceof AArch64Block;
        AArch64Block targetBlock = (AArch64Block) getOperands().get(1);
        targetBlock.addPreds(block);
        block.addSuccs(targetBlock);
    }
    
    @Override
    public String toString() {
        AArch64Block targetBlock = (AArch64Block) getOperands().get(1);
        // Use getLabelName() to get just the label name without colon
        String blockName = targetBlock.getLabelName();
        return "cbz\t" + getOperands().get(0) + ", " + blockName;
    }
} 
package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Reg;
import Backend.Armv8.Structure.Armv8Block;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ARMv8比较并分支如果为零指令
 * 如果寄存器值为零则跳转
 */
public class Armv8Cbz extends Armv8Instruction {
    
    public Armv8Cbz(Armv8Reg reg, Armv8Block targetBlock) {
        super(null, new ArrayList<>(Arrays.asList(reg, targetBlock)));
        
        // 设置前驱后继关系
        targetBlock.addPreds(targetBlock);
    }
    
    public void setPredSucc(Armv8Block block) {
        assert getOperands().get(1) instanceof Armv8Block;
        Armv8Block targetBlock = (Armv8Block) getOperands().get(1);
        targetBlock.addPreds(block);
        block.addSuccs(targetBlock);
    }
    
    @Override
    public String toString() {
        Armv8Block targetBlock = (Armv8Block) getOperands().get(1);
        // Use getLabelName() to get just the label name without colon
        String blockName = targetBlock.getLabelName();
        return "cbz\t" + getOperands().get(0) + ", " + blockName;
    }
} 
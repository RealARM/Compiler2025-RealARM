package Backend.Value.Instruction.Comparison;

import Backend.Structure.AArch64Block;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64VirReg;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Cbz extends AArch64Instruction {
    private boolean use32BitMode = true; // 标志位：默认使用32位指令
    
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
    
    /**
     * 设置指令使用32位模式
     */
    public void setUse32BitMode(boolean use32Bit) {
        this.use32BitMode = use32Bit;
    }
    
    /**
     * 获取当前是否使用32位模式
     */
    public boolean isUse32BitMode() {
        return this.use32BitMode;
    }
    
    /**
     * 根据指令的32位模式标志获取寄存器的字符串表示
     */
    private String getRegisterString(AArch64Reg reg) {
        if (reg instanceof AArch64CPUReg) {
            AArch64CPUReg cpuReg = (AArch64CPUReg) reg;
            return use32BitMode ? cpuReg.to32BitString() : cpuReg.to64BitString();
        } else if (reg instanceof AArch64VirReg) {
            AArch64VirReg virReg = (AArch64VirReg) reg;
            return use32BitMode ? virReg.to32BitString() : virReg.to64BitString();
        } else {
            return reg.toString();
        }
    }
    
    @Override
    public String toString() {
        AArch64Block targetBlock = (AArch64Block) getOperands().get(1);
        String blockName = targetBlock.getLabelName();
        AArch64Reg condReg = (AArch64Reg) getOperands().get(0);
        return "cbz\t" + getRegisterString(condReg) + ", " + blockName;
    }
} 
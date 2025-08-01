package Backend.Value.Instruction.ControlFlow;

import Backend.Structure.AArch64Block;
import Backend.Utils.AArch64Tools;
import Backend.Value.Base.AArch64Instruction;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Branch extends AArch64Instruction {
    private AArch64Tools.CondType type;

    public AArch64Branch(AArch64Block block, AArch64Tools.CondType type) {
        super(null, new ArrayList<>(Collections.singletonList(block)));
        this.type = type;
    }

    public void setPredSucc(AArch64Block block) {
        assert getOperands().get(0) instanceof AArch64Block;
        AArch64Block targetBlock = (AArch64Block) getOperands().get(0);
        targetBlock.addPreds(block);
        block.addSuccs(targetBlock);
    }

    public AArch64Tools.CondType getType() {
        return type;
    }

    public void setType(AArch64Tools.CondType type1) {
        type = type1;
    }

    @Override
    public String toString() {
        if (getOperands().size() > 0 && getOperands().get(0) instanceof AArch64Block) {
            AArch64Block targetBlock = (AArch64Block) getOperands().get(0);
            return "b" + AArch64Tools.getCondString(this.type) + "\t" + targetBlock.getLabelName();
        } else {
            return "b" + AArch64Tools.getCondString(this.type) + "\t" + getOperands().get(0);
        }
    }
} 
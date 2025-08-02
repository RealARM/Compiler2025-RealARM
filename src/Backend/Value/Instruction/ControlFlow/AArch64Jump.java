package Backend.Value.Instruction.ControlFlow;

import Backend.Structure.AArch64Block;
import Backend.Value.Base.AArch64Instruction;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Jump extends AArch64Instruction {
    public AArch64Jump(AArch64Block label, AArch64Block parent) {
        super(null, new ArrayList<>(Collections.singletonList(label)));
        label.addPreds(parent);
        parent.addSuccs(label);
    }

    @Override
    public String toString() {
        if (getOperands().size() > 0 && getOperands().get(0) instanceof AArch64Block) {
            AArch64Block targetBlock = (AArch64Block) getOperands().get(0);
            return "b\t" + targetBlock.getLabelName();
        } else {
            return "b\t" + getOperands().get(0);
        }
    }
} 
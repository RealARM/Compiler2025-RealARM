package Backend.Value.Instruction.ControlFlow;

import Backend.Structure.Armv8Block;
import Backend.Value.Base.Armv8Instruction;

import java.util.ArrayList;
import java.util.Collections;

public class Armv8Jump extends Armv8Instruction {
    public Armv8Jump(Armv8Block label, Armv8Block parent) {
        super(null, new ArrayList<>(Collections.singletonList(label)));
        label.addPreds(parent);
        parent.addSuccs(label);
    }

    @Override
    public String toString() {
        if (getOperands().size() > 0 && getOperands().get(0) instanceof Armv8Block) {
            Armv8Block targetBlock = (Armv8Block) getOperands().get(0);
            return "b\t" + targetBlock.getLabelName();
        } else {
            return "b\t" + getOperands().get(0);
        }
    }
} 
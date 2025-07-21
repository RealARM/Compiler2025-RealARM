package Backend.Armv8.Instruction;

import Backend.Armv8.Structure.Armv8Block;

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
        return "b\t" + getOperands().get(0);
    }
} 
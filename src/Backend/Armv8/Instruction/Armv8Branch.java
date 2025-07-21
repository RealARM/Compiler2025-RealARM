package Backend.Armv8.Instruction;

import Backend.Armv8.Structure.Armv8Block;
import Backend.Armv8.tools.Armv8Tools;

import java.util.ArrayList;
import java.util.Collections;

public class Armv8Branch extends Armv8Instruction {
    private Armv8Tools.CondType type;

    public Armv8Branch(Armv8Block block, Armv8Tools.CondType type) {
        super(null, new ArrayList<>(Collections.singletonList(block)));
        this.type = type;
    }

    public void setPredSucc(Armv8Block block) {
        assert getOperands().get(0) instanceof Armv8Block;
        Armv8Block targetBlock = (Armv8Block) getOperands().get(0);
        targetBlock.addPreds(block);
        block.addSuccs(targetBlock);
    }

    public Armv8Tools.CondType getType() {
        return type;
    }

    public void setType(Armv8Tools.CondType type1) {
        type = type1;
    }

    @Override
    public String toString() {
        return "b" + Armv8Tools.getCondString(this.type) + "\t" + getOperands().get(0);
    }
} 
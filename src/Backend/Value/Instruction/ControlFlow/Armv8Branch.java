package Backend.Value.Instruction.ControlFlow;

import Backend.Structure.Armv8Block;
import Backend.Utils.Armv8Tools;
import Backend.Value.Base.Armv8Instruction;

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
        if (getOperands().size() > 0 && getOperands().get(0) instanceof Armv8Block) {
            Armv8Block targetBlock = (Armv8Block) getOperands().get(0);
            return "b" + Armv8Tools.getCondString(this.type) + "\t" + targetBlock.getLabelName();
        } else {
            return "b" + Armv8Tools.getCondString(this.type) + "\t" + getOperands().get(0);
        }
    }
} 
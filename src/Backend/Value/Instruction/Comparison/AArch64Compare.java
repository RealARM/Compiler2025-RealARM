package Backend.Value.Instruction.Comparison;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;

import java.util.ArrayList;
import java.util.Arrays;

public class AArch64Compare extends AArch64Instruction {
    private CmpType type;
    
    public AArch64Compare(AArch64Operand left, AArch64Operand right, CmpType type) {
        super(null, new ArrayList<>(Arrays.asList(left, right)));
        this.type = type;
    }

    public enum CmpType {
        cmp, // 比较
        cmn, // 比较负值
        fcmp, // 浮点比较
    }

    public String getCmpTypeStr() {
        switch (this.type) {
            case cmn:
                return "cmn";
            case cmp:
                return "cmp";
            case fcmp:
                return "fcmp";
            default:
                return null;
        }
    }

    @Override
    public String toString() {
        return getCmpTypeStr() + "\t" + getOperands().get(0) + ",\t" + getOperands().get(1);
    }
} 
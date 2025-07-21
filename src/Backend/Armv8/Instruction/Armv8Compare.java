package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Operand;

import java.util.ArrayList;
import java.util.Arrays;

public class Armv8Compare extends Armv8Instruction {
    private CmpType type;
    private boolean is32Bit; // 是否是32位(w)比较而非64位(x)
    
    public Armv8Compare(Armv8Operand left, Armv8Operand right, CmpType type, boolean is32Bit) {
        super(null, new ArrayList<>(Arrays.asList(left, right)));
        this.type = type;
        this.is32Bit = is32Bit;
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
        // 对于寄存器，确定是否需要使用32位或64位版本
        String leftOp = getOperands().get(0).toString();
        String rightOp = getOperands().get(1).toString();
        
        // 如果操作数是以'x'开头的寄存器，如果使用32位则可能需要转换为'w'
        if (is32Bit && leftOp.startsWith("x")) {
            leftOp = "w" + leftOp.substring(1);
        }
        if (is32Bit && rightOp.startsWith("x")) {
            rightOp = "w" + rightOp.substring(1);
        }
        
        return getCmpTypeStr() + "\t" + leftOp + ",\t" + rightOp;
    }
} 
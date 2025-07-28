package Backend.Armv8.Structure;

import Backend.Armv8.Operand.Armv8Label;
import IR.Type.Type;

import java.util.ArrayList;

public class Armv8GlobalVariable extends Armv8Label {
    private final ArrayList<Number> initialValues;
    private final boolean isZeroInit;
    private final int byteSize;
    private final boolean isFloat;
    private final Type elementType;

    public Armv8GlobalVariable(String name, ArrayList<Number> initialValues, int byteSize, boolean isFloat, Type elementType) {
        super(name);
        this.initialValues = initialValues;
        this.isZeroInit = initialValues == null || initialValues.isEmpty();
        this.byteSize = byteSize;
        this.isFloat = isFloat;
        this.elementType = elementType;
    }

    public String getName() {
        return getLabelName();
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(getLabelName()).append(":\n");

        if (isZeroInit) {
            sb.append("\t.zero\t").append(byteSize).append("\n");
        } else if (isFloat) {
            sb.append("\t.double\t");
            for (int i = 0; i < initialValues.size(); i++) {
                sb.append(initialValues.get(i).floatValue());
                if (i != initialValues.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("\n");
        } else {
            // 根据元素类型的大小选择正确的汇编指令
            int elementSize = elementType != null ? elementType.getSize() : 4;
            if (elementSize == 1) {
                sb.append("\t.byte\t");
            } else if (elementSize == 2) {
                sb.append("\t.hword\t");
            } else if (elementSize == 4) {
                sb.append("\t.word\t");
            } else if (elementSize == 8) {
                sb.append("\t.quad\t");
            } else {
                // 默认使用word
                sb.append("\t.word\t");
            }
            
            for (int i = 0; i < initialValues.size(); i++) {
                sb.append(initialValues.get(i).intValue());
                if (i != initialValues.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
} 
package Backend.Structure;

import MiddleEnd.IR.Type.Type;

import java.util.ArrayList;

import Backend.Value.Operand.Symbol.AArch64Label;

public class AArch64GlobalVariable extends AArch64Label {
    private final ArrayList<Number> initialValues;
    private final boolean isZeroInit;
    private final int byteSize;
    private final boolean isFloat;
    private final Type elementType;

    public AArch64GlobalVariable(String name, ArrayList<Number> initialValues, int byteSize, boolean isFloat, Type elementType) {
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
            // 强制所有整型全局变量都使用64位 (.quad)
            sb.append("\t.quad\t");
            
            for (int i = 0; i < initialValues.size(); i++) {
                sb.append(initialValues.get(i).longValue());
                if (i != initialValues.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
} 
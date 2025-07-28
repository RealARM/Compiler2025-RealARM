package Backend.Armv8.Structure;

import Backend.Armv8.Operand.Armv8Label;

import java.util.ArrayList;

public class Armv8GlobalVariable extends Armv8Label {
    private final ArrayList<Number> initialValues;
    private final boolean isZeroInit;
    private final int byteSize;
    private final boolean isFloat;

    public Armv8GlobalVariable(String name, ArrayList<Number> initialValues, int byteSize, boolean isFloat) {
        super(name);
        this.initialValues = initialValues;
        this.isZeroInit = initialValues == null || initialValues.isEmpty();
        this.byteSize = byteSize;
        this.isFloat = isFloat;
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
            sb.append("\t.quad\t");
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
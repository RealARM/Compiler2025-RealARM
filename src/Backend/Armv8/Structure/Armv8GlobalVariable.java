package Backend.Armv8.Structure;

import Backend.Armv8.Operand.Armv8Label;

import java.util.ArrayList;

public class Armv8GlobalVariable extends Armv8Label {
    private final ArrayList<Byte> initialValues;
    private final boolean isZeroInit;
    private final int byteSize;

    public Armv8GlobalVariable(String name, ArrayList<Byte> initialValues, int byteSize) {
        super(name);
        this.initialValues = initialValues;
        this.isZeroInit = initialValues == null || initialValues.isEmpty();
        this.byteSize = byteSize;
    }

    // 添加兼容方法
    public String getName() {
        return getLabelName();
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(getLabelName()).append(":\n");
        
        if (isZeroInit) {
            // For zero-initialized variables in .bss
            sb.append("\t.zero\t").append(byteSize).append("\n");
        } else {
            // For initialized variables in .data
            if (byteSize == 1) {
                // Byte data
                sb.append("\t.byte\t");
                for (int i = 0; i < initialValues.size(); i++) {
                    sb.append(initialValues.get(i));
                    if (i != initialValues.size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append("\n");
            } else if (byteSize == 4) {
                // Word data (32-bit)
                sb.append("\t.word\t");
                for (int i = 0; i < initialValues.size(); i += 4) {
                    int value = 0;
                    for (int j = 0; j < 4 && i + j < initialValues.size(); j++) {
                        value |= (initialValues.get(i + j) & 0xFF) << (j * 8);
                    }
                    sb.append(value);
                    if (i + 4 < initialValues.size()) {
                        sb.append(", ");
                    }
                }
                sb.append("\n");
            } else if (byteSize == 8) {
                // Doubleword data (64-bit)
                sb.append("\t.dword\t");
                for (int i = 0; i < initialValues.size(); i += 8) {
                    long value = 0;
                    for (int j = 0; j < 8 && i + j < initialValues.size(); j++) {
                        value |= ((long) (initialValues.get(i + j) & 0xFF)) << (j * 8);
                    }
                    sb.append(value);
                    if (i + 8 < initialValues.size()) {
                        sb.append(", ");
                    }
                }
                sb.append("\n");
            } else {
                // Other sizes
                sb.append("\t.zero\t").append(byteSize).append("\n");
            }
        }
        return sb.toString();
    }
} 
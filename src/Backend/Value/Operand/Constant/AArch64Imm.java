package Backend.Value.Operand.Constant;

import Backend.Value.Base.AArch64Operand;

public class AArch64Imm extends AArch64Operand {
    private final long value;

    public AArch64Imm(long value) {
        this.value = value;
    }

    public long getValue() {
        return this.value;
    }

    @Override
    public String toString() {
        return "#" + value;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AArch64Imm) {
            return ((AArch64Imm) obj).value == this.value;
        }
        return false;
    }
} 
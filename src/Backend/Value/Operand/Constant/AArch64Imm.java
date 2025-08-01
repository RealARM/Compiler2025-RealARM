package Backend.Value.Operand.Constant;

import Backend.Value.Base.AArch64Operand;

public class AArch64Imm extends AArch64Operand {
    private final long value;  // 改为long类型

    public AArch64Imm(long value) {  // 构造函数参数改为long
        this.value = value;
    }

    public long getValue() {  // 返回类型改为long
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
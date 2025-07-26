package Backend.Armv8.Operand;

public class Armv8Imm extends Armv8Operand {
    private final long value;  // 改为long类型

    public Armv8Imm(long value) {  // 构造函数参数改为long
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
        if (obj instanceof Armv8Imm) {
            return ((Armv8Imm) obj).value == this.value;
        }
        return false;
    }
} 
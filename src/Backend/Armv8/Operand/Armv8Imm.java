package Backend.Armv8.Operand;

public class Armv8Imm extends Armv8Operand {
    private final int value;  // 改为int类型，ARM指令的立即数通常是32位的

    public Armv8Imm(int value) {  // 构造函数参数改为int
        this.value = value;
    }

    public int getValue() {  // 返回类型改为int
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
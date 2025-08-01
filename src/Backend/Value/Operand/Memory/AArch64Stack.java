package Backend.Value.Operand.Memory;

import Backend.Value.Base.AArch64Operand;

public class AArch64Stack extends AArch64Operand {
    private long offset;

    public AArch64Stack() {
        this.offset = 0;
    }

    public long getOffset() {
        return offset;
    }

    public void addOffset(long offset) {
        this.offset += offset;
    }

    @Override
    public String toString() {
        return "#" + ((offset + 15) & ~15);
    }
}

package Backend.Value.Operand.Register;

import Backend.Value.Base.Armv8Operand;

public class Armv8Stack extends Armv8Operand {
    private long offset;

    public Armv8Stack() {
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

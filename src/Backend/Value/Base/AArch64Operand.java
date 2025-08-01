package Backend.Value.Base;

import java.util.HashSet;

public class AArch64Operand {
    private final HashSet<AArch64Instruction> users = new HashSet<>();

    public AArch64Operand() {
    }

    public HashSet<AArch64Instruction> getUsers() {
        return this.users;
    }

    public void clearUsers() {
        users.clear();
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }
} 
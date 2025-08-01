package Backend.Value.Base;

import java.util.HashSet;

import Backend.Value.Base.Armv8Instruction;

public class Armv8Operand {
    private final HashSet<Armv8Instruction> users = new HashSet<>();

    public Armv8Operand() {
    }

    public HashSet<Armv8Instruction> getUsers() {
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
package Backend.Armv8.Operand;

import Backend.Armv8.Instruction.Armv8Instruction;
import Backend.Armv8.Structure.Armv8Block;

import java.util.HashSet;

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
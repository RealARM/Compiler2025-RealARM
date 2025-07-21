package Backend.Armv8.Operand;

public class Armv8VirReg extends Armv8Operand {
    private static int cnt = 0;
    private final int id;
    private final String name;
    private final Armv8Reg register;

    public Armv8VirReg() {
        super();
        this.id = cnt++;
        this.name = "vr" + this.id;
        this.register = Armv8Reg.allocateVirtualReg();
    }

    public int getId() {
        return id;
    }
    
    public Armv8Reg getRegister() {
        return register;
    }

    public static void resetId() {
        cnt = 0;
    }

    @Override
    public String toString() {
        return this.name;
    }
} 
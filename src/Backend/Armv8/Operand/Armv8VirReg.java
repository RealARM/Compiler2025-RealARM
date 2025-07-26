package Backend.Armv8.Operand;

public class Armv8VirReg extends Armv8Reg {
    private static int intRegCounter = 0;
    private static int floatRegCounter = 0;
    private final int id;
    private final String name;
    private final boolean isFloat;

    public Armv8VirReg(boolean isFloat) {
        this.isFloat = isFloat;
        if (isFloat) {
            this.id = floatRegCounter++;
            this.name = "vf" + this.id;
        } else {
            this.id = intRegCounter++;
            this.name = "vi" + this.id;
        }
    }

    public boolean isFloat() {
        return this.isFloat;
    }
    
    public int getId() {
        return id;
    }
    
    public static void resetCounter() {
        intRegCounter = 0;
        floatRegCounter = 0;
    }

    @Override
    public String toString() {
        return this.name;
    }
} 
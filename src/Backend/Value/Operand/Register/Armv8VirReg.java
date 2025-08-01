package Backend.Value.Operand.Register;

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
    
    /**
     * 重置虚拟寄存器计数器
     * 确保每个函数开始时有连续的寄存器编号
     */
    public static void resetCounter() {
        intRegCounter = 0;
        floatRegCounter = 0;
    }
    
    /**
     * 获取当前整型寄存器计数
     */
    public static int getCurrentIntCounter() {
        return intRegCounter;
    }
    
    /**
     * 获取当前浮点寄存器计数
     */
    public static int getCurrentFloatCounter() {
        return floatRegCounter;
    }
    
    /**
     * 检查寄存器编号是否有效
     */
    public boolean hasValidId() {
        if (isFloat) {
            return id >= 0 && id < floatRegCounter;
        } else {
            return id >= 0 && id < intRegCounter;
        }
    }

    @Override
    public String toString() {
        return this.name;
    }
} 
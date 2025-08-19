package Backend.Value.Operand.Register;

public class AArch64VirReg extends AArch64Reg {
    private static int intRegCounter = 0;
    private static int floatRegCounter = 0;
    private final int id;
    private final String name;
    private final boolean isFloat;

    public AArch64VirReg(boolean isFloat) {
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
    
    public static int getCurrentIntCounter() {
        return intRegCounter;
    }
    
    public static int getCurrentFloatCounter() {
        return floatRegCounter;
    }
    
    public boolean hasValidId() {
        if (isFloat) {
            return id >= 0 && id < floatRegCounter;
        } else {
            return id >= 0 && id < intRegCounter;
        }
    }
    
    /**
     * 获取64位寄存器名称
     */
    public String to64BitString() {
        return this.name;
    }
    
    /**
     * 获取32位寄存器名称
     */
    public String to32BitString() {
        if (isFloat) {
            return this.name; // 浮点寄存器没有32位版本
        }
        return "wi" + this.id;
    }

    @Override
    public String toString() {
        // 默认使用64位输出
        return to64BitString();
    }
} 
package Backend.Value.Operand.Register;

import java.util.LinkedHashMap;

public class Armv8CPUReg extends Armv8PhyReg {
    // ARMv8有31个通用寄存器和零寄存器
    private static final LinkedHashMap<Integer, String> Armv8IntRegNames = new LinkedHashMap<>();
    static {
        // x0-x7用于参数传递和返回值
        Armv8IntRegNames.put(0, "x0");
        Armv8IntRegNames.put(1, "x1");
        Armv8IntRegNames.put(2, "x2");
        Armv8IntRegNames.put(3, "x3");
        Armv8IntRegNames.put(4, "x4");
        Armv8IntRegNames.put(5, "x5");
        Armv8IntRegNames.put(6, "x6");
        Armv8IntRegNames.put(7, "x7");
        // x8用于间接结果返回
        Armv8IntRegNames.put(8, "x8");
        // x9-x15是调用者保存的临时寄存器
        Armv8IntRegNames.put(9, "x9");
        Armv8IntRegNames.put(10, "x10");
        Armv8IntRegNames.put(11, "x11");
        Armv8IntRegNames.put(12, "x12");
        Armv8IntRegNames.put(13, "x13");
        Armv8IntRegNames.put(14, "x14");
        Armv8IntRegNames.put(15, "x15");
        // x16-x17是过程内调用临时寄存器
        Armv8IntRegNames.put(16, "x16");
        Armv8IntRegNames.put(17, "x17");
        // x18是平台专用（保留）
        Armv8IntRegNames.put(18, "x18");
        // x19-x28是被调用者保存的寄存器
        Armv8IntRegNames.put(19, "x19");
        Armv8IntRegNames.put(20, "x20");
        Armv8IntRegNames.put(21, "x21");
        Armv8IntRegNames.put(22, "x22");
        Armv8IntRegNames.put(23, "x23");
        Armv8IntRegNames.put(24, "x24");
        Armv8IntRegNames.put(25, "x25");
        Armv8IntRegNames.put(26, "x26");
        Armv8IntRegNames.put(27, "x27");
        Armv8IntRegNames.put(28, "x28");
        // x29是帧指针(FP)
        Armv8IntRegNames.put(29, "x29");
        // x30是链接寄存器(LR)
        Armv8IntRegNames.put(30, "x30");
        // sp是栈指针
        Armv8IntRegNames.put(31, "sp");
        // xzr是零寄存器（在某些指令中可作为x31引用）
        Armv8IntRegNames.put(32, "xzr");
    }

    private final int index;
    private final String name;

    public Armv8CPUReg(int index, String name) {
        super();
        this.index = index;
        this.name = name;
    }
    
    
    private static LinkedHashMap<Integer, Armv8CPUReg> armv8CPURegs = new LinkedHashMap<>();
    static {
        for (int i = 0; i <= 32; i++) {
            armv8CPURegs.put(i, new Armv8CPUReg(i, Armv8IntRegNames.get(i)));
        }
    }
    
    public boolean canBeReorder(){
        // 被调用者保存寄存器和特殊寄存器不能被重排
        if(index >= 19 && index <= 30) return false;
        if(index == 31 || index == 32) return false; // SP和XZR
        return true;
    }
    
    public static LinkedHashMap<Integer, Armv8CPUReg> getAllCPURegs() {
        return armv8CPURegs;
    }
    
    public static Armv8CPUReg getArmv8CPUReg(int index) {
        return armv8CPURegs.get(index);
    }
    public static Armv8CPUReg getArmv8RetReg() {
        return armv8CPURegs.get(30); // 链接寄存器(x30)
    }

    public static Armv8CPUReg getArmv8CPURetValueReg() {
        return armv8CPURegs.get(0); // x0
    }

    public static Armv8CPUReg getArmv8SpReg() {
        return armv8CPURegs.get(31); // sp
    }

    public static Armv8CPUReg getArmv8FPReg() {
        return armv8CPURegs.get(29); // x29(帧指针)
    }

    public static Armv8CPUReg getArmv8ArgReg(int argIntIndex) {
        assert argIntIndex < 8; // ARMv8在寄存器中传递前8个参数
        return armv8CPURegs.get(argIntIndex);
    }

    public static Armv8CPUReg getZeroReg() {
        return armv8CPURegs.get(32); // xzr
    }

    public int getIndex() {
        return this.index;
    }

    public String getName() {
        return this.name;
    }

    // 默认使用64位寄存器
    @Override
    public String toString() {
        return this.name;
    }

    // 获取寄存器的32位视图(w0-w30)
    public String get32BitName() {
        if (index == 31) return "wsp"; // 32位SP是wsp
        if (index == 32) return "wzr"; // 32位零寄存器是wzr
        return "w" + index;
    }
    
    // 检查是否是64位寄存器(x寄存器)
    public boolean is64Bit() {
        // 在AArch64中，所有的CPU寄存器名称如果以'x'开头则为64位
        // 以'w'开头则为32位
        return this.name.startsWith("x") || this.name.equals("sp") || this.name.equals("xzr");
    }
} 
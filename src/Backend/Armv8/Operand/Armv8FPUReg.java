package Backend.Armv8.Operand;

import java.util.LinkedHashMap;

public class Armv8FPUReg extends Armv8PhyReg {
    // ARMv8有32个浮点/SIMD寄存器(v0-v31)
    private static LinkedHashMap<Integer, Armv8FPUReg> armv8FPURegs = new LinkedHashMap<>();
    static {
        for (int i = 0; i <= 31; i++) {
            armv8FPURegs.put(i, new Armv8FPUReg(i, "v" + i));
        }
    }

    @Override
    public boolean canBeReorder() {
        // v8-v15在AArch64中是被调用者保存的
        if(index >= 8 && index <= 15) {
            return false;
        }
        return true;
    }

    public static LinkedHashMap<Integer, Armv8FPUReg> getAllFPURegs() {
        return armv8FPURegs;
    }

    public static Armv8FPUReg getArmv8FloatReg(int index) {
        return armv8FPURegs.get(index);
    }

    public static Armv8FPUReg getArmv8FArgReg(int argIndex) {
        assert argIndex < 8; // ARMv8将前8个浮点参数传入v0-v7
        return getArmv8FloatReg(argIndex);
    }

    public static Armv8FPUReg getArmv8FPURetValueReg() {
        return armv8FPURegs.get(0); // v0用于返回值
    }

    private int index;
    private String name;

    public Armv8FPUReg(int index, String name) {
        super(index);
        this.index = index;
        this.name = name;
    }

    public int getIndex() {
        return this.index;
    }

    public String getName() {
        return this.name;
    }

    // 获取单精度浮点视图
    public String getSingleName() {
        return "s" + index;
    }

    // 获取双精度浮点视图
    public String getDoubleName() {
        return "d" + index;
    }

    // 获取四字SIMD视图
    public String getQuadName() {
        return "q" + index;
    }

    @Override
    public String toString() {
        return this.name;
    }
} 
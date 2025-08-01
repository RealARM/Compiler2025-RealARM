package Backend.Value.Operand.Register;

import java.util.LinkedHashMap;

public class Armv8FPUReg extends Armv8PhyReg {
    // ARMv8有32个浮点/SIMD寄存器(v0-v31)
    private static final LinkedHashMap<Integer, String> Armv8FPURegNames = new LinkedHashMap<>();
    static {
        // v0-v7用于参数传递和返回值
        Armv8FPURegNames.put(0, "v0");
        Armv8FPURegNames.put(1, "v1");
        Armv8FPURegNames.put(2, "v2");
        Armv8FPURegNames.put(3, "v3");
        Armv8FPURegNames.put(4, "v4");
        Armv8FPURegNames.put(5, "v5");
        Armv8FPURegNames.put(6, "v6");
        Armv8FPURegNames.put(7, "v7");
        // v8-v15是被调用者保存的寄存器
        Armv8FPURegNames.put(8, "v8");
        Armv8FPURegNames.put(9, "v9");
        Armv8FPURegNames.put(10, "v10");
        Armv8FPURegNames.put(11, "v11");
        Armv8FPURegNames.put(12, "v12");
        Armv8FPURegNames.put(13, "v13");
        Armv8FPURegNames.put(14, "v14");
        Armv8FPURegNames.put(15, "v15");
        // v16-v31是调用者保存的临时寄存器
        Armv8FPURegNames.put(16, "v16");
        Armv8FPURegNames.put(17, "v17");
        Armv8FPURegNames.put(18, "v18");
        Armv8FPURegNames.put(19, "v19");
        Armv8FPURegNames.put(20, "v20");
        Armv8FPURegNames.put(21, "v21");
        Armv8FPURegNames.put(22, "v22");
        Armv8FPURegNames.put(23, "v23");
        Armv8FPURegNames.put(24, "v24");
        Armv8FPURegNames.put(25, "v25");
        Armv8FPURegNames.put(26, "v26");
        Armv8FPURegNames.put(27, "v27");
        Armv8FPURegNames.put(28, "v28");
        Armv8FPURegNames.put(29, "v29");
        Armv8FPURegNames.put(30, "v30");
        Armv8FPURegNames.put(31, "v31");
    }

    private static LinkedHashMap<Integer, Armv8FPUReg> armv8FPURegs = new LinkedHashMap<>();
    static {
        for (int i = 0; i <= 31; i++) {
            armv8FPURegs.put(i, new Armv8FPUReg(i, Armv8FPURegNames.get(i)));
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
        super();
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
        // 全部使用64位
        return "d" + index;
    }
} 
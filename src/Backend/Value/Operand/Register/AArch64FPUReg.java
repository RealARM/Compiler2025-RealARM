package Backend.Value.Operand.Register;

import java.util.LinkedHashMap;

public class AArch64FPUReg extends AArch64PhyReg {
    // ARMv8有32个浮点/SIMD寄存器(v0-v31)
    private static final LinkedHashMap<Integer, String> AArch64FPURegNames = new LinkedHashMap<>();
    static {
        // v0-v7用于参数传递和返回值
        AArch64FPURegNames.put(0, "v0");
        AArch64FPURegNames.put(1, "v1");
        AArch64FPURegNames.put(2, "v2");
        AArch64FPURegNames.put(3, "v3");
        AArch64FPURegNames.put(4, "v4");
        AArch64FPURegNames.put(5, "v5");
        AArch64FPURegNames.put(6, "v6");
        AArch64FPURegNames.put(7, "v7");
        // v8-v15是被调用者保存的寄存器
        AArch64FPURegNames.put(8, "v8");
        AArch64FPURegNames.put(9, "v9");
        AArch64FPURegNames.put(10, "v10");
        AArch64FPURegNames.put(11, "v11");
        AArch64FPURegNames.put(12, "v12");
        AArch64FPURegNames.put(13, "v13");
        AArch64FPURegNames.put(14, "v14");
        AArch64FPURegNames.put(15, "v15");
        // v16-v31是调用者保存的临时寄存器
        AArch64FPURegNames.put(16, "v16");
        AArch64FPURegNames.put(17, "v17");
        AArch64FPURegNames.put(18, "v18");
        AArch64FPURegNames.put(19, "v19");
        AArch64FPURegNames.put(20, "v20");
        AArch64FPURegNames.put(21, "v21");
        AArch64FPURegNames.put(22, "v22");
        AArch64FPURegNames.put(23, "v23");
        AArch64FPURegNames.put(24, "v24");
        AArch64FPURegNames.put(25, "v25");
        AArch64FPURegNames.put(26, "v26");
        AArch64FPURegNames.put(27, "v27");
        AArch64FPURegNames.put(28, "v28");
        AArch64FPURegNames.put(29, "v29");
        AArch64FPURegNames.put(30, "v30");
        AArch64FPURegNames.put(31, "v31");
    }

    private static LinkedHashMap<Integer, AArch64FPUReg> armv8FPURegs = new LinkedHashMap<>();
    static {
        for (int i = 0; i <= 31; i++) {
            armv8FPURegs.put(i, new AArch64FPUReg(i, AArch64FPURegNames.get(i)));
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

    public static LinkedHashMap<Integer, AArch64FPUReg> getAllFPURegs() {
        return armv8FPURegs;
    }

    public static AArch64FPUReg getAArch64FloatReg(int index) {
        return armv8FPURegs.get(index);
    }

    public static AArch64FPUReg getAArch64FArgReg(int argIndex) {
        assert argIndex < 8; // ARMv8将前8个浮点参数传入v0-v7
        return getAArch64FloatReg(argIndex);
    }

    public static AArch64FPUReg getAArch64FPURetValueReg() {
        return armv8FPURegs.get(0); // v0用于返回值
    }

    private int index;
    private String name;

    public AArch64FPUReg(int index, String name) {
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
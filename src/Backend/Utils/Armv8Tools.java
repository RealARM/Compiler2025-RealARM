package Backend.Utils;

public class Armv8Tools {
    public static boolean isArmv8ImmEncodable(long imme) {
        // 在ARMv8中，立即数可以是带可选移位的12位值
        if (imme >= 0 && imme < 4096) {
            return true;
        }
        
        // 检查立即数是否可表示为逻辑立即数
        // （通过旋转和复制8位值创建的位模式）
        // 这是一个简化的检查
        for (int i = 1; i < 32; i++) {
            if ((imme & ((1L << i) - 1)) == imme && 
                Long.bitCount(imme) <= 8 &&
                trailingZeros(imme) + leadingZeros(imme) >= 56) {
                return true;
            }
        }
        
        return false;
    }
    
    private static int leadingZeros(long value) {
        return Long.numberOfLeadingZeros(value);
    }
    
    private static int trailingZeros(long value) {
        return Long.numberOfTrailingZeros(value);
    }

    public static boolean isFloatImmEncodable(float imm) {
        // ARMv8可以在指令中编码某些浮点常量
        // 检查值是否为±n × 2^r形式，其中n和r为整数，16 ≤ n ≤ 31，-3 ≤ r ≤ 4
        float eps = 1e-14f;
        float absImm = Math.abs(imm);
        
        for (int r = -3; r <= 4; r++) {
            for (int n = 16; n <= 31; n++) {
                float candidate = n * (float)Math.pow(2, r);
                if (Math.abs(absImm - candidate) < eps) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    public enum CondType {
        eq,  // ==
        ne,  // !=
        lt,  // <
        le,  // <=
        gt,  // >
        ge,  // >=
        nope,
        // ARMv8还包括这些额外的条件
        cs,  // 进位设置(无符号大于或等于)
        cc,  // 进位清除(无符号小于)
        mi,  // 负数(负)
        pl,  // 正数(正或零)
        vs,  // 溢出设置
        vc,  // 溢出清除
        hi,  // 无符号高于
        ls   // 无符号低于或相等
    }

    public static String getCondString(CondType type) {
        switch (type) {
            case eq -> {
                return "eq";
            }
            case lt -> {
                return "lt";
            }
            case le -> {
                return "le";
            }
            case gt -> {
                return "gt";
            }
            case ge -> {
                return "ge";
            }
            case ne -> {
                return "ne";
            }
            case nope -> {
                return "";
            }
            case cs -> {
                return "cs";
            }
            case cc -> {
                return "cc";
            }
            case mi -> {
                return "mi";
            }
            case pl -> {
                return "pl";
            }
            case vs -> {
                return "vs";
            }
            case vc -> {
                return "vc";
            }
            case hi -> {
                return "hi";
            }
            case ls -> {
                return "ls";
            }
        }
        return null;
    }

    public static CondType getRevCondType(CondType type) {
        switch (type) {
            case eq -> {
                return CondType.ne;
            }
            case lt -> {
                return CondType.ge;
            }
            case le -> {
                return CondType.gt;
            }
            case gt -> {
                return CondType.le;
            }
            case ge -> {
                return CondType.lt;
            }
            case ne -> {
                return CondType.eq;
            }
            case nope -> {
                return CondType.nope;
            }
            case cs -> {
                return CondType.cc;
            }
            case cc -> {
                return CondType.cs;
            }
            case mi -> {
                return CondType.pl;
            }
            case pl -> {
                return CondType.mi;
            }
            case vs -> {
                return CondType.vc;
            }
            case vc -> {
                return CondType.vs;
            }
            case hi -> {
                return CondType.ls;
            }
            case ls -> {
                return CondType.hi;
            }
        }
        return null;
    }
} 
package MiddleEnd.Optimization.Loop;

/**
 * 循环展开优化配置管理
 */
public class LoopUnrollConfig {
    
    // 常数循环展开配置
    public static final int MAX_UNROLL_ITERATIONS = 64;
    public static final int MAX_CONSTANT_UNROLL_SIZE = 2000;
    
    // 动态循环展开配置
    public static final int DYNAMIC_UNROLL_FACTOR = 4;
    public static final int MAX_DYNAMIC_LOOP_SIZE = 50;
    public static final int DYNAMIC_UNROLL_THRESHOLD = 3;
    
    // 代码大小限制
    public static final int MAX_TOTAL_UNROLL_SIZE = 5000;
    public static final int MAX_FUNCTION_SIZE_AFTER_UNROLL = 10000;
    
    // 性能阈值
    public static final double MIN_UNROLL_BENEFIT_RATIO = 1.2; // 至少20%的性能提升
    
    // 乘法归纳变量支持
    public static final boolean ENABLE_MULTIPLICATIVE_INDUCTION = true;
    public static final int MAX_MULTIPLICATIVE_ITERATIONS = 32;
    
    // 嵌套循环处理
    public static final int MAX_NESTED_LOOP_DEPTH = 4;
    public static final boolean UNROLL_INNER_LOOPS_FIRST = true;
    
    // 调试和分析
    public static final boolean ENABLE_UNROLL_STATISTICS = false;
    public static final boolean VERBOSE_UNROLL_LOGGING = true;
    
    private LoopUnrollConfig() {
        // 工具类，不允许实例化
    }
    
    /**
     * 检查是否启用乘法归纳变量支持
     */
    public static boolean isMultiplicativeInductionEnabled() {
        return ENABLE_MULTIPLICATIVE_INDUCTION;
    }
    
    /**
     * 获取动态展开的位掩码
     */
    public static int getDynamicUnrollMask() {
        return ~(DYNAMIC_UNROLL_FACTOR - 1);
    }
    
    /**
     * 计算展开后的预估代码大小
     */
    public static long calculateExpandedSize(int loopSize, int iterations) {
        return (long) loopSize * iterations;
    }
    
    /**
     * 检查展开是否超过大小限制
     */
    public static boolean exceedsSizeLimit(int loopSize, int iterations) {
        return calculateExpandedSize(loopSize, iterations) > MAX_CONSTANT_UNROLL_SIZE;
    }
} 
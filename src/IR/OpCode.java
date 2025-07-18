package IR;

/**
 * 表示IR指令的操作码
 */
public enum OpCode {
    // 终结指令
    RET("ret"),            // 返回指令
    BR("br"),              // 分支指令
    
    // 二元运算指令
    ADD("add"),            // 整数加法
    SUB("sub"),            // 整数减法
    MUL("mul"),            // 整数乘法
    DIV("div"),            // 整数除法
    REM("srem"),           // 整数余数（有符号）
    NEG("neg"),            // 一元负号
    
    // 浮点运算指令
    FADD("fadd"),          // 浮点加法
    FSUB("fsub"),          // 浮点减法
    FMUL("fmul"),          // 浮点乘法
    FDIV("fdiv"),          // 浮点除法
    FREM("frem"),          // 浮点余数
    
    // 位运算指令
    SHL("shl"),            // 左移
    LSHR("lshr"),          // 逻辑右移
    ASHR("ashr"),          // 算术右移
    AND("and"),            // 位与
    OR("or"),              // 位或
    XOR("xor"),            // 位异或
    
    // 比较指令
    ICMP("icmp"),          // 整数比较
    FCMP("fcmp"),          // 浮点比较
    
    // 整数比较条件
    EQ("eq"),              // 等于
    NE("ne"),              // 不等于
    SGT("sgt"),            // 有符号大于
    SGE("sge"),            // 有符号大于等于
    SLT("slt"),            // 有符号小于
    SLE("sle"),            // 有符号小于等于
    
    // 浮点比较条件
    UEQ("ueq"),            // 无序或等于
    UNE("une"),            // 无序或不等于
    UGT("ugt"),            // 无序或大于
    UGE("uge"),            // 无序或大于等于
    ULT("ult"),            // 无序或小于
    ULE("ule"),            // 无序或小于等于
    ORD("ord"),            // 有序
    UNO("uno"),            // 无序
    
    // 内存操作指令
    ALLOCA("alloca"),      // 分配栈空间
    LOAD("load"),          // 加载
    STORE("store"),        // 存储
    GETELEMENTPTR("getelementptr"), // 获取元素指针
    
    // 其他指令
    PHI("phi"),            // phi节点
    CALL("call"),          // 函数调用
    SELECT("select"),      // 选择
    
    // 转换指令
    TRUNC("trunc"),        // 截断转换
    ZEXT("zext"),          // 零扩展
    SEXT("sext"),          // 符号扩展
    FPTRUNC("fptrunc"),    // 浮点截断
    FPEXT("fpext"),        // 浮点扩展
    FPTOUI("fptoui"),      // 浮点转无符号整数
    FPTOSI("fptosi"),      // 浮点转有符号整数
    UITOFP("uitofp"),      // 无符号整数转浮点
    SITOFP("sitofp"),      // 有符号整数转浮点
    BITCAST("bitcast");    // 位转换
    
    private final String name;
    
    OpCode(String name) {
        this.name = name;
    }
    
    /**
     * 获取操作码的字符串表示
     */
    public String getName() {
        return name;
    }
    
    /**
     * 判断是否为终结指令操作码
     */
    public boolean isTerminator() {
        return this == RET || this == BR;
    }
    
    /**
     * 判断是否为二元运算操作码
     */
    public boolean isBinaryOp() {
        return this == ADD || this == SUB || this == MUL || this == DIV || this == REM ||
               this == FADD || this == FSUB || this == FMUL || this == FDIV || this == FREM ||
               this == SHL || this == LSHR || this == ASHR || this == AND || this == OR || this == XOR;
    }
    
    /**
     * 判断是否为比较操作码
     */
    public boolean isCompare() {
        return this == ICMP || this == FCMP;
    }
    
    /**
     * 判断是否为内存操作码
     */
    public boolean isMemoryOp() {
        return this == ALLOCA || this == LOAD || this == STORE || this == GETELEMENTPTR;
    }
    
    /**
     * 判断是否为转换操作码
     */
    public boolean isConversionOp() {
        return this == TRUNC || this == ZEXT || this == SEXT || this == FPTRUNC || this == FPEXT ||
               this == FPTOUI || this == FPTOSI || this == UITOFP || this == SITOFP || this == BITCAST;
    }
    
    /**
     * 根据名称获取操作码
     */
    public static OpCode fromString(String name) {
        for (OpCode opCode : values()) {
            if (opCode.name.equals(name)) {
                return opCode;
            }
        }
        throw new IllegalArgumentException("Unknown OpCode: " + name);
    }
} 
package MiddleEnd.IR;

public enum OpCode {
    RET("ret"),
    BR("br"),
    
    ADD("add"),
    SUB("sub"),
    MUL("mul"),
    DIV("sdiv"),
    REM("srem"),
    NEG("neg"),
    
    FADD("fadd"),
    FSUB("fsub"),
    FMUL("fmul"),
    FDIV("fdiv"),
    FREM("frem"),
    
    SHL("shl"),
    LSHR("lshr"),
    ASHR("ashr"),
    AND("and"),
    OR("or"),
    XOR("xor"),
    
    ICMP("icmp"),
    FCMP("fcmp"),
    
    EQ("eq"),
    NE("ne"),
    SGT("sgt"),
    SGE("sge"),
    SLT("slt"),
    SLE("sle"),
    
    UEQ("ueq"),
    UNE("une"),
    UGT("ugt"),
    UGE("uge"),
    ULT("ult"),
    ULE("ule"),
    ORD("ord"),
    UNO("uno"),
    
    ALLOCA("alloca"),
    LOAD("load"),
    STORE("store"),
    GETELEMENTPTR("getelementptr"),
    
    PHI("phi"),
    CALL("call"),
    SELECT("select"),
    MOV("mov"),
    
    TRUNC("trunc"),
    ZEXT("zext"),
    SEXT("sext"),
    FPTRUNC("fptrunc"),
    FPEXT("fpext"),
    FPTOUI("fptoui"),
    FPTOSI("fptosi"),
    UITOFP("uitofp"),
    SITOFP("sitofp"),
    BITCAST("bitcast");
    
    private final String name;
    
    OpCode(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
    
    public boolean isTerminator() {
        return this == RET || this == BR;
    }
    
    public boolean isBinaryOp() {
        return this == ADD || this == SUB || this == MUL || this == DIV || this == REM ||
               this == FADD || this == FSUB || this == FMUL || this == FDIV || this == FREM ||
               this == SHL || this == LSHR || this == ASHR || this == AND || this == OR || this == XOR;
    }
    
    public boolean isCompare() {
        return this == ICMP || this == FCMP;
    }
    
    public boolean isMemoryOp() {
        return this == ALLOCA || this == LOAD || this == STORE || this == GETELEMENTPTR;
    }
    
    public boolean isConversionOp() {
        return this == TRUNC || this == ZEXT || this == SEXT || this == FPTRUNC || this == FPEXT ||
               this == FPTOUI || this == FPTOSI || this == UITOFP || this == SITOFP || this == BITCAST;
    }
    
    public static OpCode fromString(String name) {
        for (OpCode opCode : values()) {
            if (opCode.name.equals(name)) {
                return opCode;
            }
        }
        throw new IllegalArgumentException("Unknown OpCode: " + name);
    }
} 
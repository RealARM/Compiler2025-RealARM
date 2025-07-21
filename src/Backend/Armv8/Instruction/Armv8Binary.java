package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;

public class Armv8Binary extends Armv8Instruction {
    private final Armv8BinaryType instType;
    private final int shiftBit;
    private final Armv8ShiftType shiftType;
    private final boolean is32Bit; // 是否使用32位操作(w寄存器)而非64位(x寄存器)

    public Armv8Binary(ArrayList<Armv8Operand> uses, Armv8Reg defReg, Armv8BinaryType type, boolean is32Bit) {
        super(defReg, uses);
        this.instType = type;
        this.shiftBit = 0;
        this.shiftType = Armv8ShiftType.LSL;
        this.is32Bit = is32Bit;
    }

    public Armv8Binary(ArrayList<Armv8Operand> uses, Armv8Reg defReg, int shiftBit,
                     Armv8ShiftType shiftType, Armv8BinaryType type, boolean is32Bit) {
        super(defReg, uses);
        this.instType = type;
        this.shiftBit = shiftBit;
        this.shiftType = shiftType;
        this.is32Bit = is32Bit;
    }

    public Armv8BinaryType getInstType() {
        return instType;
    }

    public boolean is32Bit() {
        return is32Bit;
    }

    public enum Armv8ShiftType {
        LSL, // 逻辑左移
        LSR, // 逻辑右移
        ASR, // 算术右移
        ROR, // 循环右移
    }

    public String shiftTypeToString() {
        switch (shiftType) {
            case LSL -> {
                return "LSL";
            }
            case LSR -> {
                return "LSR";
            }
            case ASR -> {
                return "ASR";
            }
            case ROR -> {
                return "ROR";
            }
        }
        return null;
    }

    public enum Armv8BinaryType {
        // 整数运算
        add,    // 加法
        adds,   // 加法并设置标志位
        sub,    // 减法
        subs,   // 减法并设置标志位
        mul,    // 乘法
        sdiv,   // 有符号除法
        udiv,   // 无符号除法
        msub,   // 乘减(a - b*c)
        madd,   // 乘加(a + b*c)
        
        // 逻辑运算
        and,    // 按位与
        ands,   // 按位与并设置标志位
        orr,    // 按位或
        eor,    // 按位异或
        bic,    // 按位与非(a & ~b)
        
        // 移位操作
        lsl,    // 逻辑左移
        lsr,    // 逻辑右移
        asr,    // 算术右移
        ror,    // 循环右移
        
        // 浮点运算
        fadd,   // 浮点加法
        fsub,   // 浮点减法
        fmul,   // 浮点乘法
        fdiv,   // 浮点除法
    }

    public String binaryTypeToString() {
        switch(instType) {
            case add -> {
                return is32Bit ? "add" : "add";
            }
            case adds -> {
                return is32Bit ? "adds" : "adds";
            }
            case sub -> {
                return is32Bit ? "sub" : "sub";
            }
            case subs -> {
                return is32Bit ? "subs" : "subs";
            }
            case mul -> {
                return is32Bit ? "mul" : "mul";
            }
            case sdiv -> {
                return is32Bit ? "sdiv" : "sdiv";
            }
            case udiv -> {
                return is32Bit ? "udiv" : "udiv";
            }
            case msub -> {
                return is32Bit ? "msub" : "msub";
            }
            case madd -> {
                return is32Bit ? "madd" : "madd";
            }
            case and -> {
                return is32Bit ? "and" : "and";
            }
            case ands -> {
                return is32Bit ? "ands" : "ands";
            }
            case orr -> {
                return is32Bit ? "orr" : "orr";
            }
            case eor -> {
                return is32Bit ? "eor" : "eor";
            }
            case bic -> {
                return is32Bit ? "bic" : "bic";
            }
            case lsl -> {
                return is32Bit ? "lsl" : "lsl";
            }
            case lsr -> {
                return is32Bit ? "lsr" : "lsr";
            }
            case asr -> {
                return is32Bit ? "asr" : "asr";
            }
            case ror -> {
                return is32Bit ? "ror" : "ror";
            }
            case fadd -> {
                return "fadd";
            }
            case fsub -> {
                return "fsub";
            }
            case fmul -> {
                return "fmul";
            }
            case fdiv -> {
                return "fdiv";
            }
        }
        return null;
    }

    @Override
    public String toString() {
        if (shiftBit == 0) {
            return binaryTypeToString() + "\t" + getDefReg() + ", " +
                    getOperands().get(0) + ", " + getOperands().get(1);
        } else {
            return binaryTypeToString() + "\t" + getDefReg() + ", " +
                    getOperands().get(0) + ", " + getOperands().get(1) + ", " + shiftTypeToString() + " #" + shiftBit;
        }
    }
} 
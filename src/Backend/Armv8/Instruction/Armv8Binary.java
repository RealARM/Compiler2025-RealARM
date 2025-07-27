package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;
import Backend.Armv8.Operand.Armv8Imm;

import java.util.ArrayList;

public class Armv8Binary extends Armv8Instruction {
    private final Armv8BinaryType instType;
    private final int shiftBit;
    private final Armv8ShiftType shiftType;
    private final Armv8Imm imm;

    public Armv8Binary(ArrayList<Armv8Operand> uses, Armv8Reg defReg, Armv8BinaryType type) {
        super(defReg, uses);
        this.instType = type;
        this.shiftBit = 0;
        this.shiftType = Armv8ShiftType.LSL;
        this.imm = null;
    }

    public Armv8Binary(ArrayList<Armv8Operand> uses, Armv8Reg defReg, int shiftBit,
                     Armv8ShiftType shiftType, Armv8BinaryType type) {
        super(defReg, uses);
        this.instType = type;
        this.shiftBit = shiftBit;
        this.shiftType = shiftType;
        this.imm = null;
    }

    public Armv8Binary(Armv8Reg defReg, Armv8Reg srcReg, Armv8Imm imm, Armv8BinaryType type) {
        super(defReg, new ArrayList<Armv8Operand>() {{
            add(srcReg);
            add(imm);
        }});
        this.instType = type;
        this.shiftBit = 0;
        this.shiftType = Armv8ShiftType.LSL;
        this.imm = imm;
    }

    public Armv8BinaryType getInstType() {
        return instType;
    }

    public enum Armv8ShiftType {
        LSL, // 逻辑左移
        LSR, // 逻辑右移
        ASR, // 算术右移
        ROR, // 循环右移
    }

    public String shiftTypeToString() {
        switch (shiftType) {
            case LSL:
                return "LSL";
            case LSR:
                return "LSR";
            case ASR:
                return "ASR";
            case ROR:
                return "ROR";
            default:
                return null;
        }
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
            case add:
                return "add";
            case adds:
                return "adds";
            case sub:
                return "sub";
            case subs:
                return "subs";
            case mul:
                return "mul";
            case sdiv:
                return "sdiv";
            case udiv:
                return "udiv";
            case msub:
                return "msub";
            case madd:
                return "madd";
            case and:
                return "and";
            case ands:
                return "ands";
            case orr:
                return "orr";
            case eor:
                return "eor";
            case bic:
                return "bic";
            case lsl:
                return "lsl";
            case lsr:
                return "lsr";
            case asr:
                return "asr";
            case ror:
                return "ror";
            case fadd:
                return "fadd";
            case fsub:
                return "fsub";
            case fmul:
                return "fmul";
            case fdiv:
                return "fdiv";
            default:
                return null;
        }
    }

    @Override
    public String toString() {
        if (imm != null) {
            return binaryTypeToString() + "\t" + getDefReg() + ", " + getOperands().get(0) + ", " + getOperands().get(1);
        } else if (instType == Armv8BinaryType.madd || instType == Armv8BinaryType.msub) {
            // MADD/MSUB指令有特殊格式：madd d, a, b, c （d = a*b + c）
            if (getOperands().size() >= 3) {
                return binaryTypeToString() + "\t" + getDefReg() + ", " +
                       getOperands().get(1) + ", " + getOperands().get(2) + ", " + getOperands().get(0);
            } else {
                return binaryTypeToString() + "\t" + getDefReg() + ", " + 
                       getOperands().get(0) + ", " + getOperands().get(1);
            }
        } else if (shiftBit == 0) {
            return binaryTypeToString() + "\t" + getDefReg() + ", " +
                    getOperands().get(0) + ", " + getOperands().get(1);
        } else {
            return binaryTypeToString() + "\t" + getDefReg() + ", " +
                    getOperands().get(0) + ", " + getOperands().get(1) + ", " + shiftTypeToString() + " #" + shiftBit;
        }
    }
} 
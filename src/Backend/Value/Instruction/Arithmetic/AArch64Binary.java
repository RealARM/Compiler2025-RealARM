package Backend.Value.Instruction.Arithmetic;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64VirReg;

import java.util.ArrayList;

public class AArch64Binary extends AArch64Instruction {
    private AArch64BinaryType instType;
    private AArch64ShiftType shiftType;
    private int shiftBit = 0;
    private AArch64Imm imm;
    private boolean use32BitMode = false; // 指令级别的32位模式标志

    // 构造函数：两个寄存器操作数
    public AArch64Binary(ArrayList<AArch64Operand> operands, AArch64Reg defReg, AArch64BinaryType instType) {
        super(defReg, operands);
        this.instType = instType;
        this.use32BitMode = !isFloatOperation();
    }

    // 构造函数：一个寄存器和一个立即数
    public AArch64Binary(AArch64Reg defReg, AArch64Operand firstOperand, AArch64Imm imm, AArch64BinaryType instType) {
        super(defReg, new ArrayList<>());
        this.operands.add(firstOperand);
        this.operands.add(imm);
        this.instType = instType;
        this.imm = imm;
        this.use32BitMode = !isFloatOperation();
    }

    // 构造函数：带移位的指令
    public AArch64Binary(AArch64Reg defReg, AArch64Operand firstOperand, AArch64Operand secondOperand, 
                        AArch64BinaryType instType, AArch64ShiftType shiftType, int shiftBit) {
        super(defReg, new ArrayList<>());
        this.operands.add(firstOperand);
        this.operands.add(secondOperand);
        this.instType = instType;
        this.shiftType = shiftType;
        this.shiftBit = shiftBit;
        this.use32BitMode = !isFloatOperation();
    }

    public AArch64BinaryType getInstType() {
        return instType;
    }
    
    /**
     * 设置指令使用32位模式
     */
    public void setUse32BitMode(boolean use32Bit) {
        this.use32BitMode = use32Bit;
    }
    
    /**
     * 获取当前是否使用32位模式
     */
    public boolean isUse32BitMode() {
        return this.use32BitMode;
    }
    
    /**
     * 判断是否是浮点运算
     */
    private boolean isFloatOperation() {
        return instType == AArch64BinaryType.fadd ||
               instType == AArch64BinaryType.fsub ||
               instType == AArch64BinaryType.fmul ||
               instType == AArch64BinaryType.fdiv;
    }
    
    /**
     * 根据指令的32位模式标志获取寄存器的字符串表示
     */
    private String getRegisterString(AArch64Operand operand) {
        if (isFloatOperation()) {
            // 浮点运算始终使用默认格式
            return operand.toString();
        }
        
            if (operand instanceof AArch64CPUReg) {
            AArch64CPUReg cpuReg = (AArch64CPUReg) operand;
            return use32BitMode ? cpuReg.to32BitString() : cpuReg.to64BitString();
            } else if (operand instanceof AArch64VirReg) {
            AArch64VirReg virReg = (AArch64VirReg) operand;
            return use32BitMode ? virReg.to32BitString() : virReg.to64BitString();
        } else {
            return operand.toString();
        }
    }

    public enum AArch64ShiftType {
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

    public enum AArch64BinaryType {
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
            return binaryTypeToString() + "\t" + getRegisterString(getDefReg()) + ", " + 
                   getRegisterString(getOperands().get(0)) + ", " + getOperands().get(1);
        } else if (instType == AArch64BinaryType.madd || instType == AArch64BinaryType.msub) {
            // MADD/MSUB指令有特殊格式：madd d, a, b, c （d = a*b + c）
            if (getOperands().size() >= 3) {
                return binaryTypeToString() + "\t" + getRegisterString(getDefReg()) + ", " +
                       getRegisterString(getOperands().get(1)) + ", " + getRegisterString(getOperands().get(2)) + ", " + getRegisterString(getOperands().get(0));
            } else {
                return binaryTypeToString() + "\t" + getRegisterString(getDefReg()) + ", " + 
                       getRegisterString(getOperands().get(0)) + ", " + getRegisterString(getOperands().get(1));
            }
        } else if (shiftBit == 0) {
            // 检查SUB指令的特殊情况：如果第一个操作数是立即数，第二个是寄存器，需要调整为NEG指令
            // 检查是否是0减某个数的情况
            if (instType == AArch64BinaryType.sub && 
                getOperands().size() == 2 && 
                getOperands().get(0) instanceof AArch64Imm && 
                ((AArch64Imm)getOperands().get(0)).getValue() == 0) {
                // sub dest, #0, src -> neg dest, src (相当于 dest = 0 - src = -src)
                // 直接使用标准的取负值指令
                return "sub\t" + getRegisterString(getDefReg()) + ", xzr, " + getRegisterString(getOperands().get(1));
            }
            
            // 检查是否两个操作数都是立即数（这是错误的ARM语法）
            if (getOperands().size() == 2 && 
                getOperands().get(0) instanceof AArch64Imm && 
                getOperands().get(1) instanceof AArch64Imm) {
                
                // 对于两个立即数的情况，先用mov指令加载第一个立即数到零寄存器xzr的别名
                // 实际上这种情况不应该发生，因为编译器应该在前面就处理了
                // 这里作为保护措施，使用mov + add的组合
                AArch64Imm firstImm = (AArch64Imm) getOperands().get(0);
                AArch64Imm secondImm = (AArch64Imm) getOperands().get(1);
                
                // 如果是简单的常量计算，直接计算结果
                if (instType == AArch64BinaryType.add) {
                    long result = firstImm.getValue() + secondImm.getValue();
                    return "mov\t" + getRegisterString(getDefReg()) + ", #" + result;
                } else if (instType == AArch64BinaryType.sub) {
                    long result = firstImm.getValue() - secondImm.getValue();
                    return "mov\t" + getRegisterString(getDefReg()) + ", #" + result;
                } else if (instType == AArch64BinaryType.mul) {
                    long result = firstImm.getValue() * secondImm.getValue();
                    return "mov\t" + getRegisterString(getDefReg()) + ", #" + result;
                } else {
                    // 对于其他操作，使用mov指令加载第一个操作数，然后执行运算
                    return "mov\t" + getRegisterString(getDefReg()) + ", " + getOperands().get(0) + "\n\t" +
                           binaryTypeToString() + "\t" + getRegisterString(getDefReg()) + ", " + getRegisterString(getDefReg()) + ", " + getOperands().get(1);
                }
            }
            
            return binaryTypeToString() + "\t" + getRegisterString(getDefReg()) + ", " +
                    getRegisterString(getOperands().get(0)) + ", " + getRegisterString(getOperands().get(1));
        } else {
            return binaryTypeToString() + "\t" + getRegisterString(getDefReg()) + ", " +
                    getRegisterString(getOperands().get(0)) + ", " + getRegisterString(getOperands().get(1)) + ", " + shiftTypeToString() + " #" + shiftBit;
        }
    }
} 
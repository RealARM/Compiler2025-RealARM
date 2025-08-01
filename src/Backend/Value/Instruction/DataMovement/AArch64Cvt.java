package Backend.Value.Instruction.DataMovement;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Collections;

/**
 * 表示ARMv8中的类型转换指令
 * 用于处理整数和浮点数之间的转换
 */
public class AArch64Cvt extends AArch64Instruction {
    private final CvtType conversionType;
    
    /**
     * 创建一个类型转换指令
     * @param srcReg 源寄存器
     * @param conversionType 转换类型
     * @param defReg 目标寄存器
     */
    public AArch64Cvt(AArch64Reg srcReg, CvtType conversionType, AArch64Reg defReg) {
        super(defReg, new ArrayList<>(Collections.singletonList(srcReg)));
        this.conversionType = conversionType;
    }
    
    /**
     * 表示不同类型的转换操作
     */
    public enum CvtType {
        // 浮点转整数
        FCVTZS,  // 浮点转有符号整数，向零舍入
        FCVTMS,  // 浮点转有符号整数，向负无穷舍入
        FCVTPS,  // 浮点转有符号整数，向正无穷舍入
        FCVTNS,  // 浮点转有符号整数，向最接近舍入
        
        // 无符号版本
        FCVTZU,  // 浮点转无符号整数，向零舍入
        
        // 整数转浮点
        SCVTF,   // 有符号整数转浮点
        UCVTF,   // 无符号整数转浮点
        
        // 浮点精度转换
        FCVT_S2D, // 单精度到双精度转换 (fcvt d, s)
        FCVT_D2S  // 双精度到单精度转换 (fcvt s, d)
    }
    
    /**
     * 获取源寄存器
     */
    public AArch64Reg getSrcReg() {
        return (AArch64Reg) getOperands().get(0);
    }
    
    /**
     * 将枚举转换为字符串表示
     */
    private String getConversionTypeString() {
        switch (conversionType) {
            case FCVT_S2D:
            case FCVT_D2S:
                return "fcvt";  // 两种FCVT类型都使用fcvt指令名
            default:
                return conversionType.name().toLowerCase();
        }
    }
    
    @Override
    public String toString() {
        // 获取源寄存器和目标寄存器的合适表示
        AArch64Reg srcReg = getSrcReg();
        AArch64Reg dstReg = getDefReg();
        
        String srcRegStr;
        String dstRegStr;
        
        // 根据转换类型确定使用寄存器的视图
        switch (conversionType) {
            case SCVTF:
            case UCVTF:
                // 整数转浮点，源是整数(w/x)，目标是浮点(s/d)
                srcRegStr = srcReg.toString();
                // 假设目标是双精度浮点
                if (dstReg instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
                    dstRegStr = ((Backend.Value.Operand.Register.AArch64FPUReg) dstReg).getDoubleName();
                } else {
                    dstRegStr = dstReg.toString();
                }
                break;
            case FCVTZS:
            case FCVTMS:
            case FCVTPS:
            case FCVTNS:
            case FCVTZU:
                // 浮点转整数，源是浮点(s/d)，目标是整数(w/x)
                if (srcReg instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
                    srcRegStr = ((Backend.Value.Operand.Register.AArch64FPUReg) srcReg).getDoubleName();
                } else {
                    srcRegStr = srcReg.toString();
                }
                dstRegStr = dstReg.toString();
                break;
            case FCVT_S2D:
                // 单精度到双精度转换：fcvt d, s
                if (srcReg instanceof Backend.Value.Operand.Register.AArch64FPUReg && 
                    dstReg instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
                    Backend.Value.Operand.Register.AArch64FPUReg srcFPU = (Backend.Value.Operand.Register.AArch64FPUReg) srcReg;
                    Backend.Value.Operand.Register.AArch64FPUReg dstFPU = (Backend.Value.Operand.Register.AArch64FPUReg) dstReg;
                    srcRegStr = srcFPU.getSingleName();  // 源是单精度
                    dstRegStr = dstFPU.getDoubleName();  // 目标是双精度
                } else {
                    srcRegStr = srcReg.toString();
                    dstRegStr = dstReg.toString();
                }
                break;
            case FCVT_D2S:
                // 双精度到单精度转换：fcvt s, d
                if (srcReg instanceof Backend.Value.Operand.Register.AArch64FPUReg && 
                    dstReg instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
                    Backend.Value.Operand.Register.AArch64FPUReg srcFPU = (Backend.Value.Operand.Register.AArch64FPUReg) srcReg;
                    Backend.Value.Operand.Register.AArch64FPUReg dstFPU = (Backend.Value.Operand.Register.AArch64FPUReg) dstReg;
                    srcRegStr = srcFPU.getDoubleName();  // 源是双精度
                    dstRegStr = dstFPU.getSingleName();  // 目标是单精度
                } else {
                    srcRegStr = srcReg.toString();
                    dstRegStr = dstReg.toString();
                }
                break;
            default:
                // 其他情况，使用默认名称
                srcRegStr = srcReg.toString();
                dstRegStr = dstReg.toString();
        }
        
        return getConversionTypeString() + "\t" + dstRegStr + ", " + srcRegStr;
    }
} 
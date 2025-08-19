package Backend.Value.Instruction.DataMovement;

import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;
import java.util.Collections;

public class AArch64Cvt extends AArch64Instruction {
    private final CvtType conversionType;
    
    public AArch64Cvt(AArch64Reg srcReg, CvtType conversionType, AArch64Reg defReg) {
        super(defReg, new ArrayList<>(Collections.singletonList(srcReg)));
        this.conversionType = conversionType;
    }
    
    public enum CvtType {
        FCVTZS,  // 浮点转有符号整数，向零舍入
        FCVTMS,  // 浮点转有符号整数，向负无穷舍入
        FCVTPS,  // 浮点转有符号整数，向正无穷舍入
        FCVTNS,  // 浮点转有符号整数，向最接近舍入
        FCVTZU,  // 浮点转无符号整数，向零舍入
        
        SCVTF,   // 有符号整数转浮点
        UCVTF,   // 无符号整数转浮点
        
        FCVT_S2D, // 单精度到双精度转换 (fcvt d, s)
        FCVT_D2S  // 双精度到单精度转换 (fcvt s, d)
    }
    
    public AArch64Reg getSrcReg() {
        return (AArch64Reg) getOperands().get(0);
    }
    
    private String getConversionTypeString() {
        switch (conversionType) {
            case FCVT_S2D:
            case FCVT_D2S:
                return "fcvt";
            default:
                return conversionType.name().toLowerCase();
        }
    }
    
    @Override
    public String toString() {
        AArch64Reg srcReg = getSrcReg();
        AArch64Reg dstReg = getDefReg();
        
        String srcRegStr;
        String dstRegStr;
        
        switch (conversionType) {
            case SCVTF:
            case UCVTF:
                // 整数转浮点，源是整数(w)，目标是浮点(s)
                srcRegStr = srcReg.to32BitString();  // 使用32位w寄存器
                if (dstReg instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
                    dstRegStr = ((Backend.Value.Operand.Register.AArch64FPUReg) dstReg).getSingleName();
                } else {
                    dstRegStr = dstReg.toString();
                }
                break;
            case FCVTZS:
            case FCVTMS:
            case FCVTPS:
            case FCVTNS:
            case FCVTZU:
                // 浮点转整数，源是浮点(s/d)，目标是整数(w)
                if (srcReg instanceof Backend.Value.Operand.Register.AArch64FPUReg) {
                    srcRegStr = ((Backend.Value.Operand.Register.AArch64FPUReg) srcReg).getSingleName();
                } else {
                    srcRegStr = srcReg.toString();
                }
                dstRegStr = dstReg.to32BitString();  // 使用32位w寄存器
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
                srcRegStr = srcReg.toString();
                dstRegStr = dstReg.toString();
        }
        
        return getConversionTypeString() + "\t" + dstRegStr + ", " + srcRegStr;
    }
} 
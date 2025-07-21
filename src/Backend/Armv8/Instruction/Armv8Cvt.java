package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Collections;

/**
 * 表示ARMv8中的类型转换指令
 * 用于处理整数和浮点数之间的转换
 */
public class Armv8Cvt extends Armv8Instruction {
    private final CvtType conversionType;
    
    /**
     * 创建一个类型转换指令
     * @param srcReg 源寄存器
     * @param conversionType 转换类型
     * @param defReg 目标寄存器
     */
    public Armv8Cvt(Armv8Reg srcReg, CvtType conversionType, Armv8Reg defReg) {
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
        FCVT     // 浮点精度转换(单精度到双精度或反之)
    }
    
    /**
     * 获取源寄存器
     */
    public Armv8Reg getSrcReg() {
        return (Armv8Reg) getOperands().get(0);
    }
    
    /**
     * 将枚举转换为字符串表示
     */
    private String getConversionTypeString() {
        return conversionType.name().toLowerCase();
    }
    
    @Override
    public String toString() {
        // 获取源寄存器和目标寄存器的合适表示
        Armv8Reg srcReg = getSrcReg();
        Armv8Reg dstReg = getDefReg();
        
        String srcRegStr;
        String dstRegStr;
        
        // 根据转换类型确定使用寄存器的视图
        switch (conversionType) {
            case SCVTF:
            case UCVTF:
                // 整数转浮点，源是整数(w/x)，目标是浮点(s/d)
                srcRegStr = srcReg.toString();
                // 假设目标是双精度浮点
                if (dstReg instanceof Backend.Armv8.Operand.Armv8FPUReg) {
                    dstRegStr = ((Backend.Armv8.Operand.Armv8FPUReg) dstReg).getDoubleName();
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
                if (srcReg instanceof Backend.Armv8.Operand.Armv8FPUReg) {
                    srcRegStr = ((Backend.Armv8.Operand.Armv8FPUReg) srcReg).getDoubleName();
                } else {
                    srcRegStr = srcReg.toString();
                }
                dstRegStr = dstReg.toString();
                break;
            default:
                // 其他情况，使用默认名称
                srcRegStr = srcReg.toString();
                dstRegStr = dstReg.toString();
        }
        
        return getConversionTypeString() + "\t" + dstRegStr + ", " + srcRegStr;
    }
} 
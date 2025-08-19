package Backend.Value.Operand.Register;

import Backend.Value.Base.AArch64Operand;

public class AArch64Reg extends AArch64Operand {
    @Override
    public String toString() {
        return "RootReg";
    }
    
    /**
     * 获取64位寄存器名称（默认实现）
     */
    public String to64BitString() {
        return toString();
    }
    
    /**
     * 获取32位寄存器名称（默认实现）
     */
    public String to32BitString() {
        return toString();
    }
} 
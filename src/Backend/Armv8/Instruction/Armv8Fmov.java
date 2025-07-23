package Backend.Armv8.Instruction;

import Backend.Armv8.Operand.Armv8FPUReg;
import Backend.Armv8.Operand.Armv8Operand;
import Backend.Armv8.Operand.Armv8Reg;

import java.util.ArrayList;
import java.util.Collections;

public class Armv8Fmov extends Armv8Instruction {
    private final boolean is32Bit; // 是否是32位(s)操作而非64位(d)
    private final boolean toFPU;  // true: CPU -> FPU, false: FPU -> CPU

    // FPU寄存器 <- 操作数(立即数或CPU寄存器)
    public Armv8Fmov(Armv8FPUReg destReg, Armv8Operand source, boolean is32Bit) {
        super(destReg, new ArrayList<>(Collections.singletonList(source)));
        this.is32Bit = is32Bit;
        this.toFPU = true;
    }

    // CPU寄存器 <- FPU寄存器
    public Armv8Fmov(Armv8Reg destReg, Armv8FPUReg source, boolean is32Bit) {
        super(destReg, new ArrayList<>(Collections.singletonList(source)));
        this.is32Bit = is32Bit;
        this.toFPU = false;
    }

    @Override
    public String toString() {
        // String size = is32Bit ? "s" : "d";
        String size = "d";
        
        if (toFPU) {
            // CPU/Imm -> FPU: fmov sN, xM 或 fmov dN, xM 或 fmov sN, #imm
            String src = getOperands().get(0).toString();
            // 如果源操作数是寄存器，统一使用64位x寄存器
            if (src.startsWith("x") || src.startsWith("w")) {
                // 不管是32位还是64位浮点操作，源都使用x寄存器
                if (src.startsWith("w")) {
                    src = "x" + src.substring(1);
                }
            }
            return "fmov\t" + size + getDefReg().toString().substring(1) + ", " + src;
        } else {
            // FPU -> CPU: fmov xN, sM 或 fmov xN, dM，始终使用x寄存器
            return "fmov\t" + "x" + getDefReg().toString().substring(1) + 
                  ", " + size + getOperands().get(0).toString().substring(1);
        }
    }
} 
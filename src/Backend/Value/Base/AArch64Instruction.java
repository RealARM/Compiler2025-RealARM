package Backend.Value.Base;

import Backend.Value.Operand.Register.AArch64Reg;

import java.util.ArrayList;

public class AArch64Instruction {
    public AArch64Reg defReg;
    public ArrayList<AArch64Operand> operands;

    public AArch64Instruction() {
        this.defReg = null;
        this.operands = new ArrayList<>();
    }

    public AArch64Instruction(AArch64Reg rd, ArrayList<AArch64Operand> operands) {
        this.operands = new ArrayList<>();
        this.operands.addAll(operands);
        this.defReg = rd;
    }

    public void replaceOperands(AArch64Reg armv8Reg1, AArch64Operand armv8Reg2) {
        if (armv8Reg2 == null) {
            System.err.println("警告: 尝试用null替换操作数 " + armv8Reg1);
            return; // 不进行替换
        }
        for(int i = 0; i < operands.size(); i++) {
            if(operands.get(i).equals(armv8Reg1)) {
                operands.get(i).getUsers().remove(this);
                armv8Reg2.getUsers().add(this);
                operands.set(i, armv8Reg2);
            }
        }
    }

    public void replaceDefReg(AArch64Reg armv8Reg) {
        defReg = armv8Reg;
        if (armv8Reg != null) {
            armv8Reg.getUsers().remove(this);
        }
        if (defReg != null) {
            defReg.getUsers().add(this);
        }
    }

    public ArrayList<AArch64Operand> getOperands() {
        return this.operands;
    }

    public AArch64Reg getDefReg() {
        return this.defReg;
    }
    
    // This inner class helps indicate returns in code
    public static class AArch64Ret extends AArch64Instruction {}
} 
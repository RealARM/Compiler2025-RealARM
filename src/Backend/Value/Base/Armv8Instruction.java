package Backend.Value.Base;

import Backend.Value.Operand.Register.Armv8Reg;

import java.util.ArrayList;

public class Armv8Instruction {
    public Armv8Reg defReg;
    public ArrayList<Armv8Operand> operands;

    public Armv8Instruction() {
        this.defReg = null;
        this.operands = new ArrayList<>();
    }

    public Armv8Instruction(Armv8Reg rd, ArrayList<Armv8Operand> operands) {
        this.operands = new ArrayList<>();
        this.operands.addAll(operands);
        this.defReg = rd;
    }

    public void replaceOperands(Armv8Reg armv8Reg1, Armv8Operand armv8Reg2) {
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

    public void replaceDefReg(Armv8Reg armv8Reg) {
        defReg = armv8Reg;
        if (armv8Reg != null) {
            armv8Reg.getUsers().remove(this);
        }
        if (defReg != null) {
            defReg.getUsers().add(this);
        }
    }

    public ArrayList<Armv8Operand> getOperands() {
        return this.operands;
    }

    public Armv8Reg getDefReg() {
        return this.defReg;
    }
    
    // This inner class helps indicate returns in code
    public static class Armv8Ret extends Armv8Instruction {}
} 
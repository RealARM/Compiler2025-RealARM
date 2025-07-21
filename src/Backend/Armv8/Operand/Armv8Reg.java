package Backend.Armv8.Operand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Armv8Reg extends Armv8Operand {
    // Base class for all registers (physical and virtual)
    private static int virtualRegCounter = 0;
    private static final List<Armv8Reg> physicalRegs = new ArrayList<>();
    private static final Map<Integer, Armv8Reg> virtualRegs = new HashMap<>();
    
    // ARM v8 has 31 general purpose registers (X0-X30)
    public static final int REG_X0 = 0;
    public static final int REG_X1 = 1;
    public static final int REG_X2 = 2;
    public static final int REG_X3 = 3;
    public static final int REG_X4 = 4;
    public static final int REG_X5 = 5;
    public static final int REG_X6 = 6;
    public static final int REG_X7 = 7;
    public static final int REG_X8 = 8;
    public static final int REG_X9 = 9;
    public static final int REG_X10 = 10;
    public static final int REG_X11 = 11;
    public static final int REG_X12 = 12;
    public static final int REG_X13 = 13;
    public static final int REG_X14 = 14;
    public static final int REG_X15 = 15;
    public static final int REG_X16 = 16;
    public static final int REG_X17 = 17;
    public static final int REG_X18 = 18;
    public static final int REG_X19 = 19;
    public static final int REG_X20 = 20;
    public static final int REG_X21 = 21;
    public static final int REG_X22 = 22;
    public static final int REG_X23 = 23;
    public static final int REG_X24 = 24;
    public static final int REG_X25 = 25;
    public static final int REG_X26 = 26;
    public static final int REG_X27 = 27;
    public static final int REG_X28 = 28;
    public static final int REG_X29 = 29; // Frame pointer (FP)
    public static final int REG_X30 = 30; // Link register (LR)
    public static final int REG_SP = 31;  // Stack pointer
    
    // Special registers
    public static final int REG_FP = REG_X29;
    public static final int REG_LR = REG_X30;
    
    // Register status
    private static final boolean[] regUsed = new boolean[32];
    
    // Register properties
    private final int regNum;
    private final boolean isPhysical;
    private final boolean isCallerSaved;
    
    static {
        // Initialize physical registers
        for (int i = 0; i < 31; i++) {
            physicalRegs.add(new Armv8Reg(i, true));
        }
        physicalRegs.add(new Armv8Reg(REG_SP, true));
        
        // Mark special registers as used by default
        regUsed[REG_SP] = true;
    }
    
    protected Armv8Reg(int regNum, boolean isPhysical) {
        this.regNum = regNum;
        this.isPhysical = isPhysical;
        
        // X0-X18 are caller-saved, X19-X30 are callee-saved
        this.isCallerSaved = regNum <= 18;
    }
    
    public int getRegNum() {
        return regNum;
    }
    
    public boolean isPhysical() {
        return isPhysical;
    }
    
    public boolean isCallerSaved() {
        return isCallerSaved;
    }
    
    @Override
    public String toString() {
        if (isPhysical) {
            if (regNum == REG_SP) {
                return "sp";
            } else {
                return "x" + regNum;
            }
        } else {
            return "v" + regNum;
        }
    }
    
    // Allocate a new physical register
    public static Armv8Reg allocateReg() {
        // Find an unused register
        for (int i = 0; i < regUsed.length - 1; i++) { // Skip SP
            // Skip registers reserved for specific purposes
            if (i == REG_FP || i == REG_LR) {
                continue;
            }
            
            if (!regUsed[i]) {
                regUsed[i] = true;
                return physicalRegs.get(i);
            }
        }
        
        // If no physical register available, allocate virtual register
        return allocateVirtualReg();
    }
    
    // Allocate a specific physical register
    public static Armv8Reg allocateReg(int regNum) {
        if (regNum < 0 || regNum >= regUsed.length) {
            throw new IllegalArgumentException("Invalid register number: " + regNum);
        }
        
        if (!regUsed[regNum]) {
            regUsed[regNum] = true;
        }
        
        return physicalRegs.get(regNum);
    }
    
    // Allocate a virtual register
    public static Armv8Reg allocateVirtualReg() {
        int vRegNum = virtualRegCounter++;
        Armv8Reg vReg = new Armv8Reg(vRegNum, false);
        virtualRegs.put(vRegNum, vReg);
        return vReg;
    }
    
    // Free a physical register
    public void free() {
        if (isPhysical && regNum != REG_SP && regNum != REG_FP && regNum != REG_LR) {
            regUsed[regNum] = false;
        }
    }
    
    // Check if a register is used
    public static boolean isRegUsed(int regNum) {
        return regUsed[regNum];
    }
    
    // Reset register allocation state
    public static void resetAllocator() {
        for (int i = 0; i < regUsed.length; i++) {
            // Only reset general purpose registers, keep special registers marked as used
            if (i != REG_SP && i != REG_FP && i != REG_LR) {
                regUsed[i] = false;
            }
        }
        virtualRegCounter = 0;
        virtualRegs.clear();
    }
    
    // Get a physical register by number
    public static Armv8Reg getPhysicalReg(int regNum) {
        if (regNum < 0 || regNum >= physicalRegs.size()) {
            throw new IllegalArgumentException("Invalid register number: " + regNum);
        }
        return physicalRegs.get(regNum);
    }
} 
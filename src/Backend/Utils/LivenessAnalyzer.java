package Backend.Utils;

import Backend.Structure.AArch64Block;
import Backend.Structure.AArch64Function;
import Backend.Value.Base.AArch64Instruction;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Instruction.ControlFlow.AArch64BlrCall;
import Backend.Value.Instruction.ControlFlow.AArch64Call;
import Backend.Value.Operand.Register.AArch64PhyReg;
import Backend.Value.Operand.Register.AArch64Reg;

import java.util.*;

/**
 * 活跃性分析器
 * 用于分析每个基本块中变量的活跃性信息
 */
public class LivenessAnalyzer {
    
    /**
     * 活跃性信息
     * 包含每个基本块的定义(def)、使用(use)、活跃入口(liveIn)、活跃出口(liveOut)信息
     */
    public static class LivenessInfo {
        private final LinkedHashSet<AArch64Reg> defSet = new LinkedHashSet<>();    // 在此块中定义的寄存器
        private final LinkedHashSet<AArch64Reg> useSet = new LinkedHashSet<>();    // 在此块中使用的寄存器
        private final LinkedHashSet<AArch64Reg> liveIn = new LinkedHashSet<>();    // 活跃入口
        private final LinkedHashSet<AArch64Reg> liveOut = new LinkedHashSet<>();   // 活跃出口

        public LinkedHashSet<AArch64Reg> getDefSet() { return defSet; }
        public LinkedHashSet<AArch64Reg> getUseSet() { return useSet; }
        public LinkedHashSet<AArch64Reg> getLiveIn() { return liveIn; }
        public LinkedHashSet<AArch64Reg> getLiveOut() { return liveOut; }
        
        public void setLiveOut(LinkedHashSet<AArch64Reg> newLiveOut) {
            liveOut.clear();
            liveOut.addAll(newLiveOut);
        }
        
        public void addDef(AArch64Reg reg) {
            if (reg != null && !(reg instanceof AArch64PhyReg)) {
                defSet.add(reg);
            }
        }
        
        public void addUse(AArch64Reg reg) {
            if (reg != null && !(reg instanceof AArch64PhyReg)) {
                useSet.add(reg);
            }
        }
    }
    
    /**
     * 对整个函数进行活跃性分析
     * @param function 要分析的函数
     * @return 每个基本块的活跃性信息映射
     */
    public static LinkedHashMap<AArch64Block, LivenessInfo> analyzeLiveness(AArch64Function function) {
        LinkedHashMap<AArch64Block, LivenessInfo> livenessMap = new LinkedHashMap<>();
        
        // 初始化每个基本块的活跃性信息
        for (AArch64Block block : function.getBlocks()) {
            livenessMap.put(block, new LivenessInfo());
        }
        
        // 计算每个基本块的def和use集合
        for (AArch64Block block : function.getBlocks()) {
            computeDefUse(block, livenessMap.get(block));
        }
        
        // 迭代计算活跃入口和活跃出口
        boolean changed = true;
        while (changed) {
            changed = false;
            
            // 反向遍历基本块
            for (int i = function.getBlocks().size() - 1; i >= 0; i--) {
                AArch64Block block = function.getBlocks().get(i);
                LivenessInfo info = livenessMap.get(block);
                
                // 计算新的liveOut = ∪(liveIn of successors)
                LinkedHashSet<AArch64Reg> newLiveOut = new LinkedHashSet<>();
                for (AArch64Block successor : block.getSuccs()) {
                    newLiveOut.addAll(livenessMap.get(successor).getLiveIn());
                }
                
                // 计算新的liveIn = use ∪ (liveOut - def)
                LinkedHashSet<AArch64Reg> newLiveIn = new LinkedHashSet<>(info.getUseSet());
                LinkedHashSet<AArch64Reg> temp = new LinkedHashSet<>(newLiveOut);
                temp.removeAll(info.getDefSet());
                newLiveIn.addAll(temp);
                
                // 检查是否有变化
                if (!newLiveOut.equals(info.getLiveOut()) || !newLiveIn.equals(info.getLiveIn())) {
                    changed = true;
                    info.setLiveOut(newLiveOut);
                    info.getLiveIn().clear();
                    info.getLiveIn().addAll(newLiveIn);
                }
            }
        }
        
        return livenessMap;
    }
    
    /**
     * 计算单个基本块的def和use集合
     */
    private static void computeDefUse(AArch64Block block, LivenessInfo info) {
        LinkedHashSet<AArch64Reg> tempDef = new LinkedHashSet<>();
        
        for (AArch64Instruction instruction : block.getInstructions()) {
            // 处理指令使用的寄存器（在定义之前使用的寄存器）
            for (AArch64Operand operand : instruction.getOperands()) {
                if (operand instanceof AArch64Reg) {
                    AArch64Reg reg = (AArch64Reg) operand;
                    if (!tempDef.contains(reg)) {
                        info.addUse(reg);
                    }
                }
            }
            
            // 处理指令定义的寄存器
            AArch64Reg defReg = instruction.getDefReg();
            if (defReg != null) {
                info.addDef(defReg);
                tempDef.add(defReg);
            }
            
            // 特殊处理一些指令
            if (instruction instanceof AArch64Call) {
                // 函数调用会修改调用者保存寄存器
                handleCallInstruction((AArch64Call) instruction, info);
            } else if (instruction instanceof AArch64BlrCall) {
                // 间接函数调用
                handleBlrCallInstruction((AArch64BlrCall) instruction, info);
            }
        }
    }
    
    /**
     * 处理函数调用指令的特殊情况
     * 函数调用会隐式定义调用者保存寄存器
     */
    private static void handleCallInstruction(AArch64Call callInst, LivenessInfo info) {
        // 函数调用隐式定义返回值寄存器
        if (callInst.getDefReg() != null) {
            info.addDef(callInst.getDefReg());
        }
        
        // 添加调用中使用的寄存器
        for (AArch64Reg usedReg : callInst.getUsedRegs()) {
            info.addUse(usedReg);
        }
    }
    
    /**
     * 处理间接函数调用指令的特殊情况
     */
    private static void handleBlrCallInstruction(AArch64BlrCall blrCallInst, LivenessInfo info) {
        // 间接函数调用隐式定义返回值寄存器
        if (blrCallInst.getDefReg() != null) {
            info.addDef(blrCallInst.getDefReg());
        }
        
        // 添加调用中使用的寄存器
        for (AArch64Reg usedReg : blrCallInst.getUsedRegs()) {
            info.addUse(usedReg);
        }
    }
    
    /**
     * 获取指令中所有使用的寄存器
     */
    public static LinkedHashSet<AArch64Reg> getUsedRegisters(AArch64Instruction instruction) {
        LinkedHashSet<AArch64Reg> usedRegs = new LinkedHashSet<>();
        
        for (AArch64Operand operand : instruction.getOperands()) {
            if (operand instanceof AArch64Reg) {
                usedRegs.add((AArch64Reg) operand);
            }
        }
        
        return usedRegs;
    }
    
    /**
     * 获取指令定义的寄存器
     */
    public static AArch64Reg getDefinedRegister(AArch64Instruction instruction) {
        return instruction.getDefReg();
    }
} 
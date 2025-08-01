package Backend.Utils;

import Backend.Structure.Armv8Block;
import Backend.Structure.Armv8Function;
import Backend.Value.Base.Armv8Instruction;
import Backend.Value.Base.Armv8Operand;
import Backend.Value.Instruction.ControlFlow.Armv8BlrCall;
import Backend.Value.Instruction.ControlFlow.Armv8Call;
import Backend.Value.Operand.Register.Armv8PhyReg;
import Backend.Value.Operand.Register.Armv8Reg;

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
        private final LinkedHashSet<Armv8Reg> defSet = new LinkedHashSet<>();    // 在此块中定义的寄存器
        private final LinkedHashSet<Armv8Reg> useSet = new LinkedHashSet<>();    // 在此块中使用的寄存器
        private final LinkedHashSet<Armv8Reg> liveIn = new LinkedHashSet<>();    // 活跃入口
        private final LinkedHashSet<Armv8Reg> liveOut = new LinkedHashSet<>();   // 活跃出口

        public LinkedHashSet<Armv8Reg> getDefSet() { return defSet; }
        public LinkedHashSet<Armv8Reg> getUseSet() { return useSet; }
        public LinkedHashSet<Armv8Reg> getLiveIn() { return liveIn; }
        public LinkedHashSet<Armv8Reg> getLiveOut() { return liveOut; }
        
        public void setLiveOut(LinkedHashSet<Armv8Reg> newLiveOut) {
            liveOut.clear();
            liveOut.addAll(newLiveOut);
        }
        
        public void addDef(Armv8Reg reg) {
            if (reg != null && !(reg instanceof Armv8PhyReg)) {
                defSet.add(reg);
            }
        }
        
        public void addUse(Armv8Reg reg) {
            if (reg != null && !(reg instanceof Armv8PhyReg)) {
                useSet.add(reg);
            }
        }
    }
    
    /**
     * 对整个函数进行活跃性分析
     * @param function 要分析的函数
     * @return 每个基本块的活跃性信息映射
     */
    public static LinkedHashMap<Armv8Block, LivenessInfo> analyzeLiveness(Armv8Function function) {
        LinkedHashMap<Armv8Block, LivenessInfo> livenessMap = new LinkedHashMap<>();
        
        // 初始化每个基本块的活跃性信息
        for (Armv8Block block : function.getBlocks()) {
            livenessMap.put(block, new LivenessInfo());
        }
        
        // 计算每个基本块的def和use集合
        for (Armv8Block block : function.getBlocks()) {
            computeDefUse(block, livenessMap.get(block));
        }
        
        // 迭代计算活跃入口和活跃出口
        boolean changed = true;
        while (changed) {
            changed = false;
            
            // 反向遍历基本块
            for (int i = function.getBlocks().size() - 1; i >= 0; i--) {
                Armv8Block block = function.getBlocks().get(i);
                LivenessInfo info = livenessMap.get(block);
                
                // 计算新的liveOut = ∪(liveIn of successors)
                LinkedHashSet<Armv8Reg> newLiveOut = new LinkedHashSet<>();
                for (Armv8Block successor : block.getSuccs()) {
                    newLiveOut.addAll(livenessMap.get(successor).getLiveIn());
                }
                
                // 计算新的liveIn = use ∪ (liveOut - def)
                LinkedHashSet<Armv8Reg> newLiveIn = new LinkedHashSet<>(info.getUseSet());
                LinkedHashSet<Armv8Reg> temp = new LinkedHashSet<>(newLiveOut);
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
    private static void computeDefUse(Armv8Block block, LivenessInfo info) {
        LinkedHashSet<Armv8Reg> tempDef = new LinkedHashSet<>();
        
        for (Armv8Instruction instruction : block.getInstructions()) {
            // 处理指令使用的寄存器（在定义之前使用的寄存器）
            for (Armv8Operand operand : instruction.getOperands()) {
                if (operand instanceof Armv8Reg) {
                    Armv8Reg reg = (Armv8Reg) operand;
                    if (!tempDef.contains(reg)) {
                        info.addUse(reg);
                    }
                }
            }
            
            // 处理指令定义的寄存器
            Armv8Reg defReg = instruction.getDefReg();
            if (defReg != null) {
                info.addDef(defReg);
                tempDef.add(defReg);
            }
            
            // 特殊处理一些指令
            if (instruction instanceof Armv8Call) {
                // 函数调用会修改调用者保存寄存器
                handleCallInstruction((Armv8Call) instruction, info);
            } else if (instruction instanceof Armv8BlrCall) {
                // 间接函数调用
                handleBlrCallInstruction((Armv8BlrCall) instruction, info);
            }
        }
    }
    
    /**
     * 处理函数调用指令的特殊情况
     * 函数调用会隐式定义调用者保存寄存器
     */
    private static void handleCallInstruction(Armv8Call callInst, LivenessInfo info) {
        // 函数调用隐式定义返回值寄存器
        if (callInst.getDefReg() != null) {
            info.addDef(callInst.getDefReg());
        }
        
        // 添加调用中使用的寄存器
        for (Armv8Reg usedReg : callInst.getUsedRegs()) {
            info.addUse(usedReg);
        }
    }
    
    /**
     * 处理间接函数调用指令的特殊情况
     */
    private static void handleBlrCallInstruction(Armv8BlrCall blrCallInst, LivenessInfo info) {
        // 间接函数调用隐式定义返回值寄存器
        if (blrCallInst.getDefReg() != null) {
            info.addDef(blrCallInst.getDefReg());
        }
        
        // 添加调用中使用的寄存器
        for (Armv8Reg usedReg : blrCallInst.getUsedRegs()) {
            info.addUse(usedReg);
        }
    }
    
    /**
     * 获取指令中所有使用的寄存器
     */
    public static LinkedHashSet<Armv8Reg> getUsedRegisters(Armv8Instruction instruction) {
        LinkedHashSet<Armv8Reg> usedRegs = new LinkedHashSet<>();
        
        for (Armv8Operand operand : instruction.getOperands()) {
            if (operand instanceof Armv8Reg) {
                usedRegs.add((Armv8Reg) operand);
            }
        }
        
        return usedRegs;
    }
    
    /**
     * 获取指令定义的寄存器
     */
    public static Armv8Reg getDefinedRegister(Armv8Instruction instruction) {
        return instruction.getDefReg();
    }
} 
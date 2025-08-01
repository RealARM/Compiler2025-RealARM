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
 * 变量活跃性分析器
 * 基于数据流分析的变量生存期计算器
 * 支持控制流图的前向和后向数据流分析
 */
public class LivenessAnalyzer {
    
    /**
     * 基本块活跃性数据
     */
    public static class LivenessInfo {
        private final LinkedHashSet<AArch64Reg> definitionsInBlock = new LinkedHashSet<>();     // 块内定义的变量
        private final LinkedHashSet<AArch64Reg> referencesInBlock = new LinkedHashSet<>();      // 块内引用的变量
        private final LinkedHashSet<AArch64Reg> entryLiveVariables = new LinkedHashSet<>();     // 块入口活跃变量
        private final LinkedHashSet<AArch64Reg> exitLiveVariables = new LinkedHashSet<>();      // 块出口活跃变量

        public LinkedHashSet<AArch64Reg> getDefSet() { return definitionsInBlock; }
        public LinkedHashSet<AArch64Reg> getUseSet() { return referencesInBlock; }
        public LinkedHashSet<AArch64Reg> getLiveIn() { return entryLiveVariables; }
        public LinkedHashSet<AArch64Reg> getLiveOut() { return exitLiveVariables; }
        
        public void setLiveOut(LinkedHashSet<AArch64Reg> newExitLiveVars) {
            exitLiveVariables.clear();
            exitLiveVariables.addAll(newExitLiveVars);
        }
        
        public void recordDefinition(AArch64Reg register) {
            if (register != null && isVirtualRegister(register)) {
                definitionsInBlock.add(register);
            }
        }
        
        public void recordReference(AArch64Reg register) {
            if (register != null && isVirtualRegister(register)) {
                referencesInBlock.add(register);
            }
        }
        
        private boolean isVirtualRegister(AArch64Reg register) {
            return !(register instanceof AArch64PhyReg);
        }
    }
    
    /**
     * 活跃性分析器上下文
     */
    private static class AnalysisContext {
        private final AArch64Function targetFunction;
        private final LinkedHashMap<AArch64Block, LivenessInfo> blockLivenessData;
        
        AnalysisContext(AArch64Function function) {
            this.targetFunction = function;
            this.blockLivenessData = new LinkedHashMap<>();
            initializeBlockData();
        }
        
        private void initializeBlockData() {
            for (AArch64Block block : targetFunction.getBlocks()) {
                blockLivenessData.put(block, new LivenessInfo());
            }
        }
        
        LinkedHashMap<AArch64Block, LivenessInfo> getResults() {
            return blockLivenessData;
        }
    }
    
    /**
     * 执行变量活跃性分析
     * @param function 目标函数
     * @return 每个基本块的活跃性数据映射
     */
    public static LinkedHashMap<AArch64Block, LivenessInfo> analyzeLiveness(AArch64Function function) {
        AnalysisContext context = new AnalysisContext(function);
        
        // 阶段1：计算局部定义-引用集合
        computeLocalDefinitionReferenceSets(context);
        
        // 阶段2：执行数据流固定点计算
        performDataflowFixedPointComputation(context);
        
        return context.getResults();
    }
    
    /**
     * 计算每个基本块的局部定义和引用集合
     */
    private static void computeLocalDefinitionReferenceSets(AnalysisContext context) {
        for (AArch64Block block : context.targetFunction.getBlocks()) {
            LivenessInfo blockData = context.blockLivenessData.get(block);
            analyzeInstructionSequence(block, blockData);
        }
    }
    
    /**
     * 执行数据流固定点迭代计算
     */
    private static void performDataflowFixedPointComputation(AnalysisContext context) {
        boolean hasConverged = false;
        
        while (!hasConverged) {
            hasConverged = true;
            
            // 反向遍历基本块进行后向数据流分析
            List<AArch64Block> blocks = context.targetFunction.getBlocks();
            for (int blockIndex = blocks.size() - 1; blockIndex >= 0; blockIndex--) {
                AArch64Block currentBlock = blocks.get(blockIndex);
                LivenessInfo currentBlockData = context.blockLivenessData.get(currentBlock);
                
                // 计算出口活跃变量集合：所有后继块入口活跃变量的并集
                LinkedHashSet<AArch64Reg> newExitLiveVars = computeExitLiveVariables(
                    currentBlock, context.blockLivenessData);
                
                // 计算入口活跃变量集合：use ∪ (liveOut - def)
                LinkedHashSet<AArch64Reg> newEntryLiveVars = computeEntryLiveVariables(
                    currentBlockData, newExitLiveVars);
                
                // 检查是否达到固定点
                if (detectChangesInLivenessData(currentBlockData, newEntryLiveVars, newExitLiveVars)) {
                    hasConverged = false;
                    updateLivenessData(currentBlockData, newEntryLiveVars, newExitLiveVars);
                }
            }
        }
    }
    
    /**
     * 计算基本块出口的活跃变量集合
     */
    private static LinkedHashSet<AArch64Reg> computeExitLiveVariables(
            AArch64Block block, LinkedHashMap<AArch64Block, LivenessInfo> livenessData) {
        LinkedHashSet<AArch64Reg> exitLiveVars = new LinkedHashSet<>();
        
        for (AArch64Block successorBlock : block.getSuccs()) {
            LivenessInfo successorData = livenessData.get(successorBlock);
            exitLiveVars.addAll(successorData.getLiveIn());
        }
        
        return exitLiveVars;
    }
    
    /**
     * 计算基本块入口的活跃变量集合
     */
    private static LinkedHashSet<AArch64Reg> computeEntryLiveVariables(
            LivenessInfo blockData, LinkedHashSet<AArch64Reg> exitLiveVars) {
        LinkedHashSet<AArch64Reg> entryLiveVars = new LinkedHashSet<>(blockData.getUseSet());
        LinkedHashSet<AArch64Reg> propagatedVars = new LinkedHashSet<>(exitLiveVars);
        propagatedVars.removeAll(blockData.getDefSet());
        entryLiveVars.addAll(propagatedVars);
        
        return entryLiveVars;
    }
    
    /**
     * 检测活跃性数据是否发生变化
     */
    private static boolean detectChangesInLivenessData(LivenessInfo blockData,
            LinkedHashSet<AArch64Reg> newEntryVars, LinkedHashSet<AArch64Reg> newExitVars) {
        return !newExitVars.equals(blockData.getLiveOut()) || 
               !newEntryVars.equals(blockData.getLiveIn());
    }
    
    /**
     * 更新活跃性数据
     */
    private static void updateLivenessData(LivenessInfo blockData,
            LinkedHashSet<AArch64Reg> newEntryVars, LinkedHashSet<AArch64Reg> newExitVars) {
        blockData.setLiveOut(newExitVars);
        blockData.getLiveIn().clear();
        blockData.getLiveIn().addAll(newEntryVars);
    }
    
    /**
     * 分析指令序列以计算局部定义和引用集合
     */
    private static void analyzeInstructionSequence(AArch64Block block, LivenessInfo blockData) {
        LinkedHashSet<AArch64Reg> localDefinitions = new LinkedHashSet<>();
        
        for (AArch64Instruction instruction : block.getInstructions()) {
            // 处理指令的操作数引用（必须在定义之前处理）
            processInstructionOperandReferences(instruction, blockData, localDefinitions);
            
            // 处理指令的定义
            processInstructionDefinition(instruction, blockData, localDefinitions);
            
            // 处理特殊指令类型
            handleSpecialInstructionTypes(instruction, blockData);
        }
    }
    
    /**
     * 处理指令操作数引用
     */
    private static void processInstructionOperandReferences(AArch64Instruction instruction, 
                                                          LivenessInfo blockData, 
                                                          LinkedHashSet<AArch64Reg> localDefinitions) {
        for (AArch64Operand operand : instruction.getOperands()) {
            if (operand instanceof AArch64Reg) {
                AArch64Reg register = (AArch64Reg) operand;
                // 只有在当前块中未被定义的寄存器才算作引用
                if (!localDefinitions.contains(register)) {
                    blockData.recordReference(register);
                }
            }
        }
    }
    
    /**
     * 处理指令定义
     */
    private static void processInstructionDefinition(AArch64Instruction instruction, 
                                                    LivenessInfo blockData, 
                                                    LinkedHashSet<AArch64Reg> localDefinitions) {
        AArch64Reg definedRegister = instruction.getDefReg();
        if (definedRegister != null) {
            blockData.recordDefinition(definedRegister);
            localDefinitions.add(definedRegister);
        }
    }
    
    /**
     * 处理特殊指令类型
     */
    private static void handleSpecialInstructionTypes(AArch64Instruction instruction, LivenessInfo blockData) {
        if (instruction instanceof AArch64Call) {
            processDirectFunctionCall((AArch64Call) instruction, blockData);
        } else if (instruction instanceof AArch64BlrCall) {
            processIndirectFunctionCall((AArch64BlrCall) instruction, blockData);
        }
    }
    
    /**
     * 处理直接函数调用指令
     * 函数调用会隐式修改调用者保存寄存器
     */
    private static void processDirectFunctionCall(AArch64Call callInstruction, LivenessInfo blockData) {
        // 函数调用隐式定义返回值寄存器
        if (callInstruction.getDefReg() != null) {
            blockData.recordDefinition(callInstruction.getDefReg());
        }
        
        // 添加调用中显式使用的寄存器
        for (AArch64Reg usedRegister : callInstruction.getUsedRegs()) {
            blockData.recordReference(usedRegister);
        }
    }
    
    /**
     * 处理间接函数调用指令
     */
    private static void processIndirectFunctionCall(AArch64BlrCall blrCallInstruction, LivenessInfo blockData) {
        // 间接函数调用隐式定义返回值寄存器
        if (blrCallInstruction.getDefReg() != null) {
            blockData.recordDefinition(blrCallInstruction.getDefReg());
        }
        
        // 添加调用中显式使用的寄存器
        for (AArch64Reg usedRegister : blrCallInstruction.getUsedRegs()) {
            blockData.recordReference(usedRegister);
        }
    }
    
    // === 公共实用方法 ===
    
    /**
     * 提取指令中所有被引用的寄存器
     */
    public static LinkedHashSet<AArch64Reg> extractReferencedRegisters(AArch64Instruction instruction) {
        LinkedHashSet<AArch64Reg> referencedRegisters = new LinkedHashSet<>();
        
        for (AArch64Operand operand : instruction.getOperands()) {
            if (operand instanceof AArch64Reg) {
                referencedRegisters.add((AArch64Reg) operand);
            }
        }
        
        return referencedRegisters;
    }
    
    /**
     * 提取指令定义的寄存器
     */
    public static AArch64Reg extractDefinedRegister(AArch64Instruction instruction) {
        return instruction.getDefReg();
    }
} 
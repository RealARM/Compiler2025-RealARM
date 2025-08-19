package Backend.RegisterManager;

import Backend.Structure.AArch64Block;
import Backend.Structure.AArch64Function;
import Backend.Value.Base.*;
import Backend.Value.Instruction.Arithmetic.*;
import Backend.Value.Instruction.Memory.*;
import Backend.Value.Operand.Constant.*;
import Backend.Value.Operand.Register.*;

import java.util.*;

/**
 * AArch64寄存器分配器
 * 基于图着色算法的寄存器分配实现
 * 支持整型和浮点寄存器的独立分配
 */
public class RegisterAllocator {
    
    private final AArch64Function targetFunction;
    private RegisterAllocationState currentState;
    
    // 寄存器类型配置
    private static final int INTEGER_REGISTER_COUNT = 8;   // 整型寄存器数量 (x8-x15)
    private static final int FLOATING_REGISTER_COUNT = 24; // 浮点寄存器数量 (v8-v31)
    private static final int MAX_ALLOCATION_ROUNDS = 5;    // 最大分配轮次
    
    public RegisterAllocator(AArch64Function function) {
        this.targetFunction = function;
    }
    

    public void allocateRegisters() {
        // System.out.println("\n===== 开始为函数 " + targetFunction.getName() + " 分配寄存器 =====");
        
        // 分别处理整型和浮点寄存器
        performAllocationForType(false); // 整型寄存器
        performAllocationForType(true);  // 浮点寄存器
        
        // System.out.println("===== 函数 " + targetFunction.getName() + " 寄存器分配完成 =====\n");
    }
    

    private void performAllocationForType(boolean isFloatingPoint) {
        String registerTypeName = isFloatingPoint ? "浮点" : "整型";
        
        boolean requiresSpillHandling = true;
        int allocationRound = 0;
        
        while (requiresSpillHandling && allocationRound < MAX_ALLOCATION_ROUNDS) {
            allocationRound++;
            // System.out.println("第 " + allocationRound + " 轮分配 " + registerTypeName + " 寄存器");
            
            // 创建新的分配状态
            currentState = new RegisterAllocationState(targetFunction, isFloatingPoint);
            
            // 执行分配算法的各个阶段
            if (executeGraphColoringAlgorithm()) {
                // 分配成功，应用结果
                applyAllocationResults();
                requiresSpillHandling = false;
            } else {
                // 需要处理溢出
                handleRegisterSpilling();
            }
        }
        
        if (allocationRound >= MAX_ALLOCATION_ROUNDS) {
            System.err.println("警告: " + registerTypeName + " 寄存器分配达到最大轮数，可能存在问题");
        }
    }
    
    /**
     * 执行图着色算法的主要流程
     * @return true表示分配成功，false表示需要溢出处理
     */
    private boolean executeGraphColoringAlgorithm() {
        // 创建各个模块
        InterferenceGraphBuilder graphBuilder = new InterferenceGraphBuilder(currentState);
        GraphColoringEngine coloringEngine = new GraphColoringEngine(currentState);
        
        // 阶段1：收集候选寄存器
        graphBuilder.collectCandidateRegisters();
        
        // 阶段2：构建干扰图
        graphBuilder.buildInterferenceGraph();
        
        // 阶段3：分类节点到工作列表
        coloringEngine.categorizeNodesToWorklists();
        
        // 阶段4：执行图简化主循环
        coloringEngine.performGraphSimplificationLoop();
        
        // 阶段5：尝试分配颜色
        return coloringEngine.attemptColorAssignment();
    }
    


    

    private void applyAllocationResults() {
        // System.out.println("开始应用寄存器分配结果...");
        int replacementCount = 0;
        
        // 调试：打印所有虚拟寄存器的颜色分配
        // System.out.println("=== 寄存器颜色分配结果 ===");
        // for (Map.Entry<AArch64Reg, Integer> entry : currentState.getRegisterColors().entrySet()) {
        //     AArch64Reg reg = entry.getKey();
        //     Integer color = entry.getValue();
        //     if (reg instanceof AArch64VirReg) {
        //         AArch64VirReg virReg = (AArch64VirReg) reg;
        //         if (virReg.isFloat() == currentState.isFloatingPoint()) {
        //             AArch64PhyReg physReg = mapColorToPhysicalRegister(virReg, color);
        //             System.out.println(virReg + " -> 颜色" + color + " -> " + physReg);
        //         }
        //     }
        // }
        // System.out.println("=== 开始替换指令 ===");
        
        for (AArch64Block block : targetFunction.getBlocks()) {
            for (AArch64Instruction instruction : block.getInstructions()) {
                // 调试：打印所有指令的寄存器分配前状态
                // if (instruction.toString().contains("mov") || instruction.toString().contains("cmp")) {
                //     System.out.println("分配前指令: " + instruction);
                //     if (instruction.getDefReg() instanceof AArch64VirReg) {
                //         System.out.println("  定义寄存器: " + instruction.getDefReg());
                //     }
                //     for (AArch64Operand op : instruction.getOperands()) {
                //         if (op instanceof AArch64VirReg) {
                //             System.out.println("  操作数: " + op);
                //         }
                //     }
                // }
                
                // 替换定义寄存器
                if (instruction.getDefReg() instanceof AArch64VirReg) {
                    AArch64VirReg virtualReg = (AArch64VirReg) instruction.getDefReg();
                    if (virtualReg.isFloat() == currentState.isFloatingPoint() && 
                        currentState.getRegisterColors().containsKey(virtualReg)) {
                        AArch64PhyReg physicalReg = mapColorToPhysicalRegister(virtualReg, 
                                                                             currentState.getRegisterColors().get(virtualReg));
                        if (physicalReg != null) {
                            instruction.replaceDefReg(physicalReg);
                            replacementCount++;
                        }
                    }
                }
                
                // 替换操作数寄存器
                List<AArch64Operand> operandList = instruction.getOperands();
                for (int i = 0; i < operandList.size(); i++) {
                    AArch64Operand operand = operandList.get(i);
                    if (operand instanceof AArch64VirReg) {
                        AArch64VirReg virtualReg = (AArch64VirReg) operand;
                        if (virtualReg.isFloat() == currentState.isFloatingPoint()) {
                            if (currentState.getRegisterColors().containsKey(virtualReg)) {
                                AArch64PhyReg physicalReg = mapColorToPhysicalRegister(virtualReg, 
                                                                                     currentState.getRegisterColors().get(virtualReg));
                                if (physicalReg != null) {
                                    instruction.replaceOperands(virtualReg, physicalReg);
                                    replacementCount++;
                                } else {
                                    System.err.println("警告: 无法为虚拟寄存器 " + virtualReg + " 创建物理寄存器映射");
                                }
                            } else {
                                System.err.println("警告: 虚拟寄存器 " + virtualReg + " 没有分配颜色，指令: " + instruction);
                            }
                        }
                    }
                }
                
                // 调试：打印分配后的指令状态
                // if (instruction.toString().contains("mov") || instruction.toString().contains("cmp")) {
                //     System.out.println("分配后指令: " + instruction);
                // }
            }
        }
        
        // System.out.println("寄存器分配结果应用完成，替换了 " + replacementCount + " 个寄存器");
    }
    

    private void handleRegisterSpilling() {
        // System.out.println("处理寄存器溢出，数量: " + currentState.getSpilledNodes().size());
        
        for (AArch64Operand spillOperand : currentState.getSpilledNodes()) {
            if (!(spillOperand instanceof AArch64VirReg)) continue;
            
            AArch64VirReg spillRegister = (AArch64VirReg) spillOperand;
            if (spillRegister.isFloat() != currentState.isFloatingPoint()) continue;
            
            long stackPosition = targetFunction.getStackSize();
            targetFunction.addStack(null, 8L);
            
            // System.out.println("为溢出寄存器 " + spillRegister + " 分配栈位置: " + stackPosition);
            rewriteSpilledRegisterAccesses(spillRegister, stackPosition);
        }
        
        // System.out.println("溢出处理完成");
    }

    

    private AArch64PhyReg mapColorToPhysicalRegister(AArch64VirReg virtualRegister, int color) {
        AArch64PhyReg physicalRegister = null;
        
        if (virtualRegister.isFloat()) {
            // 浮点寄存器: v8-v31
            if (color >= 0 && color < FLOATING_REGISTER_COUNT) {
                physicalRegister = AArch64FPUReg.getAArch64FloatReg(color + 8);
            }
        } else {
            // 整型寄存器: x19-x26
            if (color >= 0 && color < INTEGER_REGISTER_COUNT) {
                physicalRegister = AArch64CPUReg.getAArch64CPUReg(color + 19);
            }
        }
        
        if (physicalRegister == null) {
            System.err.println("无法为寄存器 " + virtualRegister + " 映射物理寄存器，颜色: " + color);
        }
        
        return physicalRegister;
    }
    

    private void rewriteSpilledRegisterAccesses(AArch64VirReg spilledRegister, long stackOffset) {
        for (AArch64Block block : targetFunction.getBlocks()) {
            List<AArch64Instruction> instructionList = new ArrayList<>(block.getInstructions());
            
            for (int i = 0; i < instructionList.size(); i++) {
                AArch64Instruction instruction = instructionList.get(i);
                boolean containsSpilledUse = false;
                boolean containsSpilledDef = false;
                
                // 检查是否使用了溢出寄存器
                for (AArch64Operand operand : instruction.getOperands()) {
                    if (operand.equals(spilledRegister)) {
                        containsSpilledUse = true;
                        break;
                    }
                }
                
                // 检查是否定义了溢出寄存器
                if (spilledRegister.equals(instruction.getDefReg())) {
                    containsSpilledDef = true;
                }
                
                if (containsSpilledUse || containsSpilledDef) {
                    if (containsSpilledUse) {
                        // 为使用插入加载指令
                        insertLoadInstructionForSpilledUse(block, instruction, spilledRegister, 
                        stackOffset + instruction.getCalleeParamOffset());
                    }
                    
                    if (containsSpilledDef) {
                        // 为定义插入存储指令
                        insertStoreInstructionForSpilledDef(block, instruction, spilledRegister, 
                        stackOffset + instruction.getCalleeParamOffset());
                    }
                }
            }
        }
    }
    

    private void insertLoadInstructionForSpilledUse(AArch64Block block, AArch64Instruction instruction, 
                                                   AArch64VirReg spilledRegister, long stackOffset) {
        AArch64VirReg temporaryRegister = new AArch64VirReg(spilledRegister.isFloat());
        
        if (stackOffset >= -256 && stackOffset <= 255) {
            // 在有符号偏移范围内
            AArch64Load loadInstruction = new AArch64Load(AArch64CPUReg.getAArch64SpReg(), 
                                                        new AArch64Imm(stackOffset), temporaryRegister);
            block.insertBeforeInst(instruction, loadInstruction);
        } else if (stackOffset >= 0 && stackOffset <= 32760 && (stackOffset % 8 == 0)) {
            // 在无符号偏移范围内
            AArch64Load loadInstruction = new AArch64Load(AArch64CPUReg.getAArch64SpReg(), 
                                                        new AArch64Imm(stackOffset), temporaryRegister);
            block.insertBeforeInst(instruction, loadInstruction);
        } else {
            // 超出范围，分解为ADD+LOAD
            AArch64VirReg addressRegister = new AArch64VirReg(false);
            RegisterAllocatorHelper.loadLargeImmToReg(block, instruction, addressRegister, stackOffset, false);
            ArrayList<AArch64Operand> addOperands = new ArrayList<>();
            addOperands.add(AArch64CPUReg.getAArch64SpReg());
            addOperands.add(addressRegister);
            AArch64Binary addInstruction = new AArch64Binary(addOperands, addressRegister, 
                                                           AArch64Binary.AArch64BinaryType.add);
            addInstruction.setUse32BitMode(false); // 地址计算使用64位寄存器
            block.insertBeforeInst(instruction, addInstruction);
            // 使用零偏移加载
            AArch64Load loadInstruction = new AArch64Load(addressRegister, new AArch64Imm(0), temporaryRegister);
            block.insertBeforeInst(instruction, loadInstruction);
        }
        instruction.replaceOperands(spilledRegister, temporaryRegister);
    }
    

    private void insertStoreInstructionForSpilledDef(AArch64Block block, AArch64Instruction instruction, 
                                                    AArch64VirReg spilledRegister, long stackOffset) {
        AArch64VirReg temporaryRegister = new AArch64VirReg(spilledRegister.isFloat());
        instruction.replaceDefReg(temporaryRegister);
        
        if (stackOffset >= -256 && stackOffset <= 255) {
            // 在有符号偏移范围内
            AArch64Store storeInstruction = new AArch64Store(temporaryRegister, AArch64CPUReg.getAArch64SpReg(), 
                                                           new AArch64Imm(stackOffset));
            RegisterAllocatorHelper.insertAfterInstruction(block, instruction, storeInstruction);
        } else if (stackOffset >= 0 && stackOffset <= 32760 && (stackOffset % 8 == 0)) {
            // 在无符号偏移范围内
            AArch64Store storeInstruction = new AArch64Store(temporaryRegister, AArch64CPUReg.getAArch64SpReg(), 
                                                           new AArch64Imm(stackOffset));
            RegisterAllocatorHelper.insertAfterInstruction(block, instruction, storeInstruction);
        } else {
            // 超出范围，分解为ADD+STORE
            AArch64VirReg addressRegister = new AArch64VirReg(false);
            RegisterAllocatorHelper.loadLargeImmToReg(block, instruction, addressRegister, stackOffset, true);
            ArrayList<AArch64Operand> addOperands = new ArrayList<>();
            addOperands.add(AArch64CPUReg.getAArch64SpReg());
            addOperands.add(addressRegister);
            AArch64Binary addInstruction = new AArch64Binary(addOperands, addressRegister, 
                                                           AArch64Binary.AArch64BinaryType.add);
            addInstruction.setUse32BitMode(false); // 地址计算使用64位寄存器
            RegisterAllocatorHelper.insertAfterInstruction(block, instruction, addInstruction);
            // 使用零偏移存储
            AArch64Store storeInstruction = new AArch64Store(temporaryRegister, addressRegister, new AArch64Imm(0));
            RegisterAllocatorHelper.insertAfterInstruction(block, instruction, storeInstruction);
        }
    }
} 
package IR.Pass;

import IR.Module;
import IR.Value.BasicBlock;
import IR.Value.Function;
import IR.Value.Instructions.BranchInstruction;
import IR.Value.Instructions.CallInstruction;
import IR.Value.Instructions.Instruction;
import IR.Value.Instructions.ReturnInstruction;
import IR.Value.Instructions.StoreInstruction;
import IR.Value.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 无效代码剔除优化
 * 识别并移除对程序结果没有影响的冗余指令
 */
public class DCE implements Pass.IRPass {

    @Override
    public String getName() {
        return "DeadCodeElimination";
    }

    // 记录所有必要保留的指令
    private final HashSet<Instruction> essentialInstructions = new HashSet<>();
    
    @Override
    public boolean run(Module module) {
        boolean modified = false;
        
        // 对每个函数进行优化
        for (Function function : module.functions()) {
            // 跳过外部函数
            if (function.isExternal()) {
                continue;
            }
            
            // 对当前函数执行优化
            modified |= optimizeFunction(function);
        }
        
        return modified;
    }

    /**
     * 对单个函数执行无效代码剔除优化
     * @param function 待优化的函数
     * @return 如果函数被修改返回true，否则返回false
     */
    private boolean optimizeFunction(Function function) {
        // 清空记录
        essentialInstructions.clear();
        List<Instruction> instructionsToRemove = new ArrayList<>();
        
        // 第一步：标记所有必要的指令
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction instruction : block.getInstructions()) {
                if (hasEffectOnProgram(instruction)) {
                    markEssentialInstruction(instruction);
                }
            }
        }

        // 第二步：收集所有可以移除的指令
        boolean modified = false;
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction instruction : block.getInstructions()) {
                if (!essentialInstructions.contains(instruction)) {
                    instructionsToRemove.add(instruction);
                    modified = true;
                }
            }
        }

        // 第三步：移除无效指令
        for (Instruction instruction : instructionsToRemove) {
            instruction.removeFromParent();
        }
        
        return modified;
    }

    /**
     * 标记一条指令为必要指令，并递归标记其依赖的指令
     * @param instruction 要标记的指令
     */
    private void markEssentialInstruction(Instruction instruction) {
        // 如果已经标记过，直接返回，避免循环
        if (essentialInstructions.contains(instruction)) {
            return;
        }
        
        // 标记当前指令
        essentialInstructions.add(instruction);
        
        // 递归标记所有操作数中的指令
        for (Value operand : instruction.getOperands()) {
            if (operand instanceof Instruction) {
                markEssentialInstruction((Instruction) operand);
            }
        }
    }

    /**
     * 判断指令是否对程序结果有影响
     * @param instruction 待判断的指令
     * @return 如果有影响返回true，否则返回false
     */
    private boolean hasEffectOnProgram(Instruction instruction) {
        // 终结指令必须保留
        if (instruction instanceof BranchInstruction || 
            instruction instanceof ReturnInstruction) {
            return true;
        }
        
        // 存储指令可能有副作用，必须保留
        if (instruction instanceof StoreInstruction) {
            return true;
        }
        
        // 函数调用可能有副作用
        if (instruction instanceof CallInstruction) {
            Function calledFunction = ((CallInstruction) instruction).getCallee();
            // 外部函数或有副作用的函数调用必须保留
            return calledFunction.isExternal() || calledFunction.mayHaveSideEffect();
        }
        
        // 其他指令默认认为没有副作用
        return false;
    }
} 
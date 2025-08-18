package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.User;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.GetElementPtrInstruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.IR.Value.Instructions.BinaryInstruction;
import MiddleEnd.IR.Value.Instructions.BranchInstruction;
import MiddleEnd.IR.Value.Instructions.TerminatorInstruction;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Analysis.Loop;
import MiddleEnd.Optimization.Analysis.LoopAnalysis;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.*;

/**
 * 循环指针外提优化（Loop Pointer Extraction）
 * 将循环内以迭代变量为索引的指针运算提取到循环外，
 * 使用Phi节点管理指针的递增，减少循环内的重复计算
 */
public class LoopPointerExtract implements Optimizer.ModuleOptimizer {

    private final boolean debug = false;
    private static final int MAX_BLOCK_THRESHOLD = 1000;
    @Override
    public String getName() {
        return "LoopPointerExtract";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        if (debug) {
            System.out.println("[LoopPtrExtract] 开始循环指针外提优化");
        }
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= runForFunction(function);
        }
        
        if (debug) {
            System.out.println("[LoopPtrExtract] 优化完成，是否有改动: " + changed);
        }
        
        return changed;
    }

    private boolean runForFunction(Function function) {
        int blockCount = function.getBasicBlocks().size();
        if (blockCount > MAX_BLOCK_THRESHOLD) {
            System.out.println("[LoopPtrExtract] Function " + function.getName() + " has " + blockCount + 
                " basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
            return false;
        }
        
        boolean changed = false;
        
        if (debug) {
            System.out.println("[LoopPtrExtract] 分析函数: " + function.getName());
        }
        
        List<Loop> topLoops = LoopAnalysis.analyzeLoops(function);
        
        if (debug) {
            System.out.println("[LoopPtrExtract] 找到 " + topLoops.size() + " 个顶层循环");
        }
        
        LoopAnalysis.analyzeInductionVariables(function);
        
        List<Loop> allLoops = getAllLoopsInDFSOrder(topLoops);
        
        if (debug) {
            System.out.println("[LoopPtrExtract] 总共要处理 " + allLoops.size() + " 个循环");
        }
        
        for (Loop loop : allLoops) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 处理循环，头块: " + loop.getHeader().getName());
                System.out.println("[LoopPtrExtract] 循环有归纳变量: " + loop.hasInductionVariable());
                if (loop.hasInductionVariable()) {
                    System.out.println("[LoopPtrExtract] 归纳变量: " + loop.getInductionVariable());
                    System.out.println("[LoopPtrExtract] 初始值: " + loop.getInitValue());
                    System.out.println("[LoopPtrExtract] 步长: " + loop.getStepValue());
                }
            }
            changed |= runPtrExtractForLoop(loop);
        }
        
        return changed;
    }

    private List<Loop> getAllLoopsInDFSOrder(List<Loop> topLoops) {
        List<Loop> allLoops = new ArrayList<>();
        for (Loop loop : topLoops) {
            addLoopsInDFSOrder(loop, allLoops);
        }
        return allLoops;
    }

    private void addLoopsInDFSOrder(Loop loop, List<Loop> allLoops) {
        for (Loop subLoop : loop.getSubLoops()) {
            addLoopsInDFSOrder(subLoop, allLoops);
        }
        allLoops.add(loop);
    }

    private boolean runPtrExtractForLoop(Loop loop) {
        try {
            if (!isEligibleLoop(loop)) {
                return false;
            }

            BasicBlock header = loop.getHeader();
            BasicBlock latch = loop.getLatchBlocks().get(0);
            BasicBlock preheader = loop.getPreheader();

            Value inductionVar = loop.getInductionVariable();
            Value initValue = loop.getInitValue();
            Value stepValue = loop.getStepValue();
            Instruction updateInst = loop.getUpdateInstruction();

            if (!isLoopSuitableForPtrExtraction(loop, inductionVar, initValue)) {
                return false;
            }

            List<GetElementPtrInstruction> targetGepInsts = findTargetGepInstructions(loop, inductionVar);
            
            if (targetGepInsts.isEmpty()) {
                return false;
            }

            performPtrExtraction(loop, targetGepInsts, preheader, latch, 
                               inductionVar, initValue, stepValue, updateInst);

            if (debug) {
                System.out.println("[LoopPtrExtract] 成功对循环执行指针外提优化");
            }

            return true;
            
        } catch (Exception e) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 处理循环时出错: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }
    }

    private boolean isEligibleLoop(Loop loop) {
        if (!loop.hasInductionVariable()) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 循环没有归纳变量，跳过");
            }
            return false;
        }

        BasicBlock header = loop.getHeader();
        if (header.getPredecessors().size() != 2) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 循环头块前驱数不为2，跳过");
            }
            return false;
        }

        if (loop.getLatchBlocks().size() != 1) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 循环latch块数不为1，跳过");
            }
            return false;
        }

        BasicBlock preheader = loop.getPreheader();
        
        if (preheader == null) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 循环没有前置头块，跳过");
            }
            return false;
        }

        Value inductionVar = loop.getInductionVariable();
        Value initValue = loop.getInitValue();
        Value stepValue = loop.getStepValue();

        if (inductionVar == null || initValue == null || stepValue == null) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 归纳变量信息不完整，跳过");
            }
            return false;
        }

        return true;
    }

    private List<GetElementPtrInstruction> findTargetGepInstructions(Loop loop, Value inductionVar) {
        List<GetElementPtrInstruction> targetInsts = new ArrayList<>();
        
        for (BasicBlock block : loop.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof GetElementPtrInstruction gepInst) {
                    if (usesInductionVariableAsOffset(gepInst, inductionVar)) {
                        targetInsts.add(gepInst);
                    }
                }
            }
        }
        return targetInsts;
    }

    private boolean isLoopSuitableForPtrExtraction(Loop loop, Value inductionVar, Value initValue) {
        if (!(initValue instanceof ConstantInt)) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 初始值不是编译时常量，跳过优化");
            }
            return false;
        }
        
        Set<Value> accessedArrays = new HashSet<>();
        for (BasicBlock block : loop.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst instanceof GetElementPtrInstruction gepInst) {
                    accessedArrays.add(gepInst.getPointer());
                }
            }
        }
        
        if (accessedArrays.size() > 1) {
            if (debug) {
                System.out.println("[LoopPtrExtract] 循环访问多个数组，跳过优化");
            }
            return false;
        }
        
        if (debug) {
            System.out.println("[LoopPtrExtract] 循环适合指针外提优化");
        }
        
        return true;
    }

    private boolean usesInductionVariableAsOffset(GetElementPtrInstruction gepInst, Value inductionVar) {
        if (gepInst.getOperandCount() >= 2 && gepInst.getOperand(1).equals(inductionVar)) {
            return true;
        }
        
        for (Value index : gepInst.getIndices()) {
            if (index.equals(inductionVar)) {
                return true;
            }
        }
        
        return false;
    }

    private void performPtrExtraction(Loop loop, List<GetElementPtrInstruction> targetGepInsts,
                                     BasicBlock preheader, BasicBlock latch, 
                                     Value inductionVar, Value initValue, Value stepValue,
                                     Instruction updateInst) {
        
        if (targetGepInsts.isEmpty()) {
            return;
        }
        
        Value basePointer = targetGepInsts.get(0).getPointer();
        
        List<Instruction> preheaderInsts = preheader.getInstructions();
        Instruction lastInst = preheaderInsts.get(preheaderInsts.size() - 1);
        
        GetElementPtrInstruction initGepInst = IRBuilder.createGetElementPtr(basePointer, initValue, null);
        
        preheader.addInstructionBefore(initGepInst, lastInst);

        BasicBlock header = loop.getHeader();
        
        PhiInstruction ptrPhi = new PhiInstruction(basePointer.getType(), "loop_ptr_" + System.currentTimeMillis() + "_" + loop.getHeader().getName());
        
        ptrPhi.addIncoming(initGepInst, preheader);
        
        header.addInstructionBefore(ptrPhi, header.getInstructions().get(0));

        GetElementPtrInstruction stepGepInst = createStepGepInstruction(ptrPhi, stepValue, updateInst);
        
        Instruction latchTerminator = latch.getTerminator();
        if (latchTerminator != null) {
            latch.addInstructionBefore(stepGepInst, latchTerminator);
        } else {
            latch.addInstruction(stepGepInst);
        }

        ptrPhi.addOrUpdateIncoming(stepGepInst, latch);

        for (GetElementPtrInstruction targetGepInst : targetGepInsts) {
            replaceAllUsesWith(targetGepInst, ptrPhi);
        }
        
        for (GetElementPtrInstruction targetGepInst : targetGepInsts) {
            targetGepInst.getParent().removeInstruction(targetGepInst);
        }
        
        if (debug) {
            System.out.println("[LoopPtrExtract] 已完成指针外提变换");
        }
    }

    private GetElementPtrInstruction createStepGepInstruction(Value ptrPhi, Value stepValue, Instruction updateInst) {
        if (updateInst instanceof BinaryInstruction binInst) {
            OpCode op = binInst.getOpCode();
            
            if (op == OpCode.ADD) {
                return IRBuilder.createGetElementPtr(ptrPhi, stepValue, null);
            } else if (op == OpCode.SUB) {
                Value negativeStep = createNegativeStep(stepValue, updateInst);
                return IRBuilder.createGetElementPtr(ptrPhi, negativeStep, null);
            }
        }
        
        return IRBuilder.createGetElementPtr(ptrPhi, stepValue, null);
    }

    private Value createNegativeStep(Value stepValue, Instruction updateInst) {
        if (stepValue instanceof ConstantInt constInt) {
            return new ConstantInt(-constInt.getValue());
        } else {
            ConstantInt minusOne = new ConstantInt(-1);
            BinaryInstruction negInst = new BinaryInstruction(OpCode.MUL, stepValue, minusOne, IntegerType.I32);
            updateInst.getParent().addInstructionBefore(negInst, updateInst);
            return negInst;
        }
    }

    private void replaceAllUsesWith(Value oldValue, Value newValue) {
        List<User> users = new ArrayList<>(oldValue.getUsers());
        for (User user : users) {
            user.replaceAllUsesWith(oldValue, newValue);
        }
    }

    private void addInstructionAfter(Instruction newInst, Instruction afterInst) {
        BasicBlock parent = afterInst.getParent();
        List<Instruction> instructions = parent.getInstructions();
        int index = instructions.indexOf(afterInst);
        
        if (afterInst instanceof TerminatorInstruction) {
            parent.addInstructionBefore(newInst, afterInst);
        } else if (index != -1 && index < instructions.size() - 1) {
            parent.addInstructionBefore(newInst, instructions.get(index + 1));
        } else {
            parent.addInstruction(newInst);
        }
    }
}

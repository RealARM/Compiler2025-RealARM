package MiddleEnd.Optimization.Advanced;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 尾递归消除优化
 * 将函数中对自身的尾递归调用转换为循环，避免栈溢出
 */
public class TailRecursionElimination implements Optimizer.ModuleOptimizer {
    
    private boolean debug = false; // 生产环境下禁用调试输出
    
    private void debug(String msg) {
        if (debug) {
            System.out.println("[DEBUG-TailRec] " + msg);
        }
    }
    
    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        debug("开始运行尾递归消除优化...");
        
        buildFunctionCallGraph(module);
        
        for (Function function : module.functions()) {
            if (canEliminate(function)) {
                try {
                    runEliminationForFunc(function);
                    changed = true;
                    System.out.println("[TailRecursionElimination] Successfully optimized function: " + function.getName());
                } catch (Exception e) {
                    System.err.println("[TailRecursionElimination] Failed to optimize function: " + function.getName());
                    e.printStackTrace();
                }
            }
        }
        
        return changed;
    }
    
    private void buildFunctionCallGraph(Module module) {
        debug("构建函数调用关系图");
        
        for (Function function : module.functions()) {
            function.getCallers().clear();
            function.getCallees().clear();
        }
        
        for (Function caller : module.functions()) {
            for (BasicBlock bb : caller.getBasicBlocks()) {
                for (Instruction inst : bb.getInstructions()) {
                    if (inst instanceof CallInstruction callInst) {
                        Function callee = callInst.getCallee();
                        
                        caller.addCallee(callee);
                        callee.addCaller(caller);
                    }
                }
            }
        }
    }
    
    private boolean canEliminate(Function function) {
        boolean hasSelfCall = false;
        for (Function callee : function.getCallees()) {
            if (callee == function) {
                hasSelfCall = true;
                break;
            }
        }
        
        if (!hasSelfCall) {
            return false;
        }
        
        // 检查参数类型，目前只处理整数和浮点数类型
        for (Argument arg : function.getArguments()) {
            if (!arg.getType().isIntegerType() && !arg.getType().isFloatType()) {
                return false;
            }
        }
        
        List<TailCall> tailCalls = findTailRecursiveCalls(function);
        return !tailCalls.isEmpty();
    }
    
    private void runEliminationForFunc(Function function) {
        // 1. 找出所有尾递归调用
        List<TailCall> tailCalls = findTailRecursiveCalls(function);
        if (tailCalls.isEmpty()) {
            return;
        }
        
        // 2. 创建新的控制流结构
        BlockInfo blockInfo = setupControlFlow(function, tailCalls);
        
        // 3. 创建和设置phi节点
        List<PhiInstruction> phis = setupPhiNodes(function, blockInfo, tailCalls);
        
        // 4. 替换函数体中对参数的使用
        replaceParameterUses(function, blockInfo.originalEntry, phis);
        
        // 5. 替换尾递归调用为跳转
        replaceTailCalls(blockInfo.originalEntry, tailCalls);
        
        // 6. 修复phi节点
        IRBuilder.validateAllPhiNodes(function);
    }
    
    private BlockInfo setupControlFlow(Function function, List<TailCall> tailCalls) {
        BasicBlock originalEntry = function.getEntryBlock();
        BasicBlock newEntry = IRBuilder.createBasicBlock("entry.tail.rec", function);
        
        function.removeBasicBlock(newEntry);
        function.addBasicBlockBefore(newEntry, originalEntry);
        
        IRBuilder.createBr(originalEntry, newEntry);
        newEntry.addSuccessor(originalEntry);
        originalEntry.addPredecessor(newEntry);
        
        for (TailCall tailCall : tailCalls) {
            BasicBlock tailCallBlock = tailCall.basicBlock;
            tailCallBlock.addSuccessor(originalEntry);
            originalEntry.addPredecessor(tailCallBlock);
        }
        
        return new BlockInfo(originalEntry, newEntry);
    }
    
    private List<PhiInstruction> setupPhiNodes(Function function, BlockInfo blockInfo, List<TailCall> tailCalls) {
        List<Argument> arguments = function.getArguments();
        List<PhiInstruction> phis = new ArrayList<>();
        
        // 1. 创建phi指令
        for (Argument arg : arguments) {
            PhiInstruction phi = IRBuilder.createPhi(arg.getType(), blockInfo.originalEntry);
            phis.add(phi);
        }
        
        // 2. 设置phi指令的入口值
        for (int i = 0; i < arguments.size(); i++) {
            PhiInstruction phi = phis.get(i);
            phi.addIncoming(arguments.get(i), blockInfo.newEntry);
        }
        
        // 3. 设置phi指令的递归值
        for (TailCall tailCall : tailCalls) {
            for (int i = 0; i < arguments.size(); i++) {
                Value recursiveValue = tailCall.callInst.getArgument(i);
                phis.get(i).addIncoming(recursiveValue, tailCall.basicBlock);
            }
        }
        
        return phis;
    }
    
    private void replaceParameterUses(Function function, BasicBlock entryBlock, List<PhiInstruction> phis) {
        Map<Argument, PhiInstruction> argToPhiMap = new HashMap<>();
        List<Argument> arguments = function.getArguments();
        for (int i = 0; i < arguments.size(); i++) {
            argToPhiMap.put(arguments.get(i), phis.get(i));
        }
        
        for (BasicBlock bb : function.getBasicBlocks()) {
            if (bb == entryBlock) {
                // 入口块特殊处理：跳过phi指令
                replaceInEntryBlock(bb, argToPhiMap);
            } else {
                replaceInNormalBlock(bb, argToPhiMap);
            }
        }
    }
    
    private void replaceInEntryBlock(BasicBlock entryBlock, Map<Argument, PhiInstruction> argToPhiMap) {
        List<Instruction> instructions = entryBlock.getInstructions();
        int phiCount = 0;
        
        for (Instruction inst : instructions) {
            if (inst instanceof PhiInstruction) {
                phiCount++;
            } else {
                break;
            }
        }
        
        for (int i = phiCount; i < instructions.size(); i++) {
            replaceArgumentsWithPhis(instructions.get(i), argToPhiMap);
        }
    }
    
    private void replaceInNormalBlock(BasicBlock block, Map<Argument, PhiInstruction> argToPhiMap) {
        for (Instruction inst : block.getInstructions()) {
            replaceArgumentsWithPhis(inst, argToPhiMap);
        }
    }
    
    private void replaceArgumentsWithPhis(Instruction inst, Map<Argument, PhiInstruction> argToPhiMap) {
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            if (operand instanceof Argument arg && argToPhiMap.containsKey(arg)) {
                inst.setOperand(i, argToPhiMap.get(arg));
            }
        }
    }
    
    private void replaceTailCalls(BasicBlock targetBlock, List<TailCall> tailCalls) {
        for (TailCall tailCall : tailCalls) {
            BasicBlock bb = tailCall.basicBlock;
            
            bb.removeInstruction(tailCall.retInst);
            bb.removeInstruction(tailCall.callInst);
            
            IRBuilder.createBr(targetBlock, bb);
        }
    }
    
    private List<TailCall> findTailRecursiveCalls(Function function) {
        List<TailCall> tailCalls = new ArrayList<>();
        
        for (BasicBlock bb : function.getBasicBlocks()) {
            List<Instruction> instructions = bb.getInstructions();
            if (instructions.size() < 2) continue;
            
            Instruction lastInst = instructions.get(instructions.size() - 1);
            Instruction secondLastInst = instructions.get(instructions.size() - 2);
            
            if (isTailRecursiveCallPattern(function, lastInst, secondLastInst)) {
                ReturnInstruction retInst = (ReturnInstruction)lastInst;
                CallInstruction callInst = (CallInstruction)secondLastInst;
                tailCalls.add(new TailCall(callInst, retInst, bb));
            }
        }
        
        return tailCalls;
    }
    
    private boolean isTailRecursiveCallPattern(Function function, Instruction lastInst, Instruction secondLastInst) {
        // 模式: return call(...)
        if (!(lastInst instanceof ReturnInstruction retInst) || 
            !(secondLastInst instanceof CallInstruction callInst)) {
            return false;
        }
        
        if (retInst.isVoidReturn() || retInst.getReturnValue() != callInst) {
            return false;
        }
        
        return callInst.getCallee() == function;
    }
    
    private static class TailCall {
        final CallInstruction callInst;
        final ReturnInstruction retInst;
        final BasicBlock basicBlock;
        
        TailCall(CallInstruction callInst, ReturnInstruction retInst, BasicBlock basicBlock) {
            this.callInst = callInst;
            this.retInst = retInst;
            this.basicBlock = basicBlock;
        }
    }
    
    private static class BlockInfo {
        final BasicBlock originalEntry;
        final BasicBlock newEntry;
        
        BlockInfo(BasicBlock originalEntry, BasicBlock newEntry) {
            this.originalEntry = originalEntry;
            this.newEntry = newEntry;
        }
    }
    
    @Override
    public String getName() {
        return "TailRecursionElimination";
    }
} 
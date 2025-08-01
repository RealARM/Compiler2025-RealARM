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
        
        // 构建函数调用关系图
        buildFunctionCallGraph(module);
        
        // 遍历模块中的所有函数
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
    
    /**
     * 构建函数调用关系图
     */
    private void buildFunctionCallGraph(Module module) {
        debug("构建函数调用关系图");
        
        // 清除之前的调用关系
        for (Function function : module.functions()) {
            function.getCallers().clear();
            function.getCallees().clear();
        }
        
        // 遍历每个函数，找出调用关系
        for (Function caller : module.functions()) {
            for (BasicBlock bb : caller.getBasicBlocks()) {
                for (Instruction inst : bb.getInstructions()) {
                    if (inst instanceof CallInstruction callInst) {
                        Function callee = callInst.getCallee();
                        
                        // 添加调用关系
                        caller.addCallee(callee);
                        callee.addCaller(caller);
                    }
                }
            }
        }
    }
    
    /**
     * 判断函数是否可以进行尾递归消除
     */
    private boolean canEliminate(Function function) {
        // 检查函数是否调用自身
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
        
        // 检查是否有尾递归调用
        List<TailCall> tailCalls = findTailRecursiveCalls(function);
        return !tailCalls.isEmpty();
    }
    
    /**
     * 对函数进行尾递归消除
     */
    private void runEliminationForFunc(Function function) {
        // 1. 找出所有尾递归调用
        List<TailCall> tailCalls = findTailRecursiveCalls(function);
        if (tailCalls.isEmpty()) {
            return; // 没有尾递归调用，无需优化
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
    
    /**
     * 设置新的控制流结构
     */
    private BlockInfo setupControlFlow(Function function, List<TailCall> tailCalls) {
        BasicBlock originalEntry = function.getEntryBlock();
        BasicBlock newEntry = IRBuilder.createBasicBlock("entry.tail.rec", function);
        
        // 将新入口块移到函数的最前面
        function.removeBasicBlock(newEntry);
        function.addBasicBlockBefore(newEntry, originalEntry);
        
        // 从新入口到原入口的跳转
        IRBuilder.createBr(originalEntry, newEntry);
        newEntry.addSuccessor(originalEntry);
        originalEntry.addPredecessor(newEntry);
        
        // 设置从尾递归调用点到原入口的跳转关系
        for (TailCall tailCall : tailCalls) {
            BasicBlock tailCallBlock = tailCall.basicBlock;
            tailCallBlock.addSuccessor(originalEntry);
            originalEntry.addPredecessor(tailCallBlock);
        }
        
        return new BlockInfo(originalEntry, newEntry);
    }
    
    /**
     * 为参数创建phi指令并设置初始值和递归值
     */
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
    
    /**
     * 替换函数体中对参数的使用
     */
    private void replaceParameterUses(Function function, BasicBlock entryBlock, List<PhiInstruction> phis) {
        // 创建参数到phi节点的映射
        Map<Argument, PhiInstruction> argToPhiMap = new HashMap<>();
        List<Argument> arguments = function.getArguments();
        for (int i = 0; i < arguments.size(); i++) {
            argToPhiMap.put(arguments.get(i), phis.get(i));
        }
        
        // 处理每个基本块
        for (BasicBlock bb : function.getBasicBlocks()) {
            if (bb == entryBlock) {
                // 入口块特殊处理：跳过phi指令
                replaceInEntryBlock(bb, argToPhiMap);
            } else {
                // 其他块：处理所有指令
                replaceInNormalBlock(bb, argToPhiMap);
            }
        }
    }
    
    /**
     * 替换入口块中的参数引用（需要特殊处理phi指令）
     */
    private void replaceInEntryBlock(BasicBlock entryBlock, Map<Argument, PhiInstruction> argToPhiMap) {
        List<Instruction> instructions = entryBlock.getInstructions();
        int phiCount = 0;
        
        // 计算phi指令数量
        for (Instruction inst : instructions) {
            if (inst instanceof PhiInstruction) {
                phiCount++;
            } else {
                break;
            }
        }
        
        // 替换非phi指令中的参数
        for (int i = phiCount; i < instructions.size(); i++) {
            replaceArgumentsWithPhis(instructions.get(i), argToPhiMap);
        }
    }
    
    /**
     * 替换普通基本块中的参数引用
     */
    private void replaceInNormalBlock(BasicBlock block, Map<Argument, PhiInstruction> argToPhiMap) {
        for (Instruction inst : block.getInstructions()) {
            replaceArgumentsWithPhis(inst, argToPhiMap);
        }
    }
    
    /**
     * 替换指令中的参数引用
     */
    private void replaceArgumentsWithPhis(Instruction inst, Map<Argument, PhiInstruction> argToPhiMap) {
        for (int i = 0; i < inst.getOperandCount(); i++) {
            Value operand = inst.getOperand(i);
            if (operand instanceof Argument arg && argToPhiMap.containsKey(arg)) {
                inst.setOperand(i, argToPhiMap.get(arg));
            }
        }
    }
    
    /**
     * 替换尾递归调用为跳转
     */
    private void replaceTailCalls(BasicBlock targetBlock, List<TailCall> tailCalls) {
        for (TailCall tailCall : tailCalls) {
            BasicBlock bb = tailCall.basicBlock;
            
            // 移除返回指令和调用指令
            bb.removeInstruction(tailCall.retInst);
            bb.removeInstruction(tailCall.callInst);
            
            // 添加跳转到入口块的指令
            IRBuilder.createBr(targetBlock, bb);
        }
    }
    
    /**
     * 找出函数中所有的尾递归调用
     */
    private List<TailCall> findTailRecursiveCalls(Function function) {
        List<TailCall> tailCalls = new ArrayList<>();
        
        // 遍历函数中的所有基本块
        for (BasicBlock bb : function.getBasicBlocks()) {
            List<Instruction> instructions = bb.getInstructions();
            if (instructions.size() < 2) continue;
            
            // 获取最后两条指令
            Instruction lastInst = instructions.get(instructions.size() - 1);
            Instruction secondLastInst = instructions.get(instructions.size() - 2);
            
            // 检查是否为尾递归调用模式
            if (isTailRecursiveCallPattern(function, lastInst, secondLastInst)) {
                ReturnInstruction retInst = (ReturnInstruction)lastInst;
                CallInstruction callInst = (CallInstruction)secondLastInst;
                tailCalls.add(new TailCall(callInst, retInst, bb));
            }
        }
        
        return tailCalls;
    }
    
    /**
     * 判断是否为尾递归调用模式
     */
    private boolean isTailRecursiveCallPattern(Function function, Instruction lastInst, Instruction secondLastInst) {
        // 模式: return call(...)
        if (!(lastInst instanceof ReturnInstruction retInst) || 
            !(secondLastInst instanceof CallInstruction callInst)) {
            return false;
        }
        
        // 检查返回值是否来自函数调用
        if (retInst.isVoidReturn() || retInst.getReturnValue() != callInst) {
            return false;
        }
        
        // 检查是否是调用自己
        return callInst.getCallee() == function;
    }
    
    /**
     * 表示一个尾递归调用
     */
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
    
    /**
     * 保存控制流块信息
     */
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
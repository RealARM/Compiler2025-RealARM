package MiddleEnd.Optimization.Memory;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.Optimization.Core.Optimizer.ModuleOptimizer;
import MiddleEnd.Optimization.Analysis.PointerAliasInspector;
import MiddleEnd.Optimization.Analysis.DominatorAnalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Load/Store数据流优化器
 * 进行Load前向替换和Store消除优化
 */
public class LoadStoreFlowOptimizer implements ModuleOptimizer {
    
    /**
     * 数组信息类，保存每个基本块的内存访问信息
     */
    private static class ArrayInfo {
        // 所有内存相关指令
        public ArrayList<Instruction> memInsts = new ArrayList<>();
        // 从此块流出的Store指令
        public LinkedHashMap<Value, StoreInstruction> memOut = new LinkedHashMap<>();
        // 流入此块的Store指令
        public LinkedHashMap<Value, LinkedHashSet<StoreInstruction>> memIn = new LinkedHashMap<>();
        // 函数调用可能修改的指针参数
        public LinkedHashSet<Value> memFuncsOut = new LinkedHashSet<>();
        public LinkedHashMap<Value, LinkedHashSet<BasicBlock>> memFuncsIn = new LinkedHashMap<>();
    }
    
    private LinkedHashMap<BasicBlock, ArrayInfo> blockArrayInfos;
    private LinkedHashMap<StoreInstruction, Integer> storeCount;
    private LinkedHashMap<LoadInstruction, LinkedHashSet<StoreInstruction>> loadConflict;
    private Map<BasicBlock, Set<BasicBlock>> dominatorMap;
    private boolean needRepeat;

    @Override
    public String getName() {
        return "LoadStoreFlowOptimizer";
    }

    @Override
    public boolean run(Module module) {
        needRepeat = false;
        blockArrayInfos = new LinkedHashMap<>();
        storeCount = new LinkedHashMap<>();
        loadConflict = new LinkedHashMap<>();
        
        // 初始化每个基本块的信息
        for (Function function : module.functions()) {
            if (function.isExternal()) continue;
            
            for (BasicBlock bb : function.getBasicBlocks()) {
                blockArrayInfos.put(bb, new ArrayInfo());
            }
            
            runForFunction(function);
        }
        
        return needRepeat;
    }
    
    /**
     * 为单个函数运行优化
     */
    private void runForFunction(Function function) {
        if (function.isExternal()) return;
        
        // 计算支配关系
        dominatorMap = DominatorAnalysis.computeDominators(function);
        
        // 第一阶段：分析每个基本块，收集Store指令和计数
        for (BasicBlock bb : function.getBasicBlocks()) {
            analyzeBlock(bb);
        }
        
        // 第二阶段：构建数据流信息
        buildDataFlow(function);
        
        // 第三阶段：进行优化
        performOptimization(function);
        
        // 第四阶段：删除未使用的Store指令
        eliminateUnusedStores(function);
    }
    
    /**
     * 分析基本块，收集内存相关指令
     */
    private void analyzeBlock(BasicBlock block) {
        ArrayInfo info = blockArrayInfos.get(block);
        
        for (Instruction inst : block.getInstructions()) {
            if (inst instanceof StoreInstruction store) {
                info.memOut.put(store.getPointer(), store);
                storeCount.put(store, 0); // 初始计数为0
                info.memInsts.add(store);
            } else if (inst instanceof CallInstruction call) {
                info.memInsts.add(call);
                
                // 检查是否为安全（无副作用）的库函数调用
                if (isLibrarySafeFunction(call)) {
                    continue; // 安全函数，跳过
                }
                
                // 收集可能被修改的指针参数
                for (Value operand : call.getOperands()) {
                    if (operand.getType().isPointerType()) {
                        info.memFuncsOut.add(operand);
                    }
                }
            } else if (inst instanceof LoadInstruction load) {
                // 只处理指针相关的Load
                if (load.getPointer() instanceof GetElementPtrInstruction ||
                    load.getPointer() instanceof GlobalVariable ||
                    load.getPointer() instanceof AllocaInstruction ||
                    load.getPointer() instanceof PhiInstruction) {
                    info.memInsts.add(load);
                }
            }
        }
    }
    
    /**
     * 构建数据流信息
     */
    private void buildDataFlow(Function function) {
        // 为每个基本块构建前驱的内存流入信息
        for (BasicBlock bb : function.getBasicBlocks()) {
            ArrayInfo bbInfo = blockArrayInfos.get(bb);
            
            // 从所有前驱基本块收集流入信息
            for (BasicBlock pred : bb.getPredecessors()) {
                updateMemInFrom(bb, pred, function);
            }
        }
    }
    
    /**
     * 更新从前驱基本块流入的内存信息
     */
    private void updateMemInFrom(BasicBlock targetBB, BasicBlock fromBB, Function function) {
        ArrayInfo targetInfo = blockArrayInfos.get(targetBB);
        ArrayInfo fromInfo = blockArrayInfos.get(fromBB);
        
        // 处理Store指令的流入
        for (var entry : fromInfo.memOut.entrySet()) {
            Value ptr = entry.getKey();
            StoreInstruction store = entry.getValue();
            
            boolean hasPhi = PointerAliasInspector.isPhiRelated(ptr);
            
            if (hasPhi) {
                // 如果涉及Phi指令，直接添加到流入集合
                if (!targetInfo.memIn.containsKey(ptr)) {
                    targetInfo.memIn.put(ptr, new LinkedHashSet<>());
                }
                targetInfo.memIn.get(ptr).add(store);
                continue;
            }
            
            // 处理静态地址的遮蔽关系
            ArrayList<StoreInstruction> needDelete = new ArrayList<>();
            boolean needAdd = true;
            
            if (targetInfo.memIn.containsKey(ptr)) {
                            for (var existingStore : targetInfo.memIn.get(ptr)) {
                BasicBlock existingFromBB = existingStore.getParent();
                
                if (fromBB.equals(targetBB) || existingFromBB.equals(targetBB)) continue;
                
                // 检查支配关系来决定遮蔽
                if (existingFromBB.equals(fromBB)) {
                    // 同一基本块内的顺序关系
                    int storeIndex = fromBB.getInstructions().indexOf(store);
                    int existingIndex = fromBB.getInstructions().indexOf(existingStore);
                    if (storeIndex > existingIndex) {
                        needDelete.add(existingStore);
                    } else {
                        needAdd = false;
                        break;
                    }
                }
                // 可以在这里添加更复杂的支配关系检查
            }
            }
            
            if (needAdd) {
                if (!targetInfo.memIn.containsKey(ptr)) {
                    targetInfo.memIn.put(ptr, new LinkedHashSet<>());
                }
                targetInfo.memIn.get(ptr).add(store);
            }
            
            needDelete.forEach(targetInfo.memIn.get(ptr)::remove);
        }
        
        // 处理函数调用的流入
        for (Value funcPtr : fromInfo.memFuncsOut) {
            if (!targetInfo.memFuncsIn.containsKey(funcPtr)) {
                targetInfo.memFuncsIn.put(funcPtr, new LinkedHashSet<>());
            }
            targetInfo.memFuncsIn.get(funcPtr).add(fromBB);
        }
    }
    
    /**
     * 进行优化：Load前向替换和Store消除
     */
    private void performOptimization(Function function) {
        for (BasicBlock bb : function.getBasicBlocks()) {
            ArrayInfo info = blockArrayInfos.get(bb);
            ArrayList<StoreInstruction> visitedStores = new ArrayList<>();
            
            // 基本块内指令索引通过List.indexOf()获取，无需单独更新
            
            for (Instruction inst : info.memInsts) {
                if (inst instanceof LoadInstruction load) {
                    optimizeLoad(load, bb, info, visitedStores, function);
                } else if (inst instanceof StoreInstruction store) {
                    optimizeStore(store, bb, info, visitedStores);
                } else if (inst instanceof CallInstruction call) {
                    handleCallInstruction(call, bb, info);
                }
            }
        }
    }
    
    /**
     * 优化Load指令：尝试进行前向替换
     */
    private void optimizeLoad(LoadInstruction load, BasicBlock bb, ArrayInfo info, 
                             ArrayList<StoreInstruction> visitedStores, Function function) {
        LinkedHashSet<StoreInstruction> conflictStores = new LinkedHashSet<>();
        loadConflict.put(load, conflictStores);
        
        Value ptr = load.getPointer();
        
        // 查找与Load指针可能别名的Store指令
        for (Value ptrInst : info.memIn.keySet()) {
            if (PointerAliasInspector.checkAlias(ptr, ptrInst) != PointerAliasInspector.AliasResult.NO) {
                conflictStores.addAll(info.memIn.get(ptrInst));
            }
        }
        
        // 禁止对全局内存的Load做前向替换，保守保证正确性
        Value loadRoot = PointerAliasInspector.getRoot(ptr);
        if (loadRoot instanceof GlobalVariable) {
            if (!conflictStores.isEmpty()) {
                conflictStores.forEach(s -> storeCount.put(s, storeCount.get(s) + 1));
            }
            return;
        }
        
        // 检查是否与函数调用冲突
        boolean conflictWithFunc = checkFunctionConflict(load, bb, info);
        
        if (conflictWithFunc) {
            // 如果与函数调用冲突，标记所有相关Store为被使用
            conflictStores.forEach(s -> storeCount.put(s, storeCount.get(s) + 1));
            return;
        }
        
        // 尝试找到唯一的可替换Store
        Value replacementValue = findReplacementValue(load, bb, info, conflictStores, visitedStores, function);
        
        if (replacementValue != null) {
            // 执行Load前向替换
            replaceAllUses(load, replacementValue);
            load.removeFromParent();
            needRepeat = true;
            return;
        }
        
        // 标记冲突的Store为被使用
        if (!conflictStores.isEmpty()) {
            conflictStores.forEach(s -> storeCount.put(s, storeCount.get(s) + 1));
        }
    }
    
    /**
     * 检查Load是否与函数调用产生冲突
     */
    private boolean checkFunctionConflict(LoadInstruction load, BasicBlock bb, ArrayInfo info) {
        Value ptr = load.getPointer();
        
        for (Value funcPtr : info.memFuncsIn.keySet()) {
            if (!(funcPtr.getType().isPointerType())) continue;
            
            // 创建临时GEP用于别名检查
            if (PointerAliasInspector.checkAlias(funcPtr, ptr) != PointerAliasInspector.AliasResult.NO) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 查找可以替换Load的唯一值
     */
    private Value findReplacementValue(LoadInstruction load, BasicBlock bb, ArrayInfo info,
                                     LinkedHashSet<StoreInstruction> conflictStores,
                                     ArrayList<StoreInstruction> visitedStores, Function function) {
        Value ptr = load.getPointer();
        Value onlyValue = null;
        boolean onlyInputValue = true;
        
        for (Value inPtr : info.memIn.keySet()) {
            if (PointerAliasInspector.checkAlias(inPtr, ptr) == PointerAliasInspector.AliasResult.YES) {
                for (StoreInstruction store : info.memIn.get(inPtr)) {
                    // 检查Store是否在Load之前且可到达
                    if (store.getParent().equals(bb) && !visitedStores.contains(store)) {
                        continue; // 同一基本块但还未访问到
                    }
                    
                    // 关键：检查支配关系 - Store必须支配Load才能替换
                    if (!dominates(store.getParent(), bb, function) && 
                        !(store.getParent().equals(bb) && visitedStores.contains(store))) {
                        continue; // Store不支配Load，不能替换
                    }
                    
                    // 检查是否被其他Store遮蔽
                    if (isStoreMasked(store, conflictStores, visitedStores, bb, function)) {
                        continue;
                    }
                    
                    if (onlyValue == null) {
                        onlyValue = store.getValue();
                    } else if (!onlyValue.equals(store.getValue())) {
                        onlyInputValue = false;
                        break;
                    }
                }
            }
            if (!onlyInputValue) break;
        }
        
        return onlyInputValue ? onlyValue : null;
    }
    
    /**
     * 检查Store是否被其他Store遮蔽
     */
    private boolean isStoreMasked(StoreInstruction store, LinkedHashSet<StoreInstruction> conflictStores,
                                ArrayList<StoreInstruction> visitedStores, BasicBlock bb, Function function) {
        for (StoreInstruction conflictStore : conflictStores) {
            if (visitedStores.contains(store) && !visitedStores.contains(conflictStore)) {
                continue;
            }
            
            BasicBlock conflictBB = conflictStore.getParent();
            BasicBlock storeBB = store.getParent();
            
            if (!storeBB.equals(conflictBB)) {
                // 不同基本块，检查支配关系
                // 这里需要支配树分析，暂时简化处理
                continue;
            } else {
                // 同一基本块，检查指令顺序
                int storeIndex = storeBB.getInstructions().indexOf(store);
                int conflictIndex = storeBB.getInstructions().indexOf(conflictStore);
                if (storeIndex < conflictIndex) {
                    return true; // 被后面的Store遮蔽
                }
            }
        }
        return false;
    }
    
    /**
     * 优化Store指令：更新访问记录
     */
    private void optimizeStore(StoreInstruction store, BasicBlock bb, ArrayInfo info,
                             ArrayList<StoreInstruction> visitedStores) {
        visitedStores.add(store);
        Value ptr = store.getPointer();
        
        // 如果是静态地址，清除之前的Store记录
        boolean staticAddress = PointerAliasInspector.isStaticSpace(ptr);
        if (staticAddress) {
            if (info.memIn.containsKey(ptr)) {
                info.memIn.get(ptr).clear();
            }
        }
        
        // 添加当前Store到内存流入记录
        if (!info.memIn.containsKey(ptr)) {
            info.memIn.put(ptr, new LinkedHashSet<>());
        }
        info.memIn.get(ptr).add(store);
    }
    
    /**
     * 处理函数调用指令
     */
    private void handleCallInstruction(CallInstruction call, BasicBlock bb, ArrayInfo info) {
        boolean safeFunc = isLibrarySafeFunction(call);
        
        if (!safeFunc) {
            // 对于有副作用的函数调用，添加指针参数到函数流入记录
            for (Value operand : call.getOperands()) {
                if (operand.getType().isPointerType()) {
                    if (!info.memFuncsIn.containsKey(operand)) {
                        info.memFuncsIn.put(operand, new LinkedHashSet<>());
                    }
                    info.memFuncsIn.get(operand).add(bb);
                }
            }
        }
        
        // 标记可能被函数修改的Store为已使用
        for (Value operand : call.getOperands()) {
            if (operand.getType().isPointerType()) {
                markConflictingStores(operand, info);
            }
        }
    }
    
    /**
     * 检查是否为库安全函数
     */
    private boolean isLibrarySafeFunction(CallInstruction call) {
        String funcName = call.getCallee().getName();
        if (funcName.equals("@memset") ||
               funcName.equals("@putarray") ||
               funcName.equals("@putfarray")) {
            return true;
        }
        // 仅当为外部函数且显式标注无副作用时，才认为安全。
        // 内部/用户函数保守视为可能有副作用。
        return call.getCallee().isExternal() && !call.getCallee().mayHaveSideEffect();
    }
    
    /**
     * 标记与指针冲突的Store为已使用
     */
    private void markConflictingStores(Value ptr, ArrayInfo info) {
        for (Value key : info.memIn.keySet()) {
            if (PointerAliasInspector.checkAlias(key, ptr) != PointerAliasInspector.AliasResult.NO) {
                info.memIn.get(key).forEach(store -> 
                    storeCount.put(store, storeCount.get(store) + 1));
            }
        }
    }
    
    /**
     * 删除未使用的Store指令
     */
    private void eliminateUnusedStores(Function function) {
        ArrayList<StoreInstruction> storesToRemove = new ArrayList<>();
        
        // 收集所有未使用的Store指令
        for (var entry : storeCount.entrySet()) {
            StoreInstruction store = entry.getKey();
            Integer count = entry.getValue();
            
            // 如果Store未被使用（计数为0），则可以删除
            if (count == 0 && store.getParent() != null) {
                // 检查Store的指针是否可能有副作用
                if (!mayHaveSideEffect(store)) {
                    storesToRemove.add(store);
                }
            }
        }
        
        // 删除未使用的Store指令
        for (StoreInstruction store : storesToRemove) {
            store.removeFromParent();
            needRepeat = true;
        }
    }
    
    /**
     * 检查Store指令是否可能有副作用
     */
    private boolean mayHaveSideEffect(StoreInstruction store) {
        Value pointer = store.getPointer();
        
        // 如果指针的根对象是全局变量，也认为具有副作用
        Value rootPtr = PointerAliasInspector.getRoot(pointer);
        if (rootPtr instanceof GlobalVariable) {
            return true;
        }

        // 对于GEP 结果（数组/结构成员等子元素）的 Store 保守认为有副作用，
        // 因为后续可能通过不同索引再次访问同一内存区域。
        if (pointer instanceof GetElementPtrInstruction) {
            return true;
        }
        
        // 函数参数(或其GEP) 的 Store 可能影响调用者，具有副作用
        if (rootPtr instanceof Argument) {
            return true;
        }
        
        // 涉及Phi指令的Store保守处理
        if (PointerAliasInspector.isPhiRelated(pointer)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查bb1是否支配bb2
     */
    private boolean dominates(BasicBlock bb1, BasicBlock bb2, Function function) {
        if (dominatorMap == null) {
            return false;
        }
        
        // bb1 支配 bb2 当且仅当 bb2 的支配集合中包含 bb1
        Set<BasicBlock> domSet = dominatorMap.get(bb2);
        return domSet != null && domSet.contains(bb1);
    }
    
    /**
     * 替换值的所有使用
     */
    private void replaceAllUses(Value oldValue, Value newValue) {
        ArrayList<User> users = new ArrayList<>(oldValue.getUsers());
        
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldValue) {
                    user.setOperand(i, newValue);
                }
            }
        }
    }
} 
package Backend.RegisterManager;

import Backend.Structure.AArch64Block;
import Backend.Structure.AArch64Function;
import Backend.Utils.LivenessAnalyzer;
import Backend.Utils.Pair;
import Backend.Value.Base.*;
import Backend.Value.Instruction.Arithmetic.*;
import Backend.Value.Instruction.DataMovement.*;
import Backend.Value.Instruction.Memory.*;
import Backend.Value.Operand.Constant.*;
import Backend.Value.Operand.Register.*;

import java.util.*;

/**
 * ARMv8寄存器分配器
 * 参考经典图着色算法，使用简化但有效的实现策略
 */
public class RegisterAllocator {
    
    private final AArch64Function currentFunction;
    private LinkedHashMap<AArch64Block, LivenessAnalyzer.LivenessInfo> livenessMap;
    
    // 工作列表
    private LinkedHashSet<AArch64Operand> simplifyList; // 低度数且非move相关的节点
    private LinkedHashSet<AArch64Operand> freezeList;   // 低度数且move相关的节点
    private LinkedHashSet<AArch64Operand> spillList;    // 高度数的节点
    private LinkedHashSet<AArch64Operand> spilledRegs;  // 需要溢出的节点
    private LinkedHashSet<AArch64Operand> coalescedRegs; // 已合并的节点
    private LinkedHashSet<AArch64Reg> coloredRegs;      // 已着色的节点
    private Stack<AArch64Operand> eliminationStack;     // 消除栈
    
    // move指令相关
    private LinkedHashSet<AArch64Instruction> coalescedMoves;   // 已合并的move
    private LinkedHashSet<AArch64Instruction> constrainedMoves; // 受约束的move
    private LinkedHashSet<AArch64Instruction> frozenMoves;     // 冻结的move
    private LinkedHashSet<AArch64Instruction> workingMoves;    // 工作中的move
    private LinkedHashSet<AArch64Instruction> activeMoves;     // 活跃的move
    
    // 图结构
    private LinkedHashSet<Pair<AArch64Reg, AArch64Reg>> conflictSet;         // 冲突边集合
    private LinkedHashMap<AArch64Operand, LinkedHashSet<AArch64Operand>> neighborList; // 邻接表
    private LinkedHashMap<AArch64Operand, Integer> degreeMap;              // 度数映射
    private LinkedHashMap<AArch64Operand, LinkedHashSet<AArch64Move>> moveMap; // move映射
    private LinkedHashMap<AArch64Operand, AArch64Operand> aliasMap;          // 别名映射
    private LinkedHashMap<AArch64Reg, Integer> colorMap;                   // 颜色映射
    private LinkedHashSet<AArch64VirReg> allVirtualRegs;                   // 所有虚拟寄存器
    
    // 常量定义
    private final int CPU_COLOR_COUNT = 8;   // 整型寄存器颜色数 (x8-x15)
    private final int FPU_COLOR_COUNT = 24;  // 浮点寄存器颜色数 (v8-v31)
    
    public RegisterAllocator(AArch64Function function) {
        this.currentFunction = function;
    }
    
    public void allocateRegisters() {
        System.out.println("\n===== 开始为函数 " + currentFunction.getName() + " 分配寄存器 =====");
        
        // 分别处理整型和浮点寄存器
        allocateForRegisterType(false); // 整型寄存器
        allocateForRegisterType(true);  // 浮点寄存器
        
        System.out.println("===== 函数 " + currentFunction.getName() + " 寄存器分配完成 =====\n");
    }
    
    private void allocateForRegisterType(boolean isFloat) {
        String regTypeName = isFloat ? "浮点" : "整型";
        int colorLimit = isFloat ? FPU_COLOR_COUNT : CPU_COLOR_COUNT;
        
        boolean needsSpill = true;
        int spillRound = 0;
        final int MAX_SPILL_ROUNDS = 5;
        
        while (needsSpill && spillRound < MAX_SPILL_ROUNDS) {
            spillRound++;
            System.out.println("第 " + spillRound + " 轮分配 " + regTypeName + " 寄存器");
            
            // 初始化数据结构
            initializeDataStructures();
            
            // 收集当前类型的虚拟寄存器
            collectVirtualRegisters(isFloat);
            
            // 构建干扰图
            constructInterferenceGraph(isFloat);
            
            // 创建工作列表
            buildWorkLists(colorLimit);
            
            // 执行图着色主循环
            do {
                if (!simplifyList.isEmpty()) {
                    performSimplify();
                } else if (!workingMoves.isEmpty()) {
                    performCoalesce();
                } else if (!freezeList.isEmpty()) {
                    performFreeze();
                } else if (!spillList.isEmpty()) {
                    selectForSpill();
                }
            } while (!simplifyList.isEmpty() || !workingMoves.isEmpty() || 
                     !freezeList.isEmpty() || !spillList.isEmpty());
            
            // 分配颜色
            needsSpill = !assignColors(colorLimit);
            
            if (!needsSpill) {
                // 重写程序
                rewriteProgram(isFloat);
            } else {
                // 处理溢出
                handleSpillRegisters(isFloat);
            }
        }
        
        if (spillRound >= MAX_SPILL_ROUNDS) {
            System.err.println("警告: " + regTypeName + " 寄存器分配达到最大轮数，可能存在问题");
        }
    }
    
    private void initializeDataStructures() {
        // 清空工作列表
        simplifyList = new LinkedHashSet<>();
        freezeList = new LinkedHashSet<>();
        spillList = new LinkedHashSet<>();
        spilledRegs = new LinkedHashSet<>();
        coalescedRegs = new LinkedHashSet<>();
        coloredRegs = new LinkedHashSet<>();
        eliminationStack = new Stack<>();
        
        // 清空move相关结构
        coalescedMoves = new LinkedHashSet<>();
        constrainedMoves = new LinkedHashSet<>();
        frozenMoves = new LinkedHashSet<>();
        workingMoves = new LinkedHashSet<>();
        activeMoves = new LinkedHashSet<>();
        
        // 清空图结构
        conflictSet = new LinkedHashSet<>();
        neighborList = new LinkedHashMap<>();
        degreeMap = new LinkedHashMap<>();
        moveMap = new LinkedHashMap<>();
        aliasMap = new LinkedHashMap<>();
        colorMap = new LinkedHashMap<>();
        allVirtualRegs = new LinkedHashSet<>();
        
        // 计算活跃性信息
        livenessMap = LivenessAnalyzer.analyzeLiveness(currentFunction);
    }
    
    private void collectVirtualRegisters(boolean isFloat) {
        for (AArch64Block block : currentFunction.getBlocks()) {
            for (AArch64Instruction inst : block.getInstructions()) {
                // 收集定义的虚拟寄存器
                if (inst.getDefReg() instanceof AArch64VirReg) {
                    AArch64VirReg virReg = (AArch64VirReg) inst.getDefReg();
                    if (virReg.isFloat() == isFloat) {
                        allVirtualRegs.add(virReg);
                        ensureNodeExists(virReg);
                    }
                }
                
                // 收集使用的虚拟寄存器
                for (AArch64Operand operand : inst.getOperands()) {
                    if (operand instanceof AArch64VirReg) {
                        AArch64VirReg virReg = (AArch64VirReg) operand;
                        if (virReg.isFloat() == isFloat) {
                            allVirtualRegs.add(virReg);
                            ensureNodeExists(virReg);
                        }
                    }
                }
            }
        }
        
        System.out.println("收集到 " + allVirtualRegs.size() + " 个虚拟寄存器");
    }
    
    private void constructInterferenceGraph(boolean isFloat) {
        for (AArch64Block block : currentFunction.getBlocks()) {
            LivenessAnalyzer.LivenessInfo blockLiveness = livenessMap.get(block);
            if (blockLiveness == null) continue;
            
            LinkedHashSet<AArch64Reg> currentLive = new LinkedHashSet<>();
            // 只处理当前类型的寄存器
            for (AArch64Reg reg : blockLiveness.getLiveOut()) {
                if (reg instanceof AArch64VirReg && ((AArch64VirReg) reg).isFloat() == isFloat) {
                    currentLive.add(reg);
                }
            }
            
            // 反向遍历指令
            List<AArch64Instruction> instructions = new ArrayList<>(block.getInstructions());
            Collections.reverse(instructions);
            
            for (AArch64Instruction inst : instructions) {
                // 处理move指令的特殊情况
                if (inst instanceof AArch64Move) {
                    AArch64Move moveInst = (AArch64Move) inst;
                    if (moveInst.getOperands().size() > 0 && 
                        moveInst.getOperands().get(0) instanceof AArch64Reg &&
                        moveInst.getDefReg() instanceof AArch64Reg) {
                        
                        AArch64Reg src = (AArch64Reg) moveInst.getOperands().get(0);
                        AArch64Reg dst = moveInst.getDefReg();
                        
                        if (src instanceof AArch64VirReg && dst instanceof AArch64VirReg &&
                            ((AArch64VirReg) src).isFloat() == isFloat &&
                            ((AArch64VirReg) dst).isFloat() == isFloat) {
                            currentLive.remove(src);
                            addToMoveList(src, moveInst);
                            addToMoveList(dst, moveInst);
                            workingMoves.add(moveInst);
                        }
                    }
                }
                
                // 为定义的寄存器添加干扰
                if (inst.getDefReg() instanceof AArch64VirReg) {
                    AArch64VirReg defReg = (AArch64VirReg) inst.getDefReg();
                    if (defReg.isFloat() == isFloat) {
                        ensureNodeExists(defReg);
                        
                        for (AArch64Reg liveReg : currentLive) {
                            if (!liveReg.equals(defReg)) {
                                addConflictEdge(defReg, liveReg);
                            }
                        }
                        
                        currentLive.remove(defReg);
                    }
                }
                
                // 添加使用的寄存器到活跃集合
                for (AArch64Operand operand : inst.getOperands()) {
                    if (operand instanceof AArch64VirReg) {
                        AArch64VirReg virReg = (AArch64VirReg) operand;
                        if (virReg.isFloat() == isFloat) {
                            ensureNodeExists(virReg);
                            currentLive.add(virReg);
                        }
                    }
                }
            }
        }
        
        System.out.println("干扰图构建完成，冲突边数: " + conflictSet.size());
    }
    
    private void ensureNodeExists(AArch64Reg reg) {
        if (!neighborList.containsKey(reg)) {
            neighborList.put(reg, new LinkedHashSet<>());
            degreeMap.put(reg, 0);
            moveMap.put(reg, new LinkedHashSet<>());
            aliasMap.put(reg, reg);
        }
    }
    
    private void addToMoveList(AArch64Reg reg, AArch64Move moveInst) {
        ensureNodeExists(reg);
        if (!moveMap.containsKey(reg)) {
            moveMap.put(reg, new LinkedHashSet<>());
        }
        moveMap.get(reg).add(moveInst);
    }
    
    private void addConflictEdge(AArch64Reg u, AArch64Reg v) {
        if (u.equals(v)) return;
        
        Pair<AArch64Reg, AArch64Reg> edge1 = new Pair<>(u, v);
        Pair<AArch64Reg, AArch64Reg> edge2 = new Pair<>(v, u);
        
        if (!conflictSet.contains(edge1) && !conflictSet.contains(edge2)) {
            conflictSet.add(edge1);
            
            if (!neighborList.get(u).contains(v)) {
                neighborList.get(u).add(v);
                neighborList.get(v).add(u);
                
                degreeMap.put(u, degreeMap.get(u) + 1);
                degreeMap.put(v, degreeMap.get(v) + 1);
            }
        }
    }
    
    private void buildWorkLists(int colorLimit) {
        for (AArch64Operand operand : neighborList.keySet()) {
            if (operand instanceof AArch64VirReg) {
                int degree = degreeMap.get(operand);
                if (degree >= colorLimit) {
                    spillList.add(operand);
                } else if (isMoveRelated(operand)) {
                    freezeList.add(operand);
                } else {
                    simplifyList.add(operand);
                }
            }
        }
    }
    
    private void performSimplify() {
        AArch64Operand node = simplifyList.iterator().next();
        simplifyList.remove(node);
        eliminationStack.push(node);
        
        for (AArch64Operand neighbor : getAdjacentNodes(node)) {
            decreaseDegree(neighbor);
        }
    }
    
    private void performCoalesce() {
        AArch64Instruction moveInst = workingMoves.iterator().next();
        workingMoves.remove(moveInst);
        
        if (!(moveInst instanceof AArch64Move)) return;
        AArch64Move move = (AArch64Move) moveInst;
        
        if (move.getOperands().size() == 0 || !(move.getOperands().get(0) instanceof AArch64Reg)) {
            return;
        }
        
        AArch64Reg x = (AArch64Reg) move.getOperands().get(0);
        AArch64Reg y = move.getDefReg();
        
        x = (AArch64Reg) getActualAlias(x);
        y = (AArch64Reg) getActualAlias(y);
        
        AArch64Reg u, v;
        if (y instanceof AArch64PhyReg) {
            u = y;
            v = x;
        } else {
            u = x;
            v = y;
        }
        
        if (u.equals(v)) {
            coalescedMoves.add(moveInst);
            addToWorkList(u);
        } else if (v instanceof AArch64PhyReg || hasConflict(u, v)) {
            constrainedMoves.add(moveInst);
            addToWorkList(u);
            addToWorkList(v);
        } else {
            coalescedMoves.add(moveInst);
            addToWorkList(u);
        }
    }
    
    private void performFreeze() {
        AArch64Operand node = freezeList.iterator().next();
        freezeList.remove(node);
        simplifyList.add(node);
        freezeNodeMoves(node);
    }
    
    private void selectForSpill() {
        AArch64Operand spillCandidate = chooseSpillCandidate();
        spillList.remove(spillCandidate);
        simplifyList.add(spillCandidate);
        freezeNodeMoves(spillCandidate);
    }
    
    private boolean assignColors(int colorLimit) {
        Set<Integer> availableColors = new HashSet<>();
        for (int i = 0; i < colorLimit; i++) {
            availableColors.add(i);
        }
        
        while (!eliminationStack.isEmpty()) {
            AArch64Operand node = eliminationStack.pop();
            
            if (node instanceof AArch64VirReg) {
                Set<Integer> forbiddenColors = new HashSet<>();
                
                for (AArch64Operand neighbor : neighborList.get(node)) {
                    AArch64Operand actualNeighbor = getActualAlias(neighbor);
                    if (coloredRegs.contains(actualNeighbor) || actualNeighbor instanceof AArch64PhyReg) {
                        Integer color = colorMap.get(actualNeighbor);
                        if (color != null) {
                            forbiddenColors.add(color);
                        }
                    }
                }
                
                Set<Integer> okColors = new HashSet<>(availableColors);
                okColors.removeAll(forbiddenColors);
                
                if (okColors.isEmpty()) {
                    spilledRegs.add(node);
                    System.out.println("寄存器 " + node + " 溢出");
                } else {
                    int chosenColor = okColors.iterator().next();
                    colorMap.put((AArch64Reg) node, chosenColor);
                    coloredRegs.add((AArch64Reg) node);
                    System.out.println("为寄存器 " + node + " 分配颜色 " + chosenColor);
                }
            }
        }
        
        // 为合并的节点分配颜色
        for (AArch64Operand node : coalescedRegs) {
            AArch64Operand alias = getActualAlias(node);
            if (colorMap.containsKey(alias)) {
                colorMap.put((AArch64Reg) node, colorMap.get(alias));
                System.out.println("合并节点 " + node + " 继承颜色 " + colorMap.get(alias));
            }
        }
        
        System.out.println("颜色分配完成，溢出节点数: " + spilledRegs.size());
        return spilledRegs.isEmpty();
    }
    
    private void rewriteProgram(boolean isFloat) {
        System.out.println("开始重写程序...");
        int replaceCount = 0;
        
        for (AArch64Block block : currentFunction.getBlocks()) {
            for (AArch64Instruction inst : block.getInstructions()) {
                // 替换定义寄存器
                if (inst.getDefReg() instanceof AArch64VirReg) {
                    AArch64VirReg virReg = (AArch64VirReg) inst.getDefReg();
                    if (virReg.isFloat() == isFloat && colorMap.containsKey(virReg)) {
                        AArch64PhyReg phyReg = getPhysicalRegisterFromColor(virReg, colorMap.get(virReg));
                        if (phyReg != null) {
                            inst.replaceDefReg(phyReg);
                            replaceCount++;
                        }
                    }
                }
                
                // 替换操作数寄存器
                List<AArch64Operand> operands = inst.getOperands();
                for (int i = 0; i < operands.size(); i++) {
                    AArch64Operand operand = operands.get(i);
                    if (operand instanceof AArch64VirReg) {
                        AArch64VirReg virReg = (AArch64VirReg) operand;
                        if (virReg.isFloat() == isFloat && colorMap.containsKey(virReg)) {
                            AArch64PhyReg phyReg = getPhysicalRegisterFromColor(virReg, colorMap.get(virReg));
                            if (phyReg != null) {
                                inst.replaceOperands(virReg, phyReg);
                                replaceCount++;
                            }
                        }
                    }
                }
            }
        }
        
        System.out.println("程序重写完成，替换了 " + replaceCount + " 个寄存器");
    }
    
    private void handleSpillRegisters(boolean isFloat) {
        System.out.println("处理溢出寄存器，数量: " + spilledRegs.size());
        
        for (AArch64Operand spilledOperand : spilledRegs) {
            if (!(spilledOperand instanceof AArch64VirReg)) continue;
            
            AArch64VirReg spilledReg = (AArch64VirReg) spilledOperand;
            if (spilledReg.isFloat() != isFloat) continue;
            
            long stackOffset = currentFunction.getStackSize();
            currentFunction.addStack(null, 8L);
            
            System.out.println("为溢出寄存器 " + spilledReg + " 分配栈偏移: " + stackOffset);
            rewriteSpilledRegisterUsage(spilledReg, stackOffset);
        }
        
        System.out.println("溢出处理完成");
    }
    
    private void rewriteSpilledRegisterUsage(AArch64VirReg spilledReg, long offset) {
        for (AArch64Block block : currentFunction.getBlocks()) {
            List<AArch64Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (int i = 0; i < instructions.size(); i++) {
                AArch64Instruction inst = instructions.get(i);
                boolean hasSpilledUse = false;
                boolean hasSpilledDef = false;
                
                // 检查使用
                for (AArch64Operand operand : inst.getOperands()) {
                    if (operand.equals(spilledReg)) {
                        hasSpilledUse = true;
                        break;
                    }
                }
                
                // 检查定义
                if (spilledReg.equals(inst.getDefReg())) {
                    hasSpilledDef = true;
                }
                
                if (hasSpilledUse || hasSpilledDef) {
                    if (hasSpilledUse) {
                        // 插入加载指令
                        AArch64VirReg tempReg = new AArch64VirReg(spilledReg.isFloat());
                        if (offset >= -256 && offset <= 255) {
                            // 在有符号偏移范围内
                            AArch64Load loadInst = new AArch64Load(AArch64CPUReg.getAArch64SpReg(), new AArch64Imm(offset), tempReg);
                            block.insertBeforeInst(inst, loadInst);
                        } else if (offset >= 0 && offset <= 32760 && (offset % 8 == 0)) {
                            // 在无符号偏移范围内
                            AArch64Load loadInst = new AArch64Load(AArch64CPUReg.getAArch64SpReg(), new AArch64Imm(offset), tempReg);
                            block.insertBeforeInst(inst, loadInst);
                        } else {
                            // 超出范围，分解为ADD+LOAD
                            AArch64VirReg addrReg = new AArch64VirReg(false);
                            // 加载偏移量到寄存器
                            RegisterAllocatorHelper.loadLargeImmToReg(block, inst, addrReg, offset, false);
                            // 计算地址
                            ArrayList<AArch64Operand> addOps = new ArrayList<>();
                            addOps.add(AArch64CPUReg.getAArch64SpReg());
                            addOps.add(addrReg);
                            AArch64Binary addInst = new AArch64Binary(addOps, addrReg, AArch64Binary.AArch64BinaryType.add);
                            block.insertBeforeInst(inst, addInst);
                            // 使用零偏移加载
                            AArch64Load loadInst = new AArch64Load(addrReg, new AArch64Imm(0), tempReg);
                            block.insertBeforeInst(inst, loadInst);
                        }
                        inst.replaceOperands(spilledReg, tempReg);
                    }
                    
                    if (hasSpilledDef) {
                        // 创建临时寄存器并插入存储指令
                        AArch64VirReg tempReg = new AArch64VirReg(spilledReg.isFloat());
                        inst.replaceDefReg(tempReg);
                        
                        if (offset >= -256 && offset <= 255) {
                            // 在有符号偏移范围内
                            AArch64Store storeInst = new AArch64Store(tempReg, AArch64CPUReg.getAArch64SpReg(), new AArch64Imm(offset));
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, storeInst);
                        } else if (offset >= 0 && offset <= 32760 && (offset % 8 == 0)) {
                            // 在无符号偏移范围内
                            AArch64Store storeInst = new AArch64Store(tempReg, AArch64CPUReg.getAArch64SpReg(), new AArch64Imm(offset));
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, storeInst);
                        } else {
                            // 超出范围，分解为ADD+STORE
                            AArch64VirReg addrReg = new AArch64VirReg(false);
                            // 加载偏移量到寄存器
                            RegisterAllocatorHelper.loadLargeImmToReg(block, inst, addrReg, offset, true);
                            // 计算地址
                            ArrayList<AArch64Operand> addOps = new ArrayList<>();
                            addOps.add(AArch64CPUReg.getAArch64SpReg());
                            addOps.add(addrReg);
                            AArch64Binary addInst = new AArch64Binary(addOps, addrReg, AArch64Binary.AArch64BinaryType.add);
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, addInst);
                            // 使用零偏移存储
                            AArch64Store storeInst = new AArch64Store(tempReg, addrReg, new AArch64Imm(0));
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, storeInst);
                        }
                    }
                }
            }
        }
    }
    
    // 辅助方法
    
    private boolean isMoveRelated(AArch64Operand operand) {
        return !getNodeMoveInstructions(operand).isEmpty();
    }
    
    private LinkedHashSet<AArch64Instruction> getNodeMoveInstructions(AArch64Operand operand) {
        LinkedHashSet<AArch64Instruction> moves = new LinkedHashSet<>();
        if (moveMap.containsKey(operand)) {
            for (AArch64Move move : moveMap.get(operand)) {
                if (activeMoves.contains(move) || workingMoves.contains(move)) {
                    moves.add(move);
                }
            }
        }
        return moves;
    }
    
    private LinkedHashSet<AArch64Operand> getAdjacentNodes(AArch64Operand operand) {
        LinkedHashSet<AArch64Operand> result = new LinkedHashSet<>();
        if (neighborList.containsKey(operand)) {
            for (AArch64Operand neighbor : neighborList.get(operand)) {
                if (!eliminationStack.contains(neighbor) && !coalescedRegs.contains(neighbor)) {
                    result.add(neighbor);
                }
            }
        }
        return result;
    }
    
    private void decreaseDegree(AArch64Operand operand) {
        if (!(operand instanceof AArch64VirReg)) return;
        
        int currentDegree = degreeMap.get(operand);
        degreeMap.put(operand, currentDegree - 1);
    }
    
    private AArch64Operand getActualAlias(AArch64Operand operand) {
        if (coalescedRegs.contains(operand)) {
            return getActualAlias(aliasMap.get(operand));
        }
        return operand;
    }
    
    private void addToWorkList(AArch64Reg reg) {
        // 简化的实现
    }
    
    private boolean hasConflict(AArch64Reg u, AArch64Reg v) {
        return conflictSet.contains(new Pair<>(u, v)) || conflictSet.contains(new Pair<>(v, u));
    }
    
    private void freezeNodeMoves(AArch64Operand u) {
        for (AArch64Instruction moveInst : new LinkedHashSet<>(getNodeMoveInstructions(u))) {
            activeMoves.remove(moveInst);
            frozenMoves.add(moveInst);
        }
    }
    
    private AArch64Operand chooseSpillCandidate() {
        // 简单的溢出选择策略：选择度数最高的
        AArch64Operand candidate = null;
        int maxDegree = -1;
        
        for (AArch64Operand operand : spillList) {
            if (degreeMap.containsKey(operand) && degreeMap.get(operand) > maxDegree) {
                maxDegree = degreeMap.get(operand);
                candidate = operand;
            }
        }
        
        return candidate != null ? candidate : spillList.iterator().next();
    }
    
    private AArch64PhyReg getPhysicalRegisterFromColor(AArch64VirReg virReg, int color) {
        if (virReg.isFloat()) {
            // 浮点寄存器: v8-v31
            if (color >= 0 && color < FPU_COLOR_COUNT) {
                return AArch64FPUReg.getAArch64FloatReg(color + 8);
            }
        } else {
            // 整型寄存器: x8-x15
            if (color >= 0 && color < CPU_COLOR_COUNT) {
                return AArch64CPUReg.getAArch64CPUReg(color + 19);
            }
        }
        
        System.err.println("无法为寄存器 " + virReg + " 获取物理寄存器，颜色: " + color);
        return null;
    }
} 
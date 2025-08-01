package Backend.Armv8.tools;

import Backend.Armv8.Instruction.*;
import Backend.Armv8.Operand.*;
import Backend.Armv8.Structure.*;
import java.util.*;

/**
 * ARMv8寄存器分配器
 * 参考经典图着色算法，使用简化但有效的实现策略
 */
public class RegisterAllocator {
    
    private final Armv8Function currentFunction;
    private LinkedHashMap<Armv8Block, LivenessAnalyzer.LivenessInfo> livenessMap;
    
    // 工作列表
    private LinkedHashSet<Armv8Operand> simplifyList; // 低度数且非move相关的节点
    private LinkedHashSet<Armv8Operand> freezeList;   // 低度数且move相关的节点
    private LinkedHashSet<Armv8Operand> spillList;    // 高度数的节点
    private LinkedHashSet<Armv8Operand> spilledRegs;  // 需要溢出的节点
    private LinkedHashSet<Armv8Operand> coalescedRegs; // 已合并的节点
    private LinkedHashSet<Armv8Reg> coloredRegs;      // 已着色的节点
    private Stack<Armv8Operand> eliminationStack;     // 消除栈
    
    // move指令相关
    private LinkedHashSet<Armv8Instruction> coalescedMoves;   // 已合并的move
    private LinkedHashSet<Armv8Instruction> constrainedMoves; // 受约束的move
    private LinkedHashSet<Armv8Instruction> frozenMoves;     // 冻结的move
    private LinkedHashSet<Armv8Instruction> workingMoves;    // 工作中的move
    private LinkedHashSet<Armv8Instruction> activeMoves;     // 活跃的move
    
    // 图结构
    private LinkedHashSet<Pair<Armv8Reg, Armv8Reg>> conflictSet;         // 冲突边集合
    private LinkedHashMap<Armv8Operand, LinkedHashSet<Armv8Operand>> neighborList; // 邻接表
    private LinkedHashMap<Armv8Operand, Integer> degreeMap;              // 度数映射
    private LinkedHashMap<Armv8Operand, LinkedHashSet<Armv8Move>> moveMap; // move映射
    private LinkedHashMap<Armv8Operand, Armv8Operand> aliasMap;          // 别名映射
    private LinkedHashMap<Armv8Reg, Integer> colorMap;                   // 颜色映射
    private LinkedHashSet<Armv8VirReg> allVirtualRegs;                   // 所有虚拟寄存器
    
    // 常量定义
    private final int CPU_COLOR_COUNT = 8;   // 整型寄存器颜色数 (x8-x15)
    private final int FPU_COLOR_COUNT = 24;  // 浮点寄存器颜色数 (v8-v31)
    
    public RegisterAllocator(Armv8Function function) {
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
        for (Armv8Block block : currentFunction.getBlocks()) {
            for (Armv8Instruction inst : block.getInstructions()) {
                // 收集定义的虚拟寄存器
                if (inst.getDefReg() instanceof Armv8VirReg) {
                    Armv8VirReg virReg = (Armv8VirReg) inst.getDefReg();
                    if (virReg.isFloat() == isFloat) {
                        allVirtualRegs.add(virReg);
                        ensureNodeExists(virReg);
                    }
                }
                
                // 收集使用的虚拟寄存器
                for (Armv8Operand operand : inst.getOperands()) {
                    if (operand instanceof Armv8VirReg) {
                        Armv8VirReg virReg = (Armv8VirReg) operand;
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
        for (Armv8Block block : currentFunction.getBlocks()) {
            LivenessAnalyzer.LivenessInfo blockLiveness = livenessMap.get(block);
            if (blockLiveness == null) continue;
            
            LinkedHashSet<Armv8Reg> currentLive = new LinkedHashSet<>();
            // 只处理当前类型的寄存器
            for (Armv8Reg reg : blockLiveness.getLiveOut()) {
                if (reg instanceof Armv8VirReg && ((Armv8VirReg) reg).isFloat() == isFloat) {
                    currentLive.add(reg);
                }
            }
            
            // 反向遍历指令
            List<Armv8Instruction> instructions = new ArrayList<>(block.getInstructions());
            Collections.reverse(instructions);
            
            for (Armv8Instruction inst : instructions) {
                // 处理move指令的特殊情况
                if (inst instanceof Armv8Move) {
                    Armv8Move moveInst = (Armv8Move) inst;
                    if (moveInst.getOperands().size() > 0 && 
                        moveInst.getOperands().get(0) instanceof Armv8Reg &&
                        moveInst.getDefReg() instanceof Armv8Reg) {
                        
                        Armv8Reg src = (Armv8Reg) moveInst.getOperands().get(0);
                        Armv8Reg dst = moveInst.getDefReg();
                        
                        if (src instanceof Armv8VirReg && dst instanceof Armv8VirReg &&
                            ((Armv8VirReg) src).isFloat() == isFloat &&
                            ((Armv8VirReg) dst).isFloat() == isFloat) {
                            currentLive.remove(src);
                            addToMoveList(src, moveInst);
                            addToMoveList(dst, moveInst);
                            workingMoves.add(moveInst);
                        }
                    }
                }
                
                // 为定义的寄存器添加干扰
                if (inst.getDefReg() instanceof Armv8VirReg) {
                    Armv8VirReg defReg = (Armv8VirReg) inst.getDefReg();
                    if (defReg.isFloat() == isFloat) {
                        ensureNodeExists(defReg);
                        
                        for (Armv8Reg liveReg : currentLive) {
                            if (!liveReg.equals(defReg)) {
                                addConflictEdge(defReg, liveReg);
                            }
                        }
                        
                        currentLive.remove(defReg);
                    }
                }
                
                // 添加使用的寄存器到活跃集合
                for (Armv8Operand operand : inst.getOperands()) {
                    if (operand instanceof Armv8VirReg) {
                        Armv8VirReg virReg = (Armv8VirReg) operand;
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
    
    private void ensureNodeExists(Armv8Reg reg) {
        if (!neighborList.containsKey(reg)) {
            neighborList.put(reg, new LinkedHashSet<>());
            degreeMap.put(reg, 0);
            moveMap.put(reg, new LinkedHashSet<>());
            aliasMap.put(reg, reg);
        }
    }
    
    private void addToMoveList(Armv8Reg reg, Armv8Move moveInst) {
        ensureNodeExists(reg);
        if (!moveMap.containsKey(reg)) {
            moveMap.put(reg, new LinkedHashSet<>());
        }
        moveMap.get(reg).add(moveInst);
    }
    
    private void addConflictEdge(Armv8Reg u, Armv8Reg v) {
        if (u.equals(v)) return;
        
        Pair<Armv8Reg, Armv8Reg> edge1 = new Pair<>(u, v);
        Pair<Armv8Reg, Armv8Reg> edge2 = new Pair<>(v, u);
        
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
        for (Armv8Operand operand : neighborList.keySet()) {
            if (operand instanceof Armv8VirReg) {
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
        Armv8Operand node = simplifyList.iterator().next();
        simplifyList.remove(node);
        eliminationStack.push(node);
        
        for (Armv8Operand neighbor : getAdjacentNodes(node)) {
            decreaseDegree(neighbor);
        }
    }
    
    private void performCoalesce() {
        Armv8Instruction moveInst = workingMoves.iterator().next();
        workingMoves.remove(moveInst);
        
        if (!(moveInst instanceof Armv8Move)) return;
        Armv8Move move = (Armv8Move) moveInst;
        
        if (move.getOperands().size() == 0 || !(move.getOperands().get(0) instanceof Armv8Reg)) {
            return;
        }
        
        Armv8Reg x = (Armv8Reg) move.getOperands().get(0);
        Armv8Reg y = move.getDefReg();
        
        x = (Armv8Reg) getActualAlias(x);
        y = (Armv8Reg) getActualAlias(y);
        
        Armv8Reg u, v;
        if (y instanceof Armv8PhyReg) {
            u = y;
            v = x;
        } else {
            u = x;
            v = y;
        }
        
        if (u.equals(v)) {
            coalescedMoves.add(moveInst);
            addToWorkList(u);
        } else if (v instanceof Armv8PhyReg || hasConflict(u, v)) {
            constrainedMoves.add(moveInst);
            addToWorkList(u);
            addToWorkList(v);
        } else {
            coalescedMoves.add(moveInst);
            addToWorkList(u);
        }
    }
    
    private void performFreeze() {
        Armv8Operand node = freezeList.iterator().next();
        freezeList.remove(node);
        simplifyList.add(node);
        freezeNodeMoves(node);
    }
    
    private void selectForSpill() {
        Armv8Operand spillCandidate = chooseSpillCandidate();
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
            Armv8Operand node = eliminationStack.pop();
            
            if (node instanceof Armv8VirReg) {
                Set<Integer> forbiddenColors = new HashSet<>();
                
                for (Armv8Operand neighbor : neighborList.get(node)) {
                    Armv8Operand actualNeighbor = getActualAlias(neighbor);
                    if (coloredRegs.contains(actualNeighbor) || actualNeighbor instanceof Armv8PhyReg) {
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
                    colorMap.put((Armv8Reg) node, chosenColor);
                    coloredRegs.add((Armv8Reg) node);
                    System.out.println("为寄存器 " + node + " 分配颜色 " + chosenColor);
                }
            }
        }
        
        // 为合并的节点分配颜色
        for (Armv8Operand node : coalescedRegs) {
            Armv8Operand alias = getActualAlias(node);
            if (colorMap.containsKey(alias)) {
                colorMap.put((Armv8Reg) node, colorMap.get(alias));
                System.out.println("合并节点 " + node + " 继承颜色 " + colorMap.get(alias));
            }
        }
        
        System.out.println("颜色分配完成，溢出节点数: " + spilledRegs.size());
        return spilledRegs.isEmpty();
    }
    
    private void rewriteProgram(boolean isFloat) {
        System.out.println("开始重写程序...");
        int replaceCount = 0;
        
        for (Armv8Block block : currentFunction.getBlocks()) {
            for (Armv8Instruction inst : block.getInstructions()) {
                // 替换定义寄存器
                if (inst.getDefReg() instanceof Armv8VirReg) {
                    Armv8VirReg virReg = (Armv8VirReg) inst.getDefReg();
                    if (virReg.isFloat() == isFloat && colorMap.containsKey(virReg)) {
                        Armv8PhyReg phyReg = getPhysicalRegisterFromColor(virReg, colorMap.get(virReg));
                        if (phyReg != null) {
                            inst.replaceDefReg(phyReg);
                            replaceCount++;
                        }
                    }
                }
                
                // 替换操作数寄存器
                List<Armv8Operand> operands = inst.getOperands();
                for (int i = 0; i < operands.size(); i++) {
                    Armv8Operand operand = operands.get(i);
                    if (operand instanceof Armv8VirReg) {
                        Armv8VirReg virReg = (Armv8VirReg) operand;
                        if (virReg.isFloat() == isFloat && colorMap.containsKey(virReg)) {
                            Armv8PhyReg phyReg = getPhysicalRegisterFromColor(virReg, colorMap.get(virReg));
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
        
        for (Armv8Operand spilledOperand : spilledRegs) {
            if (!(spilledOperand instanceof Armv8VirReg)) continue;
            
            Armv8VirReg spilledReg = (Armv8VirReg) spilledOperand;
            if (spilledReg.isFloat() != isFloat) continue;
            
            long stackOffset = currentFunction.getStackSize();
            currentFunction.addStack(null, 8L);
            
            System.out.println("为溢出寄存器 " + spilledReg + " 分配栈偏移: " + stackOffset);
            rewriteSpilledRegisterUsage(spilledReg, stackOffset);
        }
        
        System.out.println("溢出处理完成");
    }
    
    private void rewriteSpilledRegisterUsage(Armv8VirReg spilledReg, long offset) {
        for (Armv8Block block : currentFunction.getBlocks()) {
            List<Armv8Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (int i = 0; i < instructions.size(); i++) {
                Armv8Instruction inst = instructions.get(i);
                boolean hasSpilledUse = false;
                boolean hasSpilledDef = false;
                
                // 检查使用
                for (Armv8Operand operand : inst.getOperands()) {
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
                        Armv8VirReg tempReg = new Armv8VirReg(spilledReg.isFloat());
                        if (offset >= -256 && offset <= 255) {
                            // 在有符号偏移范围内
                            Armv8Load loadInst = new Armv8Load(Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(offset), tempReg);
                            block.insertBeforeInst(inst, loadInst);
                        } else if (offset >= 0 && offset <= 32760 && (offset % 8 == 0)) {
                            // 在无符号偏移范围内
                            Armv8Load loadInst = new Armv8Load(Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(offset), tempReg);
                            block.insertBeforeInst(inst, loadInst);
                        } else {
                            // 超出范围，分解为ADD+LOAD
                            Armv8VirReg addrReg = new Armv8VirReg(false);
                            // 加载偏移量到寄存器
                            RegisterAllocatorHelper.loadLargeImmToReg(block, inst, addrReg, offset, false);
                            // 计算地址
                            ArrayList<Armv8Operand> addOps = new ArrayList<>();
                            addOps.add(Armv8CPUReg.getArmv8SpReg());
                            addOps.add(addrReg);
                            Armv8Binary addInst = new Armv8Binary(addOps, addrReg, Armv8Binary.Armv8BinaryType.add);
                            block.insertBeforeInst(inst, addInst);
                            // 使用零偏移加载
                            Armv8Load loadInst = new Armv8Load(addrReg, new Armv8Imm(0), tempReg);
                            block.insertBeforeInst(inst, loadInst);
                        }
                        inst.replaceOperands(spilledReg, tempReg);
                    }
                    
                    if (hasSpilledDef) {
                        // 创建临时寄存器并插入存储指令
                        Armv8VirReg tempReg = new Armv8VirReg(spilledReg.isFloat());
                        inst.replaceDefReg(tempReg);
                        
                        if (offset >= -256 && offset <= 255) {
                            // 在有符号偏移范围内
                            Armv8Store storeInst = new Armv8Store(tempReg, Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(offset));
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, storeInst);
                        } else if (offset >= 0 && offset <= 32760 && (offset % 8 == 0)) {
                            // 在无符号偏移范围内
                            Armv8Store storeInst = new Armv8Store(tempReg, Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(offset));
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, storeInst);
                        } else {
                            // 超出范围，分解为ADD+STORE
                            Armv8VirReg addrReg = new Armv8VirReg(false);
                            // 加载偏移量到寄存器
                            RegisterAllocatorHelper.loadLargeImmToReg(block, inst, addrReg, offset, true);
                            // 计算地址
                            ArrayList<Armv8Operand> addOps = new ArrayList<>();
                            addOps.add(Armv8CPUReg.getArmv8SpReg());
                            addOps.add(addrReg);
                            Armv8Binary addInst = new Armv8Binary(addOps, addrReg, Armv8Binary.Armv8BinaryType.add);
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, addInst);
                            // 使用零偏移存储
                            Armv8Store storeInst = new Armv8Store(tempReg, addrReg, new Armv8Imm(0));
                            RegisterAllocatorHelper.insertAfterInstruction(block, inst, storeInst);
                        }
                    }
                }
            }
        }
    }
    
    // 辅助方法
    
    private boolean isMoveRelated(Armv8Operand operand) {
        return !getNodeMoveInstructions(operand).isEmpty();
    }
    
    private LinkedHashSet<Armv8Instruction> getNodeMoveInstructions(Armv8Operand operand) {
        LinkedHashSet<Armv8Instruction> moves = new LinkedHashSet<>();
        if (moveMap.containsKey(operand)) {
            for (Armv8Move move : moveMap.get(operand)) {
                if (activeMoves.contains(move) || workingMoves.contains(move)) {
                    moves.add(move);
                }
            }
        }
        return moves;
    }
    
    private LinkedHashSet<Armv8Operand> getAdjacentNodes(Armv8Operand operand) {
        LinkedHashSet<Armv8Operand> result = new LinkedHashSet<>();
        if (neighborList.containsKey(operand)) {
            for (Armv8Operand neighbor : neighborList.get(operand)) {
                if (!eliminationStack.contains(neighbor) && !coalescedRegs.contains(neighbor)) {
                    result.add(neighbor);
                }
            }
        }
        return result;
    }
    
    private void decreaseDegree(Armv8Operand operand) {
        if (!(operand instanceof Armv8VirReg)) return;
        
        int currentDegree = degreeMap.get(operand);
        degreeMap.put(operand, currentDegree - 1);
    }
    
    private Armv8Operand getActualAlias(Armv8Operand operand) {
        if (coalescedRegs.contains(operand)) {
            return getActualAlias(aliasMap.get(operand));
        }
        return operand;
    }
    
    private void addToWorkList(Armv8Reg reg) {
        // 简化的实现
    }
    
    private boolean hasConflict(Armv8Reg u, Armv8Reg v) {
        return conflictSet.contains(new Pair<>(u, v)) || conflictSet.contains(new Pair<>(v, u));
    }
    
    private void freezeNodeMoves(Armv8Operand u) {
        for (Armv8Instruction moveInst : new LinkedHashSet<>(getNodeMoveInstructions(u))) {
            activeMoves.remove(moveInst);
            frozenMoves.add(moveInst);
        }
    }
    
    private Armv8Operand chooseSpillCandidate() {
        // 简单的溢出选择策略：选择度数最高的
        Armv8Operand candidate = null;
        int maxDegree = -1;
        
        for (Armv8Operand operand : spillList) {
            if (degreeMap.containsKey(operand) && degreeMap.get(operand) > maxDegree) {
                maxDegree = degreeMap.get(operand);
                candidate = operand;
            }
        }
        
        return candidate != null ? candidate : spillList.iterator().next();
    }
    
    private Armv8PhyReg getPhysicalRegisterFromColor(Armv8VirReg virReg, int color) {
        if (virReg.isFloat()) {
            // 浮点寄存器: v8-v31
            if (color >= 0 && color < FPU_COLOR_COUNT) {
                return Armv8FPUReg.getArmv8FloatReg(color + 8);
            }
        } else {
            // 整型寄存器: x8-x15
            if (color >= 0 && color < CPU_COLOR_COUNT) {
                return Armv8CPUReg.getArmv8CPUReg(color + 8);
            }
        }
        
        System.err.println("无法为寄存器 " + virReg + " 获取物理寄存器，颜色: " + color);
        return null;
    }
} 
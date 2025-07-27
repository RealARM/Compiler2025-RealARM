package Backend.Armv8.tools;

import Backend.Armv8.Instruction.*;
import Backend.Armv8.Operand.*;
import Backend.Armv8.Structure.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 寄存器分配器
 * 使用图着色算法将虚拟寄存器分配到物理寄存器
 */
public class RegisterAllocator {
    
    // 算法相关的数据结构
    private final LinkedHashMap<Armv8Block, LivenessAnalyzer.LivenessInfo> livenessInfoMap;
    private final LinkedHashSet<Armv8Reg> initialNodes = new LinkedHashSet<>();
    private final LinkedHashSet<Armv8Reg> simplifyWorkList = new LinkedHashSet<>();  // 低度数且与move无关的节点
    private final LinkedHashSet<Armv8Reg> freezeWorkList = new LinkedHashSet<>();    // 低度数且与move有关的节点
    private final LinkedHashSet<Armv8Reg> spillWorkList = new LinkedHashSet<>();     // 高度数的节点
    private final LinkedHashSet<Armv8Reg> spilledNodes = new LinkedHashSet<>();     // 需要溢出的节点
    private final LinkedHashSet<Armv8Reg> coalescedNodes = new LinkedHashSet<>();   // 已合并的节点
    private final LinkedHashSet<Armv8Reg> coloredNodes = new LinkedHashSet<>();     // 已着色的节点
    private final Stack<Armv8Reg> selectStack = new Stack<>();                      // 选择栈
    
    // move指令相关的工作列表
    private final LinkedHashSet<Armv8Move> coalescedMoves = new LinkedHashSet<>();
    private final LinkedHashSet<Armv8Move> constrainedMoves = new LinkedHashSet<>();
    private final LinkedHashSet<Armv8Move> frozenMoves = new LinkedHashSet<>();
    private final LinkedHashSet<Armv8Move> worklistMoves = new LinkedHashSet<>();
    private final LinkedHashSet<Armv8Move> activeMoves = new LinkedHashSet<>();
    
    // 图结构
    private final LinkedHashSet<Pair<Armv8Reg, Armv8Reg>> interferenceEdges = new LinkedHashSet<>();
    private final LinkedHashMap<Armv8Reg, LinkedHashSet<Armv8Reg>> adjacencyList = new LinkedHashMap<>();
    private final LinkedHashMap<Armv8Reg, Integer> nodeDegree = new LinkedHashMap<>();
    private final LinkedHashMap<Armv8Reg, LinkedHashSet<Armv8Move>> moveList = new LinkedHashMap<>();
    private final LinkedHashMap<Armv8Reg, Armv8Reg> alias = new LinkedHashMap<>();
    private final LinkedHashMap<Armv8Reg, Integer> regColor = new LinkedHashMap<>();
    
    // 常量
    private static final int CPU_REG_COUNT = 16;  // 可用的CPU寄存器数量 (x0-x15)
    private static final int FPU_REG_COUNT = 24;  // 可用的FPU寄存器数量 (v8-v31)
    
    private final Armv8Function currentFunction;
    
    public RegisterAllocator(Armv8Function function) {
        this.currentFunction = function;
        this.livenessInfoMap = LivenessAnalyzer.analyzeLiveness(function);
    }
    
    /**
     * 执行寄存器分配的主流程
     */
    public void allocateRegisters() {
        boolean needSpill = true;
        
        while (needSpill) {
            // 初始化数据结构
            initialize();
            
            // 构建干扰图
            buildInterferenceGraph();
            
            // 创建工作列表
            makeWorkLists();
            
            // 执行图着色算法的主循环
            while (!simplifyWorkList.isEmpty() || !worklistMoves.isEmpty() || 
                   !freezeWorkList.isEmpty() || !spillWorkList.isEmpty()) {
                
                if (!simplifyWorkList.isEmpty()) {
                    simplify();
                } else if (!worklistMoves.isEmpty()) {
                    coalesce();
                } else if (!freezeWorkList.isEmpty()) {
                    freeze();
                } else if (!spillWorkList.isEmpty()) {
                    selectSpill();
                }
            }
            
            // 分配颜色
            assignColors();
            
            // 检查是否需要溢出
            if (spilledNodes.isEmpty()) {
                needSpill = false;
                // 重写程序，将虚拟寄存器替换为物理寄存器
                rewriteProgram();
            } else {
                // 处理溢出的寄存器
                rewriteSpilledNodes();
            }
        }
    }
    
    /**
     * 初始化算法的数据结构
     */
    private void initialize() {
        // 清空所有工作列表
        simplifyWorkList.clear();
        freezeWorkList.clear();
        spillWorkList.clear();
        spilledNodes.clear();
        coalescedNodes.clear();
        coloredNodes.clear();
        selectStack.clear();
        
        coalescedMoves.clear();
        constrainedMoves.clear();
        frozenMoves.clear();
        worklistMoves.clear();
        activeMoves.clear();
        
        interferenceEdges.clear();
        adjacencyList.clear();
        nodeDegree.clear();
        moveList.clear();
        alias.clear();
        regColor.clear();
        
        // 收集所有虚拟寄存器
        initialNodes.clear();
        
        // 首先扫描一遍收集所有的定义和使用
        for (Armv8Block block : currentFunction.getBlocks()) {
            for (Armv8Instruction instruction : block.getInstructions()) {
                // 添加定义的寄存器
                if (instruction.getDefReg() instanceof Armv8VirReg) {
                    initialNodes.add(instruction.getDefReg());
                }
                
                // 添加使用的寄存器
                for (Armv8Operand operand : instruction.getOperands()) {
                    if (operand instanceof Armv8VirReg) {
                        initialNodes.add((Armv8Reg) operand);
                    }
                }
                
                // 收集move指令
                if (instruction instanceof Armv8Move) {
                    Armv8Move moveInst = (Armv8Move) instruction;
                    if (moveInst.getOperands().size() > 0 && moveInst.getOperands().get(0) instanceof Armv8Reg) {
                        worklistMoves.add(moveInst);
                    }
                }
            }
        }
        
        // 打印调试信息
        System.out.println("函数 " + currentFunction.getName() + " 收集到 " + initialNodes.size() + " 个虚拟寄存器");
        
        // 验证虚拟寄存器编号的连续性
        int maxIntId = -1;
        int maxFloatId = -1;
        
        for (Armv8Reg reg : initialNodes) {
            if (reg instanceof Armv8VirReg) {
                Armv8VirReg virReg = (Armv8VirReg) reg;
                if (virReg.isFloat()) {
                    maxFloatId = Math.max(maxFloatId, virReg.getId());
                } else {
                    maxIntId = Math.max(maxIntId, virReg.getId());
                }
            }
        }
        
        System.out.println("最大整型寄存器ID: " + maxIntId + ", 最大浮点寄存器ID: " + maxFloatId);
        System.out.println("当前整型计数: " + Armv8VirReg.getCurrentIntCounter() + ", 当前浮点计数: " + Armv8VirReg.getCurrentFloatCounter());
        
        // 初始化每个节点的邻接列表和度数
        for (Armv8Reg reg : initialNodes) {
            adjacencyList.put(reg, new LinkedHashSet<>());
            nodeDegree.put(reg, 0);
            moveList.put(reg, new LinkedHashSet<>());
            alias.put(reg, reg);
        }
    }
    
    /**
     * 构建干扰图
     */
    private void buildInterferenceGraph() {
        for (Armv8Block block : currentFunction.getBlocks()) {
            // 获取基本块的活跃性信息
            LivenessAnalyzer.LivenessInfo liveness = livenessInfoMap.get(block);
            if (liveness == null) {
                System.err.println("警告：块 " + block.getName() + " 没有活跃性信息");
                continue;
            }
            
            // 从基本块结尾开始，初始活跃变量集为liveOut
            LinkedHashSet<Armv8Reg> liveNow = new LinkedHashSet<>(liveness.getLiveOut());
            
            // 反向遍历指令
            List<Armv8Instruction> instructions = new ArrayList<>(block.getInstructions());
            Collections.reverse(instructions);
            
            for (Armv8Instruction instruction : instructions) {
                // 处理move指令
                if (instruction instanceof Armv8Move) {
                    Armv8Move moveInst = (Armv8Move) instruction;
                    if (moveInst.getOperands().size() > 0 && moveInst.getOperands().get(0) instanceof Armv8Reg &&
                        moveInst.getDefReg() instanceof Armv8Reg) {
                        
                        Armv8Reg src = (Armv8Reg) moveInst.getOperands().get(0);
                        Armv8Reg dst = moveInst.getDefReg();
                        
                        // 源寄存器和目标寄存器在Move指令处不应互相干扰
                        if (src instanceof Armv8VirReg && dst instanceof Armv8VirReg) {
                            liveNow.remove(src);
                            
                            // 添加到move列表，以便后续可能的合并
                            if (moveList.containsKey(src) && moveList.containsKey(dst)) {
                                moveList.get(src).add(moveInst);
                                moveList.get(dst).add(moveInst);
                            }
                        }
                    }
                }
                
                // 为定义的寄存器添加干扰边
                if (instruction.getDefReg() instanceof Armv8VirReg) {
                    Armv8Reg defReg = instruction.getDefReg();
                    ensureRegisterInitialized(defReg);
                    
                    // 添加与所有当前活跃寄存器的干扰边
                    for (Armv8Reg liveReg : liveNow) {
                        if (liveReg instanceof Armv8VirReg && !liveReg.equals(defReg)) {
                            addInterferenceEdge(defReg, liveReg);
                        }
                    }
                    
                    // 定义后不再活跃（除非在操作数中使用）
                    liveNow.remove(defReg);
                }
                
                // 添加使用的寄存器到活跃集合
                for (Armv8Operand operand : instruction.getOperands()) {
                    if (operand instanceof Armv8VirReg) {
                        Armv8Reg reg = (Armv8Reg) operand;
                        ensureRegisterInitialized(reg);
                        liveNow.add(reg);
                    }
                }
            }
        }
        
        // 打印干扰图的统计信息
        System.out.println("干扰图构建完成，共有 " + interferenceEdges.size() + " 条干扰边");
    }
    
    /**
     * 添加干扰边
     */
    private void addInterferenceEdge(Armv8Reg u, Armv8Reg v) {
        if (u == null || v == null || u.equals(v)) {
            return;
        }
        
        // 确保虚拟寄存器被正确初始化
        ensureRegisterInitialized(u);
        ensureRegisterInitialized(v);
        
        if (!adjacencyList.get(u).contains(v)) {
            adjacencyList.get(u).add(v);
            adjacencyList.get(v).add(u);
            
            if (!(u instanceof Armv8PhyReg)) {
                nodeDegree.put(u, nodeDegree.get(u) + 1);
            }
            if (!(v instanceof Armv8PhyReg)) {
                nodeDegree.put(v, nodeDegree.get(v) + 1);
            }
            
            interferenceEdges.add(new Pair<>(u, v));
        }
    }
    
    /**
     * 确保寄存器被正确初始化
     */
    private void ensureRegisterInitialized(Armv8Reg reg) {
        if (reg instanceof Armv8VirReg && !initialNodes.contains(reg)) {
            initialNodes.add(reg);
            adjacencyList.put(reg, new LinkedHashSet<>());
            nodeDegree.put(reg, 0);
            moveList.put(reg, new LinkedHashSet<>());
            alias.put(reg, reg);
            System.out.println("后期发现虚拟寄存器: " + reg);
        }
        
        if (!adjacencyList.containsKey(reg)) {
            adjacencyList.put(reg, new LinkedHashSet<>());
        }
        if (!nodeDegree.containsKey(reg) && reg instanceof Armv8VirReg) {
            nodeDegree.put(reg, 0);
        }
        if (!moveList.containsKey(reg)) {
            moveList.put(reg, new LinkedHashSet<>());
        }
        if (!alias.containsKey(reg)) {
            alias.put(reg, reg);
        }
    }
    
    /**
     * 创建工作列表
     */
    private void makeWorkLists() {
        for (Armv8Reg reg : initialNodes) {
            if (reg instanceof Armv8VirReg) {
                int k = getRegisterLimit((Armv8VirReg) reg);
                
                if (nodeDegree.get(reg) >= k) {
                    spillWorkList.add(reg);
                } else if (isMoveRelated(reg)) {
                    freezeWorkList.add(reg);
                } else {
                    simplifyWorkList.add(reg);
                }
            }
        }
    }
    
    /**
     * 简化阶段：移除低度数且与move无关的节点
     */
    private void simplify() {
        Armv8Reg reg = simplifyWorkList.iterator().next();
        simplifyWorkList.remove(reg);
        selectStack.push(reg);
        
        for (Armv8Reg adjacentReg : adjacencyList.get(reg)) {
            decrementDegree(adjacentReg);
        }
    }
    
    /**
     * 合并阶段：尝试合并move相关的节点
     */
    private void coalesce() {
        Armv8Move moveInst = worklistMoves.iterator().next();
        worklistMoves.remove(moveInst);
        
        Armv8Reg x = (Armv8Reg) moveInst.getOperands().get(0);
        Armv8Reg y = moveInst.getDefReg();
        
        x = getAlias(x);
        y = getAlias(y);
        
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
            addWorkList(u);
        } else if (v instanceof Armv8PhyReg || interferenceEdges.contains(new Pair<>(u, v))) {
            constrainedMoves.add(moveInst);
            addWorkList(u);
            addWorkList(v);
        } else if ((u instanceof Armv8PhyReg && canSafelyCoalesce(u, v)) ||
                   (!(u instanceof Armv8PhyReg) && isConservative(u, v))) {
            coalescedMoves.add(moveInst);
            combine(u, v);
            addWorkList(u);
        } else {
            activeMoves.add(moveInst);
        }
    }
    
    /**
     * 冻结阶段：放弃合并低度数move相关节点
     */
    private void freeze() {
        Armv8Reg u = freezeWorkList.iterator().next();
        freezeWorkList.remove(u);
        simplifyWorkList.add(u);
        freezeMoves(u);
    }
    
    /**
     * 溢出阶段：选择一个高度数节点进行溢出
     */
    private void selectSpill() {
        // 选择溢出优先级最高的节点
        Armv8Reg spillNode = selectSpillCandidate();
        spillWorkList.remove(spillNode);
        simplifyWorkList.add(spillNode);
        freezeMoves(spillNode);
    }
    
    /**
     * 分配颜色阶段
     */
    private void assignColors() {
        // 打印选择栈的大小
        System.out.println("开始分配颜色，选择栈大小: " + selectStack.size());
        
        // 创建颜色映射表，记录已分配的颜色
        Map<Boolean, Set<Integer>> usedColors = new HashMap<>();
        usedColors.put(true, new HashSet<>()); // 浮点寄存器颜色
        usedColors.put(false, new HashSet<>()); // 整型寄存器颜色
        
        // 初始化物理寄存器的预设颜色
        for (int i = 0; i < CPU_REG_COUNT; i++) {
            Armv8CPUReg cpuReg = Armv8CPUReg.getArmv8CPUReg(i);
            regColor.put(cpuReg, i);
        }
        
        for (int i = 8; i < 8 + FPU_REG_COUNT; i++) {
            Armv8FPUReg fpuReg = Armv8FPUReg.getArmv8FloatReg(i);
            regColor.put(fpuReg, i - 8);
        }
        
        while (!selectStack.isEmpty()) {
            Armv8Reg reg = selectStack.pop();
            
            if (reg instanceof Armv8VirReg) {
                Armv8VirReg virReg = (Armv8VirReg) reg;
                Set<Integer> okColors = new HashSet<>();
                
                // 获取可用颜色
                int regLimit = getRegisterLimit(virReg);
                for (int i = 0; i < regLimit; i++) {
                    okColors.add(i);
                }
                
                // 移除邻接节点已使用的颜色
                for (Armv8Reg adjacentReg : adjacencyList.get(reg)) {
                    Armv8Reg aliasReg = getAlias(adjacentReg);
                    
                    if (coloredNodes.contains(aliasReg) || aliasReg instanceof Armv8PhyReg) {
                        Integer color = regColor.get(aliasReg);
                        if (color != null) {
                            okColors.remove(color);
                        }
                    }
                }
                
                if (okColors.isEmpty()) {
                    // 没有可用颜色，需要溢出
                    spilledNodes.add(reg);
                    System.out.println("寄存器 " + reg + " 没有可用颜色，添加到溢出列表");
                } else {
                    // 寻找最优的颜色（尽量使用较小的颜色值）
                    int color = okColors.stream().min(Integer::compare).orElse(0);
                    
                    coloredNodes.add(reg);
                    regColor.put(reg, color);
                    
                    // 记录已使用的颜色
                    usedColors.get(virReg.isFloat()).add(color);
                    
                    System.out.println("为寄存器 " + reg + " 分配颜色 " + color);
                }
            }
        }
        
        // 为合并的节点分配颜色
        for (Armv8Reg reg : coalescedNodes) {
            Armv8Reg aliasReg = getAlias(reg);
            if (regColor.containsKey(aliasReg)) {
                regColor.put(reg, regColor.get(aliasReg));
                System.out.println("为合并节点 " + reg + " 分配颜色 " + regColor.get(aliasReg) + " (继承自 " + aliasReg + ")");
            }
        }
        
        // 确保所有收集到的虚拟寄存器都有颜色分配
        for (Armv8Reg reg : initialNodes) {
            if (reg instanceof Armv8VirReg && !regColor.containsKey(reg) && !spilledNodes.contains(reg)) {
                Armv8VirReg virReg = (Armv8VirReg) reg;
                int regLimit = getRegisterLimit(virReg);
                
                // 查找未使用的颜色
                int color = 0;
                while (color < regLimit && usedColors.get(virReg.isFloat()).contains(color)) {
                    color++;
                }
                
                if (color < regLimit) {
                    regColor.put(reg, color);
                    coloredNodes.add(reg);
                    usedColors.get(virReg.isFloat()).add(color);
                    System.out.println("为未着色的寄存器 " + reg + " 分配颜色 " + color);
                } else {
                    spilledNodes.add(reg);
                    System.out.println("无法为寄存器 " + reg + " 找到可用颜色，添加到溢出列表");
                }
            }
        }
        
        // 打印颜色分配统计信息
        int intColors = usedColors.get(false).size();
        int floatColors = usedColors.get(true).size();
        System.out.println("颜色分配完成，使用了 " + intColors + " 个整型颜色, " + floatColors + " 个浮点颜色");
        System.out.println("溢出寄存器数量: " + spilledNodes.size());
    }
    
    /**
     * 重写程序，将虚拟寄存器替换为物理寄存器
     */
    private void rewriteProgram() {
        System.out.println("开始重写程序，替换虚拟寄存器为物理寄存器...");
        int replacedCount = 0;

        for (Armv8Block block : currentFunction.getBlocks()) {
            for (Armv8Instruction instruction : block.getInstructions()) {
                // 替换定义的寄存器
                if (instruction.getDefReg() instanceof Armv8VirReg) {
                    Armv8VirReg virReg = (Armv8VirReg) instruction.getDefReg();
                    if (regColor.containsKey(virReg)) {
                        Integer color = regColor.get(virReg);
                        if (color != null) {
                            Armv8PhyReg phyReg = getPhysicalRegister(virReg, color);
                            instruction.replaceDefReg(phyReg);
                            replacedCount++;
                        } else {
                            System.err.println("错误: 虚拟寄存器 " + virReg + " 的颜色为null");
                        }
                    } else {
                        // 如果找不到颜色映射，检查是否在溢出列表中
                        if (spilledNodes.contains(virReg)) {
                            System.err.println("警告: 虚拟寄存器 " + virReg + " 已溢出，但未被重写");
                        } else {
                            System.err.println("错误: 虚拟寄存器 " + virReg + " 没有颜色分配且未溢出");
                        }
                    }
                }
                
                // 替换使用的寄存器
                List<Armv8Operand> operands = instruction.getOperands();
                for (int i = 0; i < operands.size(); i++) {
                    Armv8Operand operand = operands.get(i);
                    if (operand instanceof Armv8VirReg) {
                        Armv8VirReg virReg = (Armv8VirReg) operand;
                        if (regColor.containsKey(virReg)) {
                            Integer color = regColor.get(virReg);
                            if (color != null) {
                                Armv8PhyReg phyReg = getPhysicalRegister(virReg, color);
                                instruction.replaceOperands(virReg, phyReg);
                                replacedCount++;
                            } else {
                                System.err.println("错误: 虚拟寄存器 " + virReg + " 的颜色为null");
                            }
                        } else {
                            // 如果找不到颜色映射，检查是否在溢出列表中
                            if (spilledNodes.contains(virReg)) {
                                System.err.println("警告: 虚拟寄存器 " + virReg + " 已溢出，但未被重写");
                            } else {
                                System.err.println("错误: 虚拟寄存器 " + virReg + " 没有颜色分配且未溢出");
                            }
                        }
                    }
                }
            }
        }
        
        System.out.println("程序重写完成，共替换 " + replacedCount + " 处虚拟寄存器");
    }
    
    /**
     * 重写溢出的节点（重写为内存访问）
     */
    private void rewriteSpilledNodes() {
        System.out.println("开始处理溢出的寄存器，数量：" + spilledNodes.size());
        Map<Armv8VirReg, Long> spillOffsetMap = new HashMap<>();
        
        for (Armv8Reg spilledReg : spilledNodes) {
            if (spilledReg instanceof Armv8VirReg) {
                Armv8VirReg virReg = (Armv8VirReg) spilledReg;
                
                // 为溢出的寄存器在栈上分配空间
                long stackOffset = currentFunction.getStackSize();
                currentFunction.addStack(null, 8L); // 8字节对齐
                spillOffsetMap.put(virReg, stackOffset);
                
                System.out.println("为溢出寄存器 " + virReg + " 在栈上分配偏移量: " + stackOffset);
            }
        }
        
        // 重写使用溢出寄存器的指令
        for (Armv8Block block : currentFunction.getBlocks()) {
            List<Armv8Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (int i = 0; i < instructions.size(); i++) {
                Armv8Instruction instruction = instructions.get(i);
                boolean needsRewrite = false;
                Armv8VirReg usedSpilledReg = null;
                Armv8VirReg definedSpilledReg = null;
                
                // 检查定义的寄存器
                if (instruction.getDefReg() instanceof Armv8VirReg && 
                    spilledNodes.contains(instruction.getDefReg())) {
                    needsRewrite = true;
                    definedSpilledReg = (Armv8VirReg) instruction.getDefReg();
                }
                
                // 检查使用的寄存器
                for (Armv8Operand operand : instruction.getOperands()) {
                    if (operand instanceof Armv8VirReg && spilledNodes.contains(operand)) {
                        needsRewrite = true;
                        usedSpilledReg = (Armv8VirReg) operand;
                        break;
                    }
                }
                
                if (needsRewrite) {
                    // 为每个溢出的寄存器创建一个新的临时虚拟寄存器
                    if (usedSpilledReg != null) {
                        // 处理使用的溢出寄存器
                        Armv8VirReg tempReg = new Armv8VirReg(usedSpilledReg.isFloat());
                        
                        // 添加加载指令（从栈加载到临时寄存器）
                        Armv8Load loadInst = new Armv8Load(
                            Armv8CPUReg.getArmv8SpReg(),
                            new Armv8Imm(spillOffsetMap.get(usedSpilledReg)),
                            tempReg
                        );
                        
                        // 在当前指令之前插入加载指令
                        block.insertBeforeInst(instruction, loadInst);
                        
                        // 替换指令中的溢出寄存器为临时寄存器
                        instruction.replaceOperands(usedSpilledReg, tempReg);
                    }
                    
                    if (definedSpilledReg != null) {
                        // 处理定义的溢出寄存器
                        Armv8VirReg tempReg = new Armv8VirReg(definedSpilledReg.isFloat());
                        
                        // 替换指令中定义的寄存器为临时寄存器
                        instruction.replaceDefReg(tempReg);
                        
                        // 添加存储指令（从临时寄存器存到栈）
                        Armv8Store storeInst = new Armv8Store(
                            tempReg,
                            Armv8CPUReg.getArmv8SpReg(),
                            new Armv8Imm(spillOffsetMap.get(definedSpilledReg))
                        );
                        
                        // 获取指令在列表中的索引并在其后插入存储指令
                        int instIndex = block.getInstructions().indexOf(instruction);
                        if (instIndex != -1 && instIndex + 1 < block.getInstructions().size()) {
                            Armv8Instruction nextInst = block.getInstructions().get(instIndex + 1);
                            block.insertBeforeInst(nextInst, storeInst);
                        } else {
                            block.addArmv8Instruction(storeInst);
                        }
                    }
                }
            }
        }
        
        // 清空溢出节点列表，为下一轮准备
        spilledNodes.clear();
        
        System.out.println("溢出寄存器处理完成");
        
        // 重新计算虚拟寄存器计数器，为新的虚拟寄存器做准备
        Armv8VirReg.resetCounter();
    }
    
    // 辅助方法
    
    private int getRegisterLimit(Armv8VirReg virReg) {
        return virReg.isFloat() ? FPU_REG_COUNT : CPU_REG_COUNT;
    }
    
    private boolean isMoveRelated(Armv8Reg reg) {
        return !getNodeMoves(reg).isEmpty();
    }
    
    private LinkedHashSet<Armv8Move> getNodeMoves(Armv8Reg reg) {
        LinkedHashSet<Armv8Move> moves = new LinkedHashSet<>();
        if (moveList.containsKey(reg)) {
            for (Armv8Move move : moveList.get(reg)) {
                if (activeMoves.contains(move) || worklistMoves.contains(move)) {
                    moves.add(move);
                }
            }
        }
        return moves;
    }
    
    private void decrementDegree(Armv8Reg reg) {
        if (reg instanceof Armv8VirReg) {
            int degree = nodeDegree.get(reg);
            nodeDegree.put(reg, degree - 1);
            
            int k = getRegisterLimit((Armv8VirReg) reg);
            if (degree == k) {
                enableMoves(reg);
                spillWorkList.remove(reg);
                
                if (isMoveRelated(reg)) {
                    freezeWorkList.add(reg);
                } else {
                    simplifyWorkList.add(reg);
                }
            }
        }
    }
    
    private Armv8Reg getAlias(Armv8Reg reg) {
        if (coalescedNodes.contains(reg)) {
            return getAlias(alias.get(reg));
        }
        return reg;
    }
    
    private void addWorkList(Armv8Reg reg) {
        if (!(reg instanceof Armv8PhyReg) && !isMoveRelated(reg)) {
            int k = reg instanceof Armv8VirReg ? getRegisterLimit((Armv8VirReg) reg) : CPU_REG_COUNT;
            if (nodeDegree.get(reg) < k) {
                freezeWorkList.remove(reg);
                simplifyWorkList.add(reg);
            }
        }
    }
    
    private boolean canSafelyCoalesce(Armv8Reg u, Armv8Reg v) {
        // George测试：检查是否可以安全合并
        if (!adjacencyList.containsKey(v)) {
            return false;
        }
        
        for (Armv8Reg t : adjacencyList.get(v)) {
            if (t instanceof Armv8VirReg) {
                int k = getRegisterLimit((Armv8VirReg) t);
                if (!(nodeDegree.get(t) < k || t instanceof Armv8PhyReg || interferenceEdges.contains(new Pair<>(t, u)))) {
                    return false;
                }
            }
        }
        return true;
    }
    
    private boolean isConservative(Armv8Reg u, Armv8Reg v) {
        // Briggs测试：合并后高度数节点数量不增加
        LinkedHashSet<Armv8Reg> nodes = new LinkedHashSet<>();
        if (adjacencyList.containsKey(u)) {
            nodes.addAll(adjacencyList.get(u));
        }
        if (adjacencyList.containsKey(v)) {
            nodes.addAll(adjacencyList.get(v));
        }
        
        int highDegreeCount = 0;
        for (Armv8Reg node : nodes) {
            if (node instanceof Armv8VirReg) {
                int k = getRegisterLimit((Armv8VirReg) node);
                if (nodeDegree.containsKey(node) && nodeDegree.get(node) >= k) {
                    highDegreeCount++;
                }
            }
        }
        
        int k = Math.max(
            u instanceof Armv8VirReg ? getRegisterLimit((Armv8VirReg) u) : CPU_REG_COUNT,
            v instanceof Armv8VirReg ? getRegisterLimit((Armv8VirReg) v) : CPU_REG_COUNT
        );
        
        return highDegreeCount < k;
    }
    
    private void combine(Armv8Reg u, Armv8Reg v) {
        if (freezeWorkList.contains(v)) {
            freezeWorkList.remove(v);
        } else {
            spillWorkList.remove(v);
        }
        
        coalescedNodes.add(v);
        alias.put(v, u);
        
        if (moveList.containsKey(u) && moveList.containsKey(v)) {
            moveList.get(u).addAll(moveList.get(v));
        }
        
        enableMoves(v);
        
        if (adjacencyList.containsKey(v)) {
            for (Armv8Reg t : new LinkedHashSet<>(adjacencyList.get(v))) {
                addInterferenceEdge(t, u);
                decrementDegree(t);
            }
        }
        
        int k = u instanceof Armv8VirReg ? getRegisterLimit((Armv8VirReg) u) : CPU_REG_COUNT;
        if (nodeDegree.containsKey(u) && nodeDegree.get(u) >= k && freezeWorkList.contains(u)) {
            freezeWorkList.remove(u);
            spillWorkList.add(u);
        }
    }
    
    private void freezeMoves(Armv8Reg u) {
        for (Armv8Move move : new LinkedHashSet<>(getNodeMoves(u))) {
            if (move.getOperands().size() > 0 && move.getOperands().get(0) instanceof Armv8Reg) {
                Armv8Reg x = (Armv8Reg) move.getOperands().get(0);
                Armv8Reg y = move.getDefReg();
                
                if (x == null || y == null) continue;
                
                Armv8Reg v;
                if (getAlias(y).equals(getAlias(u))) {
                    v = getAlias(x);
                } else {
                    v = getAlias(y);
                }
                
                activeMoves.remove(move);
                frozenMoves.add(move);
                
                if (getNodeMoves(v).isEmpty() && v instanceof Armv8VirReg) {
                    int k = getRegisterLimit((Armv8VirReg) v);
                    if (nodeDegree.containsKey(v) && nodeDegree.get(v) < k) {
                        freezeWorkList.remove(v);
                        simplifyWorkList.add(v);
                    }
                }
            }
        }
    }
    
    private void enableMoves(Armv8Reg reg) {
        for (Armv8Move move : new LinkedHashSet<>(getNodeMoves(reg))) {
            if (activeMoves.contains(move)) {
                activeMoves.remove(move);
                worklistMoves.add(move);
            }
        }
    }
    
    private Armv8Reg selectSpillCandidate() {
        // 简单的溢出选择策略：选择度数最高的节点
        Armv8Reg candidate = null;
        int maxDegree = -1;
        
        for (Armv8Reg reg : spillWorkList) {
            if (nodeDegree.containsKey(reg) && nodeDegree.get(reg) > maxDegree) {
                maxDegree = nodeDegree.get(reg);
                candidate = reg;
            }
        }
        
        return candidate != null ? candidate : spillWorkList.iterator().next();
    }
    
    /**
     * 根据颜色获取物理寄存器
     * @param virReg 虚拟寄存器
     * @param colorIndex 颜色索引
     * @return 对应的物理寄存器
     */
    private Armv8PhyReg getPhysicalRegister(Armv8VirReg virReg, int colorIndex) {
        if (virReg == null) {
            System.err.println("错误: 传入的虚拟寄存器为null");
            return null;
        }
        
        if (colorIndex < 0) {
            System.err.println("错误: 无效的颜色索引 " + colorIndex + " 用于寄存器 " + virReg);
            return null;
        }
        
        if (virReg.isFloat()) {
            // 浮点寄存器映射: v8-v31
            if (colorIndex >= FPU_REG_COUNT) {
                System.err.println("错误: 浮点颜色索引 " + colorIndex + " 超出范围 (最大 " + (FPU_REG_COUNT-1) + ")");
                colorIndex = colorIndex % FPU_REG_COUNT; // 应急处理
            }
            return Armv8FPUReg.getArmv8FloatReg(colorIndex + 8);
        } else {
            // 整型寄存器映射: x0-x15
            if (colorIndex >= CPU_REG_COUNT) {
                System.err.println("错误: 整型颜色索引 " + colorIndex + " 超出范围 (最大 " + (CPU_REG_COUNT-1) + ")");
                colorIndex = colorIndex % CPU_REG_COUNT; // 应急处理
            }
            return Armv8CPUReg.getArmv8CPUReg(colorIndex);
        }
    }
    
    /**
     * 调试方法：打印寄存器分配的统计信息
     */
    public void printAllocationStats() {
        System.out.println("=== 寄存器分配统计 ===");
        System.out.println("着色的寄存器数量: " + coloredNodes.size());
        System.out.println("合并的寄存器数量: " + coalescedNodes.size());
        System.out.println("溢出的寄存器数量: " + spilledNodes.size());
        System.out.println("干扰边数量: " + interferenceEdges.size());
        
        if (!spilledNodes.isEmpty()) {
            System.out.println("溢出的寄存器: " + spilledNodes);
        }
    }
} 
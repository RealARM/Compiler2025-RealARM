package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.User;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.Optimization.Analysis.DominatorAnalysis;
import MiddleEnd.Optimization.Analysis.Loop;
import MiddleEnd.Optimization.Analysis.LoopAnalysis;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.*;

public class LoopSSATransform implements Optimizer.ModuleOptimizer {

	private static final int MAX_BLOCK_THRESHOLD = 1000;

	@Override
	public String getName() {
		return "LoopSSATransform";
	}
	
	@Override
	public boolean run(Module module) {
		boolean changed = false;
		for (Function function : module.functions()) {
			if (module.libFunctions().contains(function)) {
				continue;
			}
			changed |= runForFunction(function);
		}
		return changed;
	}
	
	private boolean runForFunction(Function function) {
		boolean changed = false;
		// 分析循环与支配关系
		List<Loop> topLoops = LoopAnalysis.analyzeLoops(function);
		Map<BasicBlock, Set<BasicBlock>> dominators = DominatorAnalysis.computeDominators(function);
		Map<BasicBlock, BasicBlock> idoms = DominatorAnalysis.computeImmediateDominators(dominators);
		// 将idom写回BasicBlock，便于后续步骤使用
		for (Map.Entry<BasicBlock, BasicBlock> e : idoms.entrySet()) {
			BasicBlock b = e.getKey();
			BasicBlock idom = e.getValue();
			b.setIdominator(idom);
		}
		for (Loop loop : topLoops) {
			changed |= processLoop(loop, dominators);
		}
		return changed;
	}
	
	// 处理单个循环的LCSSA变换
	private boolean processLoop(Loop loop, Map<BasicBlock, Set<BasicBlock>> dominators) {
		boolean changed = false;
		// 先处理子循环，内层优先
		for (Loop sub : loop.getSubLoops()) {
			changed |= processLoop(sub, dominators);
		}
		// 计算并获取出口块
		loop.computeExitBlocks();
		Set<BasicBlock> exitBlocks = loop.getExitBlocks();
		if (exitBlocks.isEmpty()) {
			return changed;
		}
		// 遍历循环体内所有指令，对需要LCSSA的指令进行完整处理
		for (BasicBlock bb : loop.getBlocks()) {
			for (Instruction inst : new ArrayList<>(bb.getInstructions())) {
				if (isUsedOutsideLoop(inst, loop)) {
					changed |= addPhiAndRenameUses(inst, loop, dominators);
				}
			}
		}
		return changed;
	}

	// 为单个指令添加LCSSA phi并重写所有循环外uses
	private Map<BasicBlock, PhiInstruction> exitPhiMap = new HashMap<>();
	private boolean addPhiAndRenameUses(Instruction inst, Loop loop, Map<BasicBlock, Set<BasicBlock>> dominators) {
		boolean changed = false;
		exitPhiMap.clear();
		
		BasicBlock instBlock = inst.getParent();
		Set<PhiInstruction> newPhis = new HashSet<>();
		
		// 第一步：在支配的出口块放置phi
		for (BasicBlock exit : loop.getExitBlocks()) {
			Set<BasicBlock> domSet = dominators.get(exit);
			if (domSet != null && domSet.contains(instBlock) && !exitPhiMap.containsKey(exit)) {
				PhiInstruction phi = new PhiInstruction(inst.getType(), inst.getName() + "_lcssa");
				exit.addInstructionFirst(phi);
				// 初始化phi的来边值（所有前驱都设为inst）
				for (BasicBlock pred : exit.getPredecessors()) {
					phi.addIncoming(inst, pred);
				}
				exitPhiMap.put(exit, phi);
				newPhis.add(phi);
				changed = true;
			}
		}
		
		// 第二步：重写所有循环外的uses
		List<User> userList = new ArrayList<>(inst.getUsers());
		for (User user : userList) {
			if (!(user instanceof Instruction)) {
				continue;
			}
			Instruction userInst = (Instruction) user;
			BasicBlock userBlock = getUserBlock(inst, userInst);
			
			// 跳过循环内的uses和新创建的phi
			if (userBlock == instBlock || loop.contains(userBlock) || newPhis.contains(user)) {
				continue;
			}
			
			// 为循环外的use找到正确的phi替换
			PhiInstruction correctPhi = getValueInBlock(userBlock, loop);
			if (correctPhi != null) {
				userInst.replaceAllUsesWith(inst, correctPhi);
				changed = true;
			}
		}
		
		return changed;
	}
	
	// 获取use所在的基本块（处理phi的特殊情况）
	private BasicBlock getUserBlock(Instruction inst, Instruction userInst) {
		BasicBlock userBlock = userInst.getParent();
		if (userInst instanceof PhiInstruction) {
			PhiInstruction phi = (PhiInstruction) userInst;
			// 对于phi，需要找到对应的前驱块
			for (Map.Entry<BasicBlock, Value> entry : phi.getIncomingValues().entrySet()) {
				if (entry.getValue() == inst && entry.getKey() != null) {
					userBlock = entry.getKey();
					break;
				}
			}
		}
		return userBlock;
	}
	
	// 为给定基本块找到正确的phi值（参考zy的getValueInBlock逻辑）
	private PhiInstruction getValueInBlock(BasicBlock userBlock, Loop loop) {
		// 0. 检查userBlock是否为null
		if (userBlock == null) {
			return null;
		}
		
		// 1. 如果userBlock就是exit block，直接返回对应的phi
		if (exitPhiMap.containsKey(userBlock)) {
			return exitPhiMap.get(userBlock);
		}
		
		// 2. 如果userBlock不是exit block
		BasicBlock idom = userBlock.getIdominator();
		if (idom != null && !loop.contains(idom)) {
			// idom不在循环中，说明被某个exit block支配，递归向上找
			PhiInstruction value = getValueInBlock(idom, loop);
			if (value != null) {
				exitPhiMap.put(userBlock, value);
			}
			return value;
		}
		
		// 3. idom在循环中，需要在userBlock创建新的phi
		List<PhiInstruction> predecessorValues = new ArrayList<>();
		for (BasicBlock pred : userBlock.getPredecessors()) {
			PhiInstruction predValue = getValueInBlock(pred, loop);
			if (predValue != null) {
				predecessorValues.add(predValue);
			}
		}
		
		if (!predecessorValues.isEmpty()) {
			PhiInstruction phi = new PhiInstruction(predecessorValues.get(0).getType(), "lcssa_merge");
			userBlock.addInstructionFirst(phi);
			// 添加来边
			for (int i = 0; i < userBlock.getPredecessors().size() && i < predecessorValues.size(); i++) {
				phi.addIncoming(predecessorValues.get(i), userBlock.getPredecessors().get(i));
			}
			exitPhiMap.put(userBlock, phi);
			return phi;
		}
		
		return null;
	}
	
	// 第一步：只在出口块为跨循环使用的定义放置LCSSA Phi（不改use）
	private boolean addExitPhisForLoop(Loop loop, Map<BasicBlock, Set<BasicBlock>> dominators) {
		boolean changed = false;
		// 先处理子循环，内层优先
		for (Loop sub : loop.getSubLoops()) {
			changed |= addExitPhisForLoop(sub, dominators);
		}
		// 计算并获取出口块
		loop.computeExitBlocks();
		Set<BasicBlock> exitBlocks = loop.getExitBlocks();
		if (exitBlocks.isEmpty()) {
			return changed;
		}
		// 遍历循环体内所有指令，检测是否有循环外use
		for (BasicBlock bb : loop.getBlocks()) {
			for (Instruction inst : new ArrayList<>(bb.getInstructions())) {
				if (!isUsedOutsideLoop(inst, loop)) {
					continue;
				}
				BasicBlock defBlock = inst.getParent();
				for (BasicBlock exit : exitBlocks) {
					Set<BasicBlock> domSet = dominators.get(exit);
					if (domSet == null || !domSet.contains(defBlock)) {
						continue; // 只有当定义支配出口块时，才在该出口放置Phi
					}
					// 在出口块头部插入一个Phi，初值来自各前驱（先全部设为inst，后续步骤再精化）
					if (!hasExistingLcssaPhi(exit, inst)) {
						PhiInstruction phi = new PhiInstruction(inst.getType(), inst.getName() + "_lcssa");
						exit.addInstructionFirst(phi);
						for (BasicBlock pred : exit.getPredecessors()) {
							phi.addIncoming(inst, pred);
						}
						changed = true;
					}
				}
			}
		}
		return changed;
	}
	
	private boolean isUsedOutsideLoop(Instruction inst, Loop loop) {
		for (User user : new ArrayList<>(inst.getUsers())) {
			if (!(user instanceof Instruction)) {
				continue;
			}
			Instruction uInst = (Instruction) user;
			if (uInst instanceof PhiInstruction) {
				PhiInstruction phi = (PhiInstruction) uInst;
				// 对Phi，逐个来边检查是否引用了该定义，且对应前驱是否在环外
				for (Map.Entry<BasicBlock, Value> entry : phi.getIncomingValues().entrySet()) {
					if (entry.getValue() == inst && !loop.contains(entry.getKey())) {
						return true;
					}
				}
			} else {
				BasicBlock useBlock = uInst.getParent();
				if (useBlock != null && !loop.contains(useBlock)) {
					return true;
				}
			}
		}
		return false;
	}
	
	private boolean hasExistingLcssaPhi(BasicBlock exit, Value def) {
		for (Instruction inst : exit.getInstructions()) {
			if (inst instanceof PhiInstruction) {
				PhiInstruction phi = (PhiInstruction) inst;
				// 简单判定：是否已有以该定义为所有来边值的Phi（避免重复）
				boolean allMatch = true;
				for (Value v : phi.getIncomingValues().values()) {
					if (v != def) { allMatch = false; break; }
				}
				if (allMatch && !phi.getIncomingValues().isEmpty()) {
					return true;
				}
			} else {
				break; // Phi应当在块首部连续出现
			}
		}
		return false;
	}
}

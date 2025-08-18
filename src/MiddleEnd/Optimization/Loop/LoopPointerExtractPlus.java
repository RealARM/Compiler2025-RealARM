package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.User;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.GetElementPtrInstruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.IR.Value.Instructions.BinaryInstruction;
import MiddleEnd.IR.OpCode;
import MiddleEnd.Optimization.Analysis.Loop;
import MiddleEnd.Optimization.Analysis.LoopAnalysis;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.*;

/**
 * 循环指针外提优化
 * 提取循环内以迭代变量为索引的指针运算到循环外，
 */
public class LoopPointerExtractPlus implements Optimizer.ModuleOptimizer {
	private final boolean debug = false;
	private static long uniqueId = 0;
	private static final int MAX_BLOCK_THRESHOLD = 1000;

	private static synchronized String generateUniquePtrName(BasicBlock header) {
		return "loop_ptr_" + System.nanoTime() + "_" + (uniqueId++) + "_" + header.getName();
	}

	@Override
	public String getName() {
		return "LoopPointerExtractPlus";
	}

	@Override
	public boolean run(Module module) {
		boolean changed = false;
		if (debug) {
			System.out.println("[LoopPointerMotion] 开始循环指针迁移优化");
		}

		for (Function function : module.functions()) {
			if (function.isExternal()) continue;
			changed |= runForFunction(function);
		}

		if (debug) {
			System.out.println("[LoopPointerMotion] 优化完成，是否有改动: " + changed);
		}
		return changed;
	}

	private boolean runForFunction(Function function) {
		int blockCount = function.getBasicBlocks().size();
		if (blockCount > MAX_BLOCK_THRESHOLD) {
			System.out.println("[LoopPointerExtractPlus] Function " + function.getName() + " has " + blockCount + 
				" basic blocks, which exceeds the threshold of " + MAX_BLOCK_THRESHOLD + ". Skipping for performance reasons.");
			return false;
		}
		
		boolean changed = false;
		
		List<Loop> topLoops = LoopAnalysis.analyzeLoops(function);
		LoopAnalysis.analyzeInductionVariables(function);
		
		List<Loop> allLoops = getAllLoopsInDFSOrder(topLoops);
		for (Loop loop : allLoops) {
			changed |= runPtrMotionForLoop(loop);
		}
		return changed;
	}

	private List<Loop> getAllLoopsInDFSOrder(List<Loop> topLoops) {
		List<Loop> all = new ArrayList<>();
		for (Loop lp : topLoops) addLoopsInDFSOrder(lp, all);
		return all;
	}

	private void addLoopsInDFSOrder(Loop loop, List<Loop> out) {
		for (Loop sub : loop.getSubLoops()) addLoopsInDFSOrder(sub, out);
		out.add(loop);
	}

	private boolean runPtrMotionForLoop(Loop loop) {
		try {
			if (!isEligibleLoop(loop)) return false;
			BasicBlock header = loop.getHeader();
			BasicBlock latch = loop.getLatchBlocks().get(0);
			BasicBlock preheader = loop.getPreheader();

			Value indVar = loop.getInductionVariable();
			Value initValue = loop.getInitValue();
			Value stepValue = loop.getStepValue();
			BinaryInstruction updateInst = getSupportedUpdate(loop.getUpdateInstruction());
			if (updateInst == null) return false;
			OpCode updOp = updateInst.getOpCode();

			debugLog("处理循环，头块: " + header.getName());
			debugLog("归纳变量: " + indVar);
			debugLog("初始值: " + initValue);
			debugLog("步长: " + stepValue + ", 更新操作: " + updOp);

			Set<Instruction> loopInsts = collectLoopInstructions(loop);
			TargetGepData targetData = findTargetGeps(loop, indVar, loopInsts);
			if (targetData.targets.isEmpty()) {
				debugLog("未找到匹配的GEP，跳过");
				return false;
			}

			Map<GroupKey, List<GetElementPtrInstruction>> groups = groupGeps(targetData);
			boolean anyChanged = false;

			for (Map.Entry<GroupKey, List<GetElementPtrInstruction>> entry : groups.entrySet()) {
				GroupKey key = entry.getKey();
				List<GetElementPtrInstruction> geps = entry.getValue();
				Value basePtr = key.base;
				Value invariant = key.invariant;

				debugLog("处理GEP组: size=" + geps.size() + ", base=" + basePtr + ", invariant=" + invariant);

				InitInsertResult init = insertPreheaderInit(preheader, basePtr, invariant, initValue);
				PhiInstruction ptrPhi = createPtrPhi(header, preheader, init.initGep, basePtr);
				GetElementPtrInstruction stepGep = insertLatchStep(latch, ptrPhi, stepValue);
				ptrPhi.addOrUpdateIncoming(stepGep, latch);

				debugLog("header=" + header.getName() + ", 新Phi=" + ptrPhi + "; latch=" + latch.getName() + ", stepGep=" + stepGep + ", step=" + stepValue + ", updOp=" + updOp);

				ReplacementStats stats = replaceGroupUses(loop, geps, ptrPhi);
				debugLog("组替换完成，总使用=" + stats.totalUses + ", 循环内已替换=" + stats.replacedInLoop + ", 删除原GEP数量=" + stats.removedCount + ", 组大小=" + geps.size());

				anyChanged = true;
			}

			if (debug && anyChanged) {
				System.out.println("[LoopPointerMotion] 对循环(头块=" + loop.getHeader().getName() + ") 应用指针迁移，GEP组数: " + groups.size() + ", 原始GEP数量: " + targetData.targets.size());
			}
			return anyChanged;
		} catch (Exception e) {
			if (debug) {
				System.out.println("[LoopPointerMotion] 处理循环时出错: " + e.getMessage());
				e.printStackTrace();
			}
			return false;
		}
	}

	private boolean isEligibleLoop(Loop loop) {
		if (!loop.hasInductionVariable()) return false;
		BasicBlock header = loop.getHeader();
		if (header.getPredecessors().size() != 2) return false;
		if (loop.getLatchBlocks().size() != 1) return false;
		return loop.getPreheader() != null;
	}

	private BinaryInstruction getSupportedUpdate(Instruction updateInst) {
		if (!(updateInst instanceof BinaryInstruction binUpd)) return null;
		OpCode op = binUpd.getOpCode();
		if (op != OpCode.ADD && op != OpCode.SUB) return null;
		return binUpd;
	}

	private Set<Instruction> collectLoopInstructions(Loop loop) {
		Set<Instruction> insts = new HashSet<>();
		for (BasicBlock bb : loop.getBlocks()) insts.addAll(bb.getInstructions());
		return insts;
	}

	private TargetGepData findTargetGeps(Loop loop, Value indVar, Set<Instruction> loopInsts) {
		TargetGepData data = new TargetGepData();
		for (BasicBlock bb : loop.getBlocks()) {
			for (Instruction inst : bb.getInstructions()) {
				if (inst instanceof GetElementPtrInstruction gep) {
					if (gep.getOperandCount() < 2) continue;
					Value offset = gep.getOffset();
					if (offset instanceof BinaryInstruction bin && bin.getOpCode() == OpCode.ADD) {
						Value left = bin.getLeft();
						Value right = bin.getRight();

						Value invariant = null;
						boolean indIsLeft = false;
						if (left == indVar) {
							invariant = right;
							indIsLeft = true;
						} else if (right == indVar) {
							invariant = left;
						}

						debugLog("检查GEP: bb=" + bb.getName() + ", base=" + gep.getPointer() + ", offset=" + offset
							+ " (left=" + left + ", right=" + right + ", indIsLeft=" + indIsLeft + ")");

						if (invariant == null) continue;
						if (!(invariant instanceof ConstantInt)) continue;
						if (invariant instanceof Instruction && loopInsts.contains((Instruction) invariant)) continue;

						data.targets.add(gep);
						data.invariantMap.put(gep, invariant);
						data.basePtrMap.put(gep, gep.getPointer());

						debugLog("选择目标GEP: " + gep + ", invariant=" + invariant);
					}
				}
			}
		}
		return data;
	}

	private Map<GroupKey, List<GetElementPtrInstruction>> groupGeps(TargetGepData data) {
		Map<GroupKey, List<GetElementPtrInstruction>> groups = new LinkedHashMap<>();
		for (GetElementPtrInstruction gep : data.targets) {
			GroupKey key = new GroupKey(data.basePtrMap.get(gep), data.invariantMap.get(gep));
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(gep);
		}
		return groups;
	}

	private InitInsertResult insertPreheaderInit(BasicBlock preheader, Value basePtr, Value invariant, Value initValue) {
		BinaryInstruction initOffset = IRBuilder.createBinaryInst(OpCode.ADD, invariant, initValue, null);
		List<Instruction> preInsts = preheader.getInstructions();
		Instruction preLast = preInsts.isEmpty() ? null : preInsts.get(preInsts.size() - 1);
		if (preLast != null) preheader.addInstructionBefore(initOffset, preLast); else preheader.addInstruction(initOffset);

		GetElementPtrInstruction initGep = IRBuilder.createGetElementPtr(basePtr, initOffset, null);
		if (preLast != null) preheader.addInstructionBefore(initGep, preLast); else preheader.addInstruction(initGep);

		debugLog("preheader=" + preheader.getName() + ", 插入 initOffset=" + initOffset + ", initGep=" + initGep);
		return new InitInsertResult(initOffset, initGep);
	}

	private PhiInstruction createPtrPhi(BasicBlock header, BasicBlock preheader, GetElementPtrInstruction initGep, Value basePtr) {
		PhiInstruction ptrPhi = new PhiInstruction(basePtr.getType(), generateUniquePtrName(header));
		ptrPhi.addIncoming(initGep, preheader);
		header.addInstructionBefore(ptrPhi, header.getInstructions().get(0));
		return ptrPhi;
	}

	private GetElementPtrInstruction insertLatchStep(BasicBlock latch, PhiInstruction ptrPhi, Value stepValue) {
		GetElementPtrInstruction stepGep = IRBuilder.createGetElementPtr(ptrPhi, stepValue, null);
		Instruction latchTerm = latch.getTerminator();
		if (latchTerm != null) latch.addInstructionBefore(stepGep, latchTerm); else latch.addInstruction(stepGep);
		return stepGep;
	}

	private ReplacementStats replaceGroupUses(Loop loop, List<GetElementPtrInstruction> geps, Value replacement) {
		int totalUses = 0;
		int replacedInLoop = 0;
		for (GetElementPtrInstruction gep : geps) {
			totalUses += gep.getUsers().size();
			replacedInLoop += replaceUsesInLoopWith(gep, replacement, new HashSet<>(loop.getBlocks()));
		}
		int removedCount = 0;
		for (GetElementPtrInstruction gep : geps) {
			if (gep.getUsers().isEmpty()) {
				gep.getParent().removeInstruction(gep);
				removedCount++;
			}
		}
		return new ReplacementStats(totalUses, replacedInLoop, removedCount);
	}

	private void debugLog(String msg) {
		if (debug) System.out.println("[LoopPointerMotion] " + msg);
	}

	private static class TargetGepData {
		final List<GetElementPtrInstruction> targets = new ArrayList<>();
		final Map<GetElementPtrInstruction, Value> invariantMap = new HashMap<>();
		final Map<GetElementPtrInstruction, Value> basePtrMap = new HashMap<>();
	}

	private static class InitInsertResult {
		final BinaryInstruction initOffset;
		final GetElementPtrInstruction initGep;
		InitInsertResult(BinaryInstruction initOffset, GetElementPtrInstruction initGep) {
			this.initOffset = initOffset;
			this.initGep = initGep;
		}
	}

	private static class ReplacementStats {
		final int totalUses;
		final int replacedInLoop;
		final int removedCount;
		ReplacementStats(int totalUses, int replacedInLoop, int removedCount) {
			this.totalUses = totalUses;
			this.replacedInLoop = replacedInLoop;
			this.removedCount = removedCount;
		}
	}

	private static class GroupKey {
		final Value base;
		final Value invariant;
		GroupKey(Value base, Value invariant) { this.base = base; this.invariant = invariant; }
		@Override public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof GroupKey other)) return false;
			return this.base == other.base && this.invariant == other.invariant;
		}
		@Override public int hashCode() {
			return System.identityHashCode(base) * 31 + System.identityHashCode(invariant);
		}
	}

	private void replaceAllUsesWith(Value oldValue, Value newValue) {
		List<User> users = new ArrayList<>(oldValue.getUsers());
		for (User user : users) {
			user.replaceAllUsesWith(oldValue, newValue);
		}
	}

	private int replaceUsesInLoopWith(Value oldValue, Value newValue, Set<BasicBlock> loopBlocks) {
		int count = 0;
		List<User> users = new ArrayList<>(oldValue.getUsers());
		for (User user : users) {
			if (user instanceof Instruction inst) {
				BasicBlock bb = inst.getParent();
				if (loopBlocks.contains(bb)) {
					user.replaceAllUsesWith(oldValue, newValue);
					count++;
				}
			}
		}
		return count;
	}
} 
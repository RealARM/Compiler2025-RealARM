package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.Optimization.Core.Optimizer.ModuleOptimizer;
import MiddleEnd.Optimization.Analysis.LoopAnalysis;
import MiddleEnd.Optimization.Analysis.Loop;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.Constant;
import MiddleEnd.IR.Value.ConstantInt;
import MiddleEnd.IR.Value.Instructions.BranchInstruction;
import MiddleEnd.IR.Value.Instructions.CompareInstruction;
import MiddleEnd.IR.Value.Instructions.BinaryInstruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.OpCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LoopInterchange implements ModuleOptimizer {
	@Override
	public String getName() {
		return "LoopInterchange";
	}

	@Override
	public boolean run(Module module) {
		boolean changed = false;
		for (Function function : module.functions()) {
			changed |= runOnFunction(function);
		}
		return changed;
	}

	private boolean runOnFunction(Function function) {
		boolean changed = false;
		LoopAnalysis.runLoopInfo(function);
		LoopAnalysis.analyzeInductionVariables(function);

		List<Loop> dfsLoops = LoopAnalysis.getAllLoopsInDFSOrder(function);

		for (Loop loop : dfsLoops) {
			changed |= tryInterchange(loop);
		}
		return changed;
	}

	private boolean tryInterchange(Loop loop) {
		if (loop == null) return false;
		if (!isSimpleCountedLoop(loop)) return false;

		BasicBlock header = loop.getHeader();
		if (header == null) return false;
		if (loop.getLatchBlocks().size() != 1) return false;

		if (!isPerfectlyNestedWithSingleInner(loop)) return false;

		Loop inner = loop.getSubLoops().get(0);

		if (!(loop.getInductionVariable() instanceof PhiInstruction outerPhi)) return false;
		if (!(inner.getInductionVariable() instanceof PhiInstruction innerPhi)) return false;

		if (!(loop.getStepValue() instanceof ConstantInt outerStep)) return false;
		if (!(inner.getStepValue() instanceof ConstantInt innerStep)) return false;
		if (outerStep.getValue() != innerStep.getValue()) return false;
		if (!(loop.getInitValue() instanceof ConstantInt outerInit)) return false;
		if (!(inner.getInitValue() instanceof ConstantInt innerInit)) return false;
		if (!(loop.getEndValue() instanceof ConstantInt outerEnd)) return false;
		if (!(inner.getEndValue() instanceof ConstantInt innerEnd)) return false;
		if (!(loop.getUpdateInstruction() instanceof BinaryInstruction outerUpdate)) return false;
		if (!(inner.getUpdateInstruction() instanceof BinaryInstruction innerUpdate)) return false;
		if (outerUpdate.getOpCode() != OpCode.ADD) return false;
		if (innerUpdate.getOpCode() != OpCode.ADD) return false;

		CompareInstruction outerCmp = getHeaderCompare(loop);
		CompareInstruction innerCmp = getHeaderCompare(inner);
		if (outerCmp == null || innerCmp == null) return false;
		if (!outerCmp.isRelationalCompare() || !innerCmp.isRelationalCompare()) return false;

		BasicBlock outerHeader = loop.getHeader();
		BasicBlock innerHeader = inner.getHeader();
		BasicBlock outerPreheader = loop.getPreheader();
		BasicBlock innerPreheader = inner.getPreheader();
		if (outerPreheader == null || innerPreheader == null) return false;

		BranchInstruction outerBr = null;
		if (outerHeader.getTerminator() instanceof BranchInstruction ob && !ob.isUnconditional()) {
			outerBr = ob;
		}
		BranchInstruction innerBr = null;
		if (innerHeader.getTerminator() instanceof BranchInstruction ib && !ib.isUnconditional()) {
			innerBr = ib;
		}
		if (outerBr == null || innerBr == null) return false;

		boolean changed = false;

		// 1) 重写外层比较：使用 innerPhi 与 innerEnd，并保持与原比较相同的自变量位置关系
		CompareInstruction newOuterCmp = createCmpLike(outerCmp, outerPhi, innerPhi, innerEnd);
		newOuterCmp.insertBefore(outerBr);
		outerBr.setOperand(0, newOuterCmp);
		changed = true;

		// 2) 重写内层比较：使用 outerPhi 与 outerEnd
		CompareInstruction newInnerCmp = createCmpLike(innerCmp, innerPhi, outerPhi, outerEnd);
		newInnerCmp.insertBefore(innerBr);
		innerBr.setOperand(0, newInnerCmp);

		// 3) 交换 PHI 的来自各自前置头的初值
		outerPhi.addOrUpdateIncoming(innerInit, outerPreheader);
		innerPhi.addOrUpdateIncoming(outerInit, innerPreheader);

		// 4) 调整更新指令：将“自变量”替换为对方 iv，将常量步长替换为对方 step
		rewriteAddUpdate(outerUpdate, outerPhi, innerPhi, innerStep);
		rewriteAddUpdate(innerUpdate, innerPhi, outerPhi, outerStep);

		System.out.println("[LoopInterchange] Interchanged (outer:" + loop.getHeader().getName() + ", inner:" + inner.getHeader().getName() + ")");
		return changed;
	}

	private CompareInstruction createCmpLike(CompareInstruction origin, Value originIv, Value newIv, Value newEnd) {
		Value left = origin.getOperand(0);
		Value right = origin.getOperand(1);
		if (left == originIv) {
			return IRBuilder.createCompare(origin.getCompareType(), origin.getPredicate(), newIv, newEnd, null);
		} else if (right == originIv) {
			return IRBuilder.createCompare(origin.getCompareType(), origin.getPredicate(), newEnd, newIv, null);
		}
		// Fallback: place as left
		return IRBuilder.createCompare(origin.getCompareType(), origin.getPredicate(), newIv, newEnd, null);
	}

	private void rewriteAddUpdate(BinaryInstruction addInst, Value oldIv, Value newIv, ConstantInt newStep) {
		Value left = addInst.getOperand(0);
		Value right = addInst.getOperand(1);
		if (left == oldIv) {
			addInst.setOperand(0, newIv);
			addInst.setOperand(1, newStep);
		} else if (right == oldIv) {
			addInst.setOperand(1, newIv);
			addInst.setOperand(0, newStep);
		}
	}

	private CompareInstruction getHeaderCompare(Loop l) {
		if (l == null || l.getHeader() == null) return null;
		if (!(l.getHeader().getTerminator() instanceof BranchInstruction br) || br.isUnconditional()) return null;
		Value cond = br.getCondition();
		if (cond instanceof CompareInstruction cmp) return cmp;
		return null;
	}

	private boolean isPerfectlyNestedWithSingleInner(Loop outer) {
		List<Loop> subLoops = outer.getSubLoops();
		if (subLoops.size() != 1) return false;
		Loop inner = subLoops.get(0);

		if (!isSimpleCountedLoop(inner)) return false;
		if (inner.getLatchBlocks().size() != 1) return false;

		if (outer.getPreheader() == null) return false;
		if (inner.getPreheader() == null) return false;

		Set<BasicBlock> outerBlocks = outer.getBlocks();
		Set<BasicBlock> innerBlocks = inner.getBlocks();
		Set<BasicBlock> outerLatchSet = new HashSet<>(outer.getLatchBlocks());
		for (BasicBlock block : outerBlocks) {
			if (block == outer.getHeader()) continue;
			if (outerLatchSet.contains(block)) continue;
			if (!innerBlocks.contains(block)) return false;
		}

		return true;
	}

	private boolean isSimpleCountedLoop(Loop loop) {
		if (loop == null) return false;
		if (!loop.hasInductionVariable()) return false;
		Value init = loop.getInitValue();
		Value end = loop.getEndValue();
		Value step = loop.getStepValue();
		if (init == null || end == null || step == null) return false;
		if (!(step instanceof ConstantInt constInt)) return false;
		if (constInt.getValue() == 0) return false;

		BasicBlock header = loop.getHeader();
		if (header != null && header.getTerminator() instanceof BranchInstruction br && !br.isUnconditional()) {
			Value cond = br.getCondition();
			if (cond instanceof CompareInstruction cmp) {
				boolean relational = cmp.isRelationalCompare();
				if (!relational) return false;
			}
		}
		return true;
	}
}

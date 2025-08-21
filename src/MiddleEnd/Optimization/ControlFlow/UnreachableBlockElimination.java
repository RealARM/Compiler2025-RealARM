package MiddleEnd.Optimization.ControlFlow;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.TerminatorInstruction;
import MiddleEnd.IR.IRBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 移除不可达基本块
 */
public class UnreachableBlockElimination implements Optimizer.ModuleOptimizer {
	@Override
	public String getName() {
		return "UnreachableBlockElimination";
	}

	@Override
	public boolean run(Module module) {
		boolean changed = false;
		for (Function function : module.functions()) {
			if (function.isExternal()) continue;
			changed |= eliminate(function);
		}
		return changed;
	}

	private boolean eliminate(Function function) {
		BasicBlock entry = function.getEntryBlock();
		if (entry == null) return false;
		Set<BasicBlock> reachable = new HashSet<>();
		markReachable(entry, reachable);
		List<BasicBlock> all = new ArrayList<>(function.getBasicBlocks());
		boolean changed = false;
		for (BasicBlock bb : all) {
			if (!reachable.contains(bb)) {
				bb.removeFromParent();
				changed = true;
			}
		}
		if (changed) {
			IRBuilder.validateAllPhiNodes(function);
		}
		return changed;
	}

	private void markReachable(BasicBlock start, Set<BasicBlock> visited) {
		if (visited.contains(start)) return;
		visited.add(start);
		Instruction term = start.getTerminator();
		if (term instanceof TerminatorInstruction) {
			for (BasicBlock succ : ((TerminatorInstruction) term).getSuccessors()) {
				if (succ != null) {
					markReachable(succ, visited);
				}
			}
		}
	}
} 
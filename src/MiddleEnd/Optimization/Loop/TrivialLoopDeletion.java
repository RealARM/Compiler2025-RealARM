package MiddleEnd.Optimization.Loop;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.User;
import MiddleEnd.IR.Value.Value;
import MiddleEnd.IR.Value.Instructions.BranchInstruction;
import MiddleEnd.IR.Value.Instructions.CompareInstruction;
import MiddleEnd.IR.Value.Instructions.Instruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.Optimization.Analysis.Loop;
import MiddleEnd.Optimization.Analysis.LoopAnalysis;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.*;

public class TrivialLoopDeletion implements Optimizer.ModuleOptimizer {
	@Override
	public String getName() {
		return "TrivialLoopDeletion";
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
		LoopAnalysis.runLoopInfo(function);
		LoopAnalysis.runLoopIndVarInfo(function);

		List<Loop> allLoops = LoopAnalysis.getAllLoopsInDFSOrder(function);
		for (Loop loop : allLoops) {
			if (!isCandidate(loop)) {
				continue;
			}

			BasicBlock entering = selectEnteringPredecessor(loop);
			BasicBlock exit = selectSingleExit(loop);
			if (entering == null || exit == null) {
				continue;
			}

			changed |= rewriteAndRemove(loop, entering, exit);
		}
		return changed;
	}

	private boolean isCandidate(Loop loop) {
		if (!loop.getSubLoops().isEmpty()) return false;
		if (!loop.hasInductionVariable()) return false;

		BasicBlock header = loop.getHeader();
		if (header == null) return false;

		Instruction headerTerm = header.getTerminator();
		if (!(headerTerm instanceof BranchInstruction headBr)) return false;
		if (headBr.isUnconditional()) return false;

		Value cond = headBr.getCondition();
		if (!(cond instanceof CompareInstruction headCmp)) return false;

		Instruction updateInst = loop.getUpdateInstruction();
		if (updateInst == null) return false;

		if (loop.getLatchBlocks().size() != 1) return false;
		if (loop.getExitBlocks().size() != 1) return false;

		Value indVar = loop.getInductionVariable();
		if (!(indVar instanceof Instruction indPhi) || !(indPhi instanceof PhiInstruction)) return false;

		BasicBlock latch = loop.getLatchBlocks().get(0);
		Set<Instruction> allowed = computeAllowedInstructions(headBr, headCmp, updateInst, indPhi, latch);

		if (!onlyContainsAllowedInstructions(loop, allowed)) return false;
		if (!allInductionUsesAllowed(indVar, allowed)) return false;

		return true;
	}

	private Set<Instruction> computeAllowedInstructions(BranchInstruction headBr,
			CompareInstruction headCmp,
			Instruction updateInst,
			Instruction indPhi,
			BasicBlock latch) {
		Set<Instruction> allowed = new HashSet<>();
		allowed.add(indPhi);
		allowed.add(headCmp);
		allowed.add(headBr);
		allowed.add(updateInst);
		Instruction latchTerm = latch.getTerminator();
		if (latchTerm instanceof Instruction) {
			allowed.add(latchTerm);
		}
		return allowed;
	}

	private boolean onlyContainsAllowedInstructions(Loop loop, Set<Instruction> allowed) {
		for (BasicBlock bb : loop.getBlocks()) {
			for (Instruction inst : new ArrayList<>(bb.getInstructions())) {
				if (!allowed.contains(inst)) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean allInductionUsesAllowed(Value indVar, Set<Instruction> allowed) {
		for (User user : new ArrayList<>(indVar.getUsers())) {
			if (!(user instanceof Instruction uInst)) return false;
			if (!allowed.contains(uInst)) return false;
		}
		return true;
	}

	private BasicBlock selectEnteringPredecessor(Loop loop) {
		BasicBlock header = loop.getHeader();
		BasicBlock entering = null;
		for (BasicBlock pred : header.getPredecessors()) {
			if (!loop.getBlocks().contains(pred)) {
				entering = pred;
				break;
			}
		}
		return entering;
	}

	private BasicBlock selectSingleExit(Loop loop) {
		for (BasicBlock e : loop.getExitBlocks()) {
			return e;
		}
		return null;
	}

	private boolean rewriteAndRemove(Loop loop, BasicBlock entering, BasicBlock exit) {
		BasicBlock header = loop.getHeader();
		entering.updateBranchTarget(header, exit);

		List<BasicBlock> toDelete = new ArrayList<>(loop.getBlocks());
		for (BasicBlock bb : toDelete) {
			bb.removeFromParent();
		}
		return true;
	}
}
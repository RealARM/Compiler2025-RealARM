package MiddleEnd.Optimization.ControlFlow;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.*;

/**
 * 直线基本块合并：pred 无条件跳到 succ 且 succ 只有一个前驱时，将 succ 合并进 pred
 */
public class StraightLineBlockMerge implements Optimizer.ModuleOptimizer {
	@Override
	public String getName() {
		return "StraightLineBlockMerge";
	}

	@Override
	public boolean run(Module module) {
		boolean changed = false;
		for (Function function : module.functions()) {
			if (function.isExternal()) continue;
			changed |= merge(function);
		}
		return changed;
	}

	private boolean merge(Function function) {
		boolean changed = false;
		boolean localChanged;
		do {
			localChanged = false;
			List<BasicBlock> blocks = new ArrayList<>(function.getBasicBlocks());
			for (BasicBlock pred : blocks) {
				Instruction term = pred.getTerminator();
				if (!(term instanceof BranchInstruction br) || !br.isUnconditional()) continue;
				BasicBlock succ = br.getTrueBlock();
				if (succ == pred) continue;
				if (succ.getPredecessors().size() != 1) continue;
				if (succ == function.getEntryBlock()) continue;

				for (Instruction inst : new ArrayList<>(succ.getInstructions())) {
					if (!(inst instanceof PhiInstruction phi)) break;
					List<BasicBlock> incomings = phi.getIncomingBlocks();
					if (incomings.size() == 1 && incomings.get(0) == pred) {
						Value inVal = phi.getIncomingValue(pred);
						for (User user : new ArrayList<>(phi.getUsers())) {
							user.replaceAllUsesWith(phi, inVal);
						}
						phi.removeFromParent();
					} else {
						continue;
					}
				}

				pred.removeInstruction(br);

				for (Instruction inst : new ArrayList<>(succ.getInstructions())) {
					inst.removeFromParent();
					pred.addInstruction(inst);
				}

				for (BasicBlock succsucc : new ArrayList<>(succ.getSuccessors())) {
					Map<PhiInstruction, Value> phiValues = new LinkedHashMap<>();
					for (Instruction inst : succsucc.getInstructions()) {
						if (!(inst instanceof PhiInstruction phi)) break;
						Value valFromSucc = phi.getIncomingValue(succ);
						if (valFromSucc != null) {
							phiValues.put(phi, valFromSucc);
						}
					}

					succsucc.removePredecessor(succ);

					if (!pred.getSuccessors().contains(succsucc)) {
						pred.addSuccessor(succsucc);
					}

					for (Map.Entry<PhiInstruction, Value> e : phiValues.entrySet()) {
						PhiInstruction phi = e.getKey();
						Value val = e.getValue();
						phi.addOrUpdateIncoming(val, pred);
					}
				}

				succ.removeFromParent();
				localChanged = true;
				changed = true;
				break;
			}
		} while (localChanged);
		return changed;
	}
} 
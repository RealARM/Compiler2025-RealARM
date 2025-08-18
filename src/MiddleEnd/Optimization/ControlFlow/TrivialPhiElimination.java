package MiddleEnd.Optimization.ControlFlow;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 删除平凡PHI：只有一个来边的PHI直接用该值替换
 */
public class TrivialPhiElimination implements Optimizer.ModuleOptimizer {
	@Override
	public String getName() {
		return "TrivialPhiElimination";
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
		boolean changed = false;
		boolean localChanged;
		do {
			localChanged = false;
			for (BasicBlock block : function.getBasicBlocks()) {
				List<Instruction> insts = new ArrayList<>(block.getInstructions());
				for (Instruction inst : insts) {
					if (!(inst instanceof PhiInstruction phi)) {
						break;
					}
					List<BasicBlock> incomings = phi.getIncomingBlocks();
					if (incomings.size() == 1) {
						BasicBlock inBlock = incomings.get(0);
						Value inVal = phi.getIncomingValue(inBlock);
						for (User user : new ArrayList<>(phi.getUsers())) {
							user.replaceAllUsesWith(phi, inVal);
						}
						phi.removeFromParent();
						localChanged = true;
						changed = true;
					}
				}
			}
		} while (localChanged);
		return changed;
	}
} 
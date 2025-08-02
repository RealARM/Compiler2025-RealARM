package MiddleEnd.Optimization.Core;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.Function;

/**
 * IR优化器接口
 */
public interface Optimizer {
    String getName();
    
    interface ModuleOptimizer extends Optimizer {
        boolean run(Module module);
    }
    
    interface FunctionOptimizer extends Optimizer {
        boolean run(Function function);
    }
    
    interface Analyzer extends Optimizer {
        void run(Module module);
        
        Object getResult();
    }
}
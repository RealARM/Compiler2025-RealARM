package MiddleEnd.Optimization.Core;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.Function;

/**
 * IR优化器接口
 */
public interface Optimizer {
    /**
     * 获取优化器的名称
     */
    String getName();
    
    /**
     * 模块级优化器接口，处理整个IR模块
     */
    interface ModuleOptimizer extends Optimizer {
        /**
         * 运行优化器
         * @param module IR模块
         * @return 如果IR发生变化返回true，否则返回false
         */
        boolean run(Module module);
    }
    
    /**
     * 函数级优化器接口，处理单个函数
     */
    interface FunctionOptimizer extends Optimizer {
        /**
         * 运行优化器
         * @param function 目标函数
         * @return 如果IR发生变化返回true，否则返回false
         */
        boolean run(Function function);
    }
    
    /**
     * 分析器接口，不修改IR，只进行分析
     */
    interface Analyzer extends Optimizer {
        /**
         * 运行分析
         * @param module IR模块
         */
        void run(Module module);
        
        /**
         * 获取分析结果
         * @return 分析结果
         */
        Object getResult();
    }
}
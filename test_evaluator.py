#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
LLVM评测机的使用示例
"""

import os
from llvm_evaluator import LLVMEvaluator

def main():
    # 创建评测器实例
    evaluator = LLVMEvaluator()
    
    print("=== 测试1: 测试单个无输入的LLVM文件 ===")
    passed, result = evaluator.evaluate_file("testcases/hello_world.ll")
    print(f"测试结果: {'通过' if passed else '失败'}")
    print()
    
    print("=== 测试2: 测试带有输入的LLVM文件 ===")
    passed, result = evaluator.evaluate_file("testcases/sum.ll")
    print(f"测试结果: {'通过' if passed else '失败'}")
    print()
    
    print("=== 测试3: 测试带有自定义返回值的LLVM文件 ===")
    passed, result = evaluator.evaluate_file("testcases/return_value.ll", expected_return=42)
    print(f"测试结果: {'通过' if passed else '失败'}")
    print()
    
    print("=== 测试4: 测试所有LLVM文件 ===")
    # 测试默认返回值为0的文件
    standard_files = ["testcases/hello_world.ll", "testcases/sum.ll"]
    results_standard = evaluator.evaluate_all(standard_files)
    
    # 测试返回值为42的文件
    custom_files = ["testcases/return_value.ll"]
    results_custom = evaluator.evaluate_all(custom_files, expected_return=42)
    
    # 总结果
    total_passed = results_standard["passed"] + results_custom["passed"]
    total_tests = results_standard["total"] + results_custom["total"]
    
    print("=" * 50)
    print(f"总测试结果: {total_passed}/{total_tests} 通过")
    print(f"通过率: {(total_passed / total_tests) * 100:.2f}%")
    print("=" * 50)
    
if __name__ == "__main__":
    main() 
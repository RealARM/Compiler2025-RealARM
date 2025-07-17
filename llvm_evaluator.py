#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import subprocess
import difflib
import argparse
import tempfile
import glob
import re
from pathlib import Path
from typing import Dict, List, Tuple, Optional, Union


class LLVMEvaluator:
    """评测LLVM IR文件的工具类"""
    
    def __init__(self, 
                 llvm_path: str = "lli",
                 testcase_dir: str = "testcases",
                 input_dir: str = "input",
                 output_dir: str = "output", 
                 expected_dir: str = "expected"):
        """
        初始化LLVM评测器
        
        Args:
            llvm_path: LLVM解释器路径
            testcase_dir: 测试用例目录
            input_dir: 输入文件目录 (相对于testcase_dir)
            output_dir: 输出文件目录 (相对于testcase_dir)
            expected_dir: 期望输出目录 (相对于testcase_dir)
        """
        self.llvm_path = llvm_path
        self.testcase_dir = testcase_dir
        self.input_dir = os.path.join(testcase_dir, input_dir)
        self.output_dir = os.path.join(testcase_dir, output_dir)
        self.expected_dir = os.path.join(testcase_dir, expected_dir)
        
        # 确保输出目录存在
        os.makedirs(self.output_dir, exist_ok=True)
        
    def get_input_file_path(self, test_name: str) -> Optional[str]:
        """
        获取测试用例对应的输入文件路径
        
        Args:
            test_name: 测试名称
            
        Returns:
            输入文件路径，若不存在则返回None
        """
        # 查找可能的输入文件模式
        patterns = [
            f"{test_name}_input.txt",
            f"{test_name}.in",
            f"input_{test_name}.txt",
        ]
        
        for pattern in patterns:
            input_path = os.path.join(self.input_dir, pattern)
            if os.path.exists(input_path):
                return input_path
        
        return None
    
    def get_expected_file_path(self, test_name: str) -> Optional[str]:
        """
        获取测试用例对应的期望输出文件路径
        
        Args:
            test_name: 测试名称
            
        Returns:
            期望输出文件路径，若不存在则返回None
        """
        # 查找可能的期望输出文件模式
        patterns = [
            f"{test_name}_output.txt",
            f"{test_name}.out",
            f"{test_name}.txt",
            f"expected_{test_name}.txt",
        ]
        
        for pattern in patterns:
            expected_path = os.path.join(self.expected_dir, pattern)
            if os.path.exists(expected_path):
                return expected_path
        
        return None
    
    def run_llvm_file(self, ll_file: str, input_file: Optional[str] = None) -> Tuple[int, str]:
        """
        执行LLVM IR文件
        
        Args:
            ll_file: LLVM IR文件路径
            input_file: 输入文件路径，可选
            
        Returns:
            返回值和标准输出内容
        """
        cmd = [self.llvm_path, ll_file]
        
        try:
            if input_file:
                with open(input_file, 'r') as f:
                    input_data = f.read()
                
                # 执行命令并提供输入数据
                proc = subprocess.Popen(
                    cmd, 
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True
                )
                stdout, stderr = proc.communicate(input=input_data, timeout=10)
                
                if stderr and not stderr.isspace():
                    print(f"Warning: stderr for {ll_file}: {stderr}", file=sys.stderr)
                
                return proc.returncode, stdout
            else:
                # 执行命令但不提供输入
                proc = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=10
                )
                
                if proc.stderr and not proc.stderr.isspace():
                    print(f"Warning: stderr for {ll_file}: {proc.stderr}", file=sys.stderr)
                    
                return proc.returncode, proc.stdout
                
        except subprocess.TimeoutExpired:
            print(f"Error: Timeout when executing {ll_file}", file=sys.stderr)
            return -1, "TIMEOUT"
        except Exception as e:
            print(f"Error executing {ll_file}: {e}", file=sys.stderr)
            return -1, str(e)
    
    def save_output(self, test_name: str, output: str) -> str:
        """
        保存输出到文件
        
        Args:
            test_name: 测试名称
            output: 输出内容
            
        Returns:
            输出文件路径
        """
        output_file = os.path.join(self.output_dir, f"{test_name}_output.txt")
        
        with open(output_file, 'w') as f:
            f.write(output)
        
        return output_file
    
    def compare_output(self, output_file: str, expected_file: str) -> Tuple[bool, str]:
        """
        比较输出与期望输出
        
        Args:
            output_file: 实际输出文件路径
            expected_file: 期望输出文件路径
            
        Returns:
            是否匹配和差异信息
        """
        with open(output_file, 'r') as f:
            output = f.read()
        
        with open(expected_file, 'r') as f:
            expected = f.read()
        
        # 尝试规范化输出（删除多余空白）
        output = ' '.join(output.strip().split())
        expected = ' '.join(expected.strip().split())
        
        if output == expected:
            return True, "Output matches expected result"
        else:
            # 生成差异
            diff = difflib.unified_diff(
                expected.splitlines(keepends=True),
                output.splitlines(keepends=True),
                fromfile='Expected',
                tofile='Actual'
            )
            return False, ''.join(diff)
    
    def evaluate_file(self, ll_file: str, expected_return: int = 0) -> Tuple[bool, Dict]:
        """
        评测单个LLVM IR文件
        
        Args:
            ll_file: LLVM IR文件路径
            expected_return: 期望的返回值
            
        Returns:
            是否通过测试和详细结果
        """
        # 获取测试名称（不带扩展名）
        test_name = os.path.splitext(os.path.basename(ll_file))[0]
        
        # 查找输入文件
        input_file = self.get_input_file_path(test_name)
        
        # 查找期望输出文件
        expected_file = self.get_expected_file_path(test_name)
        if not expected_file:
            print(f"Warning: No expected output file found for {test_name}")
        
        # 执行LLVM文件
        return_code, output = self.run_llvm_file(ll_file, input_file)
        
        # 保存输出
        output_file = self.save_output(test_name, output)
        
        result = {
            "file": ll_file,
            "test_name": test_name,
            "return_code": return_code,
            "expected_return_code": expected_return,
            "return_code_correct": return_code == expected_return,
            "output_file": output_file,
            "input_file": input_file,
            "output_matches": None,
            "diff": None
        }
        
        # 如果有期望输出文件，比较输出
        if expected_file:
            output_matches, diff = self.compare_output(output_file, expected_file)
            result["output_matches"] = output_matches
            result["expected_file"] = expected_file
            
            if not output_matches:
                result["diff"] = diff
        
        # 判断测试是否通过
        test_passed = result["return_code_correct"]
        if "output_matches" in result and result["output_matches"] is not None:
            test_passed = test_passed and result["output_matches"]
        
        return test_passed, result
    
    def evaluate_all(self, llvm_files: List[str] = None, expected_return: int = 0) -> Dict:
        """
        评测多个LLVM IR文件
        
        Args:
            llvm_files: LLVM IR文件路径列表，如果为None则扫描testcase_dir下的所有.ll文件
            expected_return: 期望的返回值
            
        Returns:
            评测结果汇总
        """
        if llvm_files is None:
            # 获取所有.ll文件
            llvm_files = glob.glob(os.path.join(self.testcase_dir, "*.ll"))
        
        results = {
            "total": len(llvm_files),
            "passed": 0,
            "failed": 0,
            "details": {}
        }
        
        for ll_file in llvm_files:
            print(f"Testing {os.path.basename(ll_file)}...")
            passed, detail = self.evaluate_file(ll_file, expected_return)
            
            if passed:
                results["passed"] += 1
                status = "PASSED"
            else:
                results["failed"] += 1
                status = "FAILED"
                
            results["details"][os.path.basename(ll_file)] = {
                "status": status,
                "detail": detail
            }
            
            # 显示测试结果
            print(f"  Result: {status}")
            if not passed:
                if not detail["return_code_correct"]:
                    print(f"  Expected return code {detail['expected_return_code']}, got {detail['return_code']}")
                
                if detail.get("output_matches") is False:
                    print(f"  Output doesn't match expected:")
                    print(detail["diff"])
            
            print()
        
        return results


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description='LLVM IR Evaluator')
    parser.add_argument('--llvm-path', default='lli', help='Path to LLVM interpreter (lli)')
    parser.add_argument('--testcase-dir', default='testcases', help='Directory containing test cases')
    parser.add_argument('--input-dir', default='input', help='Directory containing input files (relative to testcase-dir)')
    parser.add_argument('--output-dir', default='output', help='Directory for output files (relative to testcase-dir)')
    parser.add_argument('--expected-dir', default='expected', help='Directory containing expected output files (relative to testcase-dir)')
    parser.add_argument('--expected-return', type=int, default=0, help='Expected return code')
    parser.add_argument('files', nargs='*', help='LLVM IR files to evaluate (if not provided, all .ll files in testcase-dir will be tested)')
    
    args = parser.parse_args()
    
    evaluator = LLVMEvaluator(
        llvm_path=args.llvm_path,
        testcase_dir=args.testcase_dir,
        input_dir=args.input_dir,
        output_dir=args.output_dir,
        expected_dir=args.expected_dir
    )
    
    files_to_test = args.files if args.files else None
    results = evaluator.evaluate_all(files_to_test, args.expected_return)
    
    print("=" * 50)
    print(f"Test Results: {results['passed']}/{results['total']} passed")
    print(f"Pass rate: {results['passed'] / results['total'] * 100:.2f}%")
    print("=" * 50)
    
    return 0 if results['failed'] == 0 else 1


if __name__ == "__main__":
    sys.exit(main()) 
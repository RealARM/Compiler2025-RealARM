#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import subprocess
import difflib
import argparse
import glob
import shutil
from pathlib import Path
from typing import Dict, List, Tuple, Optional, Union
import time
import re

class CompilerEvaluator:
    """编译器评测工具类"""
    
    def __init__(self, 
                 jar_path: str = "TestIR.jar",
                 test_dirs: List[str] = ["tests", "ARM-性能"],
                 logs_dir: str = "logs"):
        """
        初始化编译器评测器
        
        Args:
            jar_path: TestIR.jar路径
            test_dirs: 测试用例目录列表
            logs_dir: 日志输出目录
        """
        self.jar_path = jar_path
        self.test_dirs = test_dirs
        self.logs_dir = logs_dir
        
        # 确保日志目录存在
        os.makedirs(self.logs_dir, exist_ok=True)
        
        # 记录时间戳，用于创建唯一的日志子目录
        self.timestamp = time.strftime("%Y%m%d_%H%M%S")
        self.current_log_dir = os.path.join(self.logs_dir, f"run_{self.timestamp}")
        os.makedirs(self.current_log_dir, exist_ok=True)
        
        # 统计信息
        self.stats = {
            "total": 0,
            "passed": 0,
            "failed": 0,
            "details": {}
        }
        
    def find_test_cases(self) -> List[Dict]:
        """
        查找所有测试用例
        
        Returns:
            测试用例信息列表
        """
        test_cases = []
        
        for test_dir in self.test_dirs:
            if not os.path.exists(test_dir):
                print(f"Warning: Test directory {test_dir} does not exist")
                continue
            
            # 寻找所有.sy文件
            if test_dir == "tests":
                # tests目录下有多个子目录
                for subdir in os.listdir(test_dir):
                    subdir_path = os.path.join(test_dir, subdir)
                    if os.path.isdir(subdir_path):
                        # First try to find .sy files
                        sy_files = glob.glob(os.path.join(subdir_path, "*.sy"))
                        if not sy_files:
                            # If no .sy files, try to find other possible source files
                            sy_files = glob.glob(os.path.join(subdir_path, "in")) + \
                                      glob.glob(os.path.join(subdir_path, "*.c"))
                        
                        for sy_file in sy_files:
                            test_case = self._create_test_case_info(sy_file, subdir_path)
                            if test_case:
                                test_cases.append(test_case)
            else:
                # ARM-性能目录直接包含.sy文件
                for sy_file in glob.glob(os.path.join(test_dir, "*.sy")):
                    test_case = self._create_test_case_info(sy_file, test_dir)
                    if test_case:
                        test_cases.append(test_case)
        
        if not test_cases:
            print(f"Error: No test cases found in directories {self.test_dirs}")
            
        return test_cases
    
    def _create_test_case_info(self, sy_file: str, dir_path: str) -> Optional[Dict]:
        """
        创建测试用例信息
        
        Args:
            sy_file: SysY源文件路径
            dir_path: 测试用例所在目录
            
        Returns:
            测试用例信息字典
        """
        base_name = os.path.splitext(os.path.basename(sy_file))[0]
        test_name = f"{os.path.basename(dir_path)}_{base_name}" if "tests" in dir_path else base_name
        
        # 查找输入文件
        in_file = self._find_file(dir_path, base_name, [".in", "_input.txt", "input.txt"])
        if not in_file and os.path.basename(sy_file) == "in":
            # If the source file itself is named "in", use it as the input file as well
            in_file = sy_file
            
        # 查找期望输出文件
        out_file = self._find_file(dir_path, base_name, [".out", "_output.txt", "output.txt"])
        if not out_file:
            # Try looking for general output files in the directory
            potential_outputs = glob.glob(os.path.join(dir_path, "*.out")) + \
                              glob.glob(os.path.join(dir_path, "*_output.txt")) + \
                              glob.glob(os.path.join(dir_path, "output.txt"))
            if potential_outputs:
                out_file = potential_outputs[0]
        
        if not out_file:
            print(f"Warning: No output file found for {sy_file}")
            return None
        
        return {
            "name": test_name,
            "source_file": sy_file,
            "input_file": in_file,
            "expected_output": out_file,
            "directory": dir_path
        }
    
    def _find_file(self, dir_path: str, base_name: str, extensions: List[str]) -> Optional[str]:
        """
        查找特定扩展名的文件
        
        Args:
            dir_path: 目录路径
            base_name: 基础文件名
            extensions: 扩展名列表
            
        Returns:
            找到的文件路径，未找到则返回None
        """
        for ext in extensions:
            file_path = os.path.join(dir_path, f"{base_name}{ext}")
            if os.path.exists(file_path):
                return file_path
        return None
    
    def run_test_case(self, test_case: Dict) -> Dict:
        """
        运行单个测试用例
        
        Args:
            test_case: 测试用例信息
            
        Returns:
            测试结果信息
        """
        # 创建临时目录用于存放生成的LLVM IR文件
        test_log_dir = os.path.join(self.current_log_dir, test_case["name"])
        os.makedirs(test_log_dir, exist_ok=True)
        
        # LLVM IR输出路径
        ll_file = os.path.join(test_log_dir, f"{test_case['name']}.ll")
        
        # 运行TestIR.jar生成LLVM IR
        generate_result = self._generate_llvm_ir(test_case["source_file"], ll_file)
        
        if not generate_result["success"]:
            return {
                "name": test_case["name"],
                "success": False,
                "stage": "compile",
                "error": generate_result["error"],
                "log_dir": test_log_dir
            }
        
        # 使用llvm_evaluator运行生成的IR代码
        run_result = self._run_llvm_file(ll_file, test_case["input_file"])
        
        # 保存程序输出
        output_file = os.path.join(test_log_dir, f"{test_case['name']}_output.txt")
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(run_result["output"])
        
        # 比较输出
        compare_result = self._compare_output(run_result["output"], test_case["expected_output"])
        
        # 如果测试不通过，记录详细信息
        if not compare_result["match"]:
            # 复制源文件、输入文件和期望输出文件到日志目录
            shutil.copy2(test_case["source_file"], os.path.join(test_log_dir, os.path.basename(test_case["source_file"])))
            if test_case["input_file"]:
                shutil.copy2(test_case["input_file"], os.path.join(test_log_dir, os.path.basename(test_case["input_file"])))
            shutil.copy2(test_case["expected_output"], os.path.join(test_log_dir, os.path.basename(test_case["expected_output"])))
            
            # 写入差异文件
            diff_file = os.path.join(test_log_dir, f"{test_case['name']}_diff.txt")
            with open(diff_file, 'w', encoding='utf-8') as f:
                f.write(compare_result["diff"])
        
        return {
            "name": test_case["name"],
            "success": compare_result["match"],
            "stage": "run",
            "return_code": run_result["return_code"],
            "output": run_result["output"],
            "expected": compare_result["expected"],
            "diff": compare_result["diff"] if not compare_result["match"] else None,
            "log_dir": test_log_dir
        }
    
    def _generate_llvm_ir(self, source_file: str, output_file: str) -> Dict:
        """
        使用TestIR.jar生成LLVM IR
        
        Args:
            source_file: 源文件路径
            output_file: 输出文件路径
            
        Returns:
            生成结果信息
        """
        try:
            cmd = ["java", "-jar", self.jar_path, source_file, output_file]
            process = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=30
            )
            
            if process.returncode != 0:
                return {
                    "success": False,
                    "error": f"TestIR.jar exited with code {process.returncode}\n{process.stderr}"
                }
            
            if not os.path.exists(output_file):
                return {
                    "success": False,
                    "error": f"LLVM IR file was not generated\n{process.stderr}"
                }
                
            return {
                "success": True,
                "output": process.stdout
            }
            
        except subprocess.TimeoutExpired:
            return {
                "success": False,
                "error": "Timeout when executing TestIR.jar"
            }
        except Exception as e:
            return {
                "success": False,
                "error": str(e)
            }
    
    def _run_llvm_file(self, ll_file: str, input_file: Optional[str]) -> Dict:
        """
        运行LLVM IR文件
        
        Args:
            ll_file: LLVM IR文件路径
            input_file: 输入文件路径
            
        Returns:
            运行结果信息
        """
        cmd = ["lli", ll_file]
        
        try:
            if input_file and os.path.exists(input_file):
                with open(input_file, 'r', encoding='utf-8') as f:
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
                
                return {
                    "return_code": proc.returncode,
                    "output": stdout,
                    "stderr": stderr
                }
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
                    
                return {
                    "return_code": proc.returncode,
                    "output": proc.stdout,
                    "stderr": proc.stderr
                }
                
        except subprocess.TimeoutExpired:
            return {
                "return_code": -1,
                "output": "TIMEOUT",
                "stderr": "Execution timed out"
            }
        except Exception as e:
            return {
                "return_code": -1,
                "output": str(e),
                "stderr": str(e)
            }
    
    def _compare_output(self, actual_output: str, expected_file: str) -> Dict:
        """
        比较实际输出与期望输出
        
        Args:
            actual_output: 实际输出内容
            expected_file: 期望输出文件路径
            
        Returns:
            比较结果
        """
        try:
            with open(expected_file, 'r', encoding='utf-8') as f:
                expected = f.read()
            
            # 规范化输出（删除多余空白）
            actual_output = ' '.join(actual_output.strip().split())
            expected = ' '.join(expected.strip().split())
            
            if actual_output == expected:
                return {
                    "match": True,
                    "expected": expected,
                    "diff": ""
                }
            else:
                # 生成差异
                diff = difflib.unified_diff(
                    expected.splitlines(keepends=True),
                    actual_output.splitlines(keepends=True),
                    fromfile='Expected',
                    tofile='Actual'
                )
                return {
                    "match": False,
                    "expected": expected,
                    "diff": ''.join(diff)
                }
        except Exception as e:
            return {
                "match": False,
                "expected": f"Error reading expected output: {str(e)}",
                "diff": str(e)
            }
    
    def evaluate_all(self) -> Dict:
        """
        评测所有测试用例
        
        Returns:
            评测结果汇总
        """
        test_cases = self.find_test_cases()
        self.stats["total"] = len(test_cases)
        
        # If no test cases found, return early
        if not test_cases:
            print("No test cases found. Exiting...")
            return self.stats
        
        # 创建总结报告文件
        summary_file = os.path.join(self.current_log_dir, "summary.txt")
        
        with open(summary_file, 'w', encoding='utf-8') as summary:
            summary.write(f"编译器测试报告 - {self.timestamp}\n")
            summary.write("=" * 50 + "\n\n")
            
            for i, test_case in enumerate(test_cases):
                print(f"[{i+1}/{len(test_cases)}] Testing {test_case['name']}...")
                
                result = self.run_test_case(test_case)
                
                if result["success"]:
                    self.stats["passed"] += 1
                    status = "PASSED"
                else:
                    self.stats["failed"] += 1
                    status = "FAILED"
                
                self.stats["details"][test_case["name"]] = {
                    "status": status,
                    "result": result
                }
                
                # 记录到摘要文件
                summary.write(f"Test {i+1}: {test_case['name']} - {status}\n")
                if not result["success"]:
                    if result["stage"] == "compile":
                        summary.write(f"  编译错误: {result['error']}\n")
                    else:
                        summary.write(f"  执行结果与预期不符\n")
                        summary.write(f"  日志目录: {result['log_dir']}\n")
                summary.write("\n")
                
                # 显示测试结果
                print(f"  Result: {status}")
                if not result["success"]:
                    if result["stage"] == "compile":
                        print(f"  编译错误: {result['error']}")
                    else:
                        print(f"  执行结果与预期不符")
                        print(f"  日志目录: {result['log_dir']}")
            
            # 写入统计信息
            summary.write("=" * 50 + "\n")
            summary.write(f"测试结果: {self.stats['passed']}/{self.stats['total']} passed\n")
            # Avoid division by zero
            pass_rate = (self.stats['passed'] / self.stats['total'] * 100) if self.stats['total'] > 0 else 0
            summary.write(f"通过率: {pass_rate:.2f}%\n")
            summary.write("=" * 50 + "\n")
        
        return self.stats

def main():
    """主函数"""
    parser = argparse.ArgumentParser(description='Compiler Evaluator')
    parser.add_argument('--jar', default='TestIR.jar', help='Path to TestIR.jar')
    parser.add_argument('--test-dirs', nargs='+', default=['tests', 'ARM-性能'], help='Directories containing test cases')
    parser.add_argument('--logs-dir', default='logs', help='Directory for log files')
    
    args = parser.parse_args()
    
    evaluator = CompilerEvaluator(
        jar_path=args.jar,
        test_dirs=args.test_dirs,
        logs_dir=args.logs_dir
    )
    
    results = evaluator.evaluate_all()
    
    print("=" * 50)
    print(f"测试结果: {results['passed']}/{results['total']} passed")
    
    # Avoid division by zero
    if results['total'] > 0:
        pass_rate = results['passed'] / results['total'] * 100
        print(f"通过率: {pass_rate:.2f}%")
    else:
        print("通过率: 0.00% (没有找到测试用例)")
    
    print(f"详细日志保存在: {evaluator.current_log_dir}")
    print("=" * 50)
    
    return 0 if results['failed'] == 0 else 1

    
if __name__ == "__main__":
    sys.exit(main()) 
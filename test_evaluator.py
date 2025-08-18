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
import json
# Colorized console output using ANSI escape codes via colorama (with graceful fallback)
try:
    from colorama import Fore, Style, init as colorama_init
    colorama_init(autoreset=True)
except ImportError:  # colorama may be missing in some environments
    class _DummyColor(str):
        def __getattr__(self, item):
            return ''
    Fore = Style = _DummyColor('')

class CompilerEvaluator:
    """编译器评测工具类"""
    
    def __init__(self,
                 jar_ours: str = "TestIR.jar",
                 jar_zy: Optional[str] = "TestIR1.jar",
                 test_dirs: List[str] = ["tests", "ARM-性能", "functional", "h_functional"],
                 logs_dir: str = "logs",
                 single_file: Optional[str] = None):
        """
        初始化编译器评测器
        
        Args:
            jar_ours: 我们的编译器 JAR 路径
            jar_zy: 参考实现 JAR 路径 (可选)
            test_dirs: 测试用例目录列表
            logs_dir: 日志输出目录
            single_file: 单文件模式，指定一个 SysY 源文件
        """
        # 我们的编译器 JAR 与参考实现 JAR
        self.jar_ours = jar_ours
        self.jar_zy = jar_zy
        
        # 是否使用源码直接编译运行
        self.use_source_code = jar_ours == "src"
        self.src_path = "src/Compiler.java"
        self.java_compiled = False  # 标记Java源码是否已编译

        # 单文件模式（优先生效）
        self.single_file = single_file

        # 评测目录与测试集
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

        # 运行时库 (sylib.c) 相关设置
        self.runtime_c = "sylib.c"  # C 源文件位置（默认在仓库根目录）
        self.runtime_bc = "sylib.ll"  # 运行时库（LLVM IR 文件）

        # 确保运行时库 bitcode 就绪
        self._prepare_runtime()
        
    def find_test_cases(self) -> List[Dict]:
        """
        查找所有测试用例
        
        Returns:
            测试用例信息列表
        """
        test_cases = []
        
        # 单文件模式
        if self.single_file:
            dir_path = os.path.dirname(self.single_file)
            test_case = self._create_test_case_info(self.single_file, dir_path)
            return [test_case] if test_case else []

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

                        # Fallback: look for the common source filename used in the dataset
                        if not sy_files:
                            input_txt = os.path.join(subdir_path, "input.txt")
                            if os.path.exists(input_txt):
                                sy_files = [input_txt]

                        # Additional fallback: any .c source file
                        if not sy_files:
                            sy_files = glob.glob(os.path.join(subdir_path, "*.c"))
                        
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
        
        # 查找输入文件 (程序运行时的 stdin 数据)
        in_file = self._find_file(dir_path, base_name, [".in", "_input.txt", "input.txt"])

        # 通用文件名 "in"（无扩展名）
        if not in_file:
            generic_in = os.path.join(dir_path, "in")
            if os.path.exists(generic_in):
                in_file = generic_in
            
        # 查找期望输出文件
        out_file = self._find_file(dir_path, base_name, [".out", "_output.txt", "output.txt"])

        # 通用文件匹配（例如 result.json）
        if not out_file:
            json_result = os.path.join(dir_path, "result.json")
            if os.path.exists(json_result):
                out_file = json_result

        # 其他可能的输出文件
        if not out_file:
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
        # 创建(或稍后删除)用于存放中间/日志文件的目录
        test_log_dir = os.path.join(self.current_log_dir, test_case["name"])
        os.makedirs(test_log_dir, exist_ok=True)
        
        # -----------------------------
        # 我们的编译器 (ans_ours)
        # -----------------------------
        ll_file_ours = os.path.join(test_log_dir, "ours.ll")
        generate_ours = self._generate_llvm_ir(self.jar_ours, test_case["source_file"], ll_file_ours)

        if not generate_ours["success"]:
            # 检查是否是编译超时
            is_compile_timeout = generate_ours.get("timeout", False)
            self._handle_compile_failure(test_case, test_log_dir, generate_ours["error"], label="ours")
            return {
                "name": test_case["name"],
                "success": False,
                "stage": "compile",
                "error": generate_ours["error"],
                "log_dir": test_log_dir,
                "timeout": is_compile_timeout  # 传递超时标记
            }

        run_ours = self._run_llvm_file(ll_file_ours, test_case["input_file"])
        # 如果 _run_llvm_file 返回 TIMEOUT 则标记为超时
        is_timeout = (run_ours["output"].strip() == "TIMEOUT")

        # 如果程序没有标准输出，则使用返回码作为输出（functional 测试集中往往以返回值作为期望输出）
        program_output = run_ours["output"]

        # 将 Windows 无符号 32 位退出码转换为有符号整数
        signed_return_code = self._to_signed32(run_ours["return_code"])

        # 对于非 JSON 预期 (functional / ARM-性能) 的用例，若无标准输出则将返回值作为输出
        if (
            not is_timeout
            and program_output.strip() == ""
            and not test_case["expected_output"].endswith(".json")
        ):
            # 将返回码作为输出，并补换行以匹配 .out 文件格式
            program_output = f"{signed_return_code}\n"

        # 保存 ans_ours
        ans_ours_path = os.path.join(test_log_dir, "ans_ours.txt")
        with open(ans_ours_path, "w", encoding="utf-8") as f:
            # 若超时则直接写 TLE 便于后续判定
            f.write("TLE" if is_timeout else program_output)

        # 保存 stderr（若有）
        if run_ours.get("stderr") and not run_ours["stderr"].isspace():
            with open(os.path.join(test_log_dir, "err_ours.txt"), "w", encoding="utf-8") as ef:
                ef.write(run_ours["stderr"])

        # -----------------------------
        # 参考编译器 (ans_zy) – 可选
        # -----------------------------
        if self.jar_zy:
            ll_file_zy = os.path.join(test_log_dir, "zy.ll")
            generate_zy = self._generate_llvm_ir(self.jar_zy, test_case["source_file"], ll_file_zy)

            if generate_zy["success"]:
                run_zy = self._run_llvm_file(ll_file_zy, test_case["input_file"])
                ans_zy_path = os.path.join(test_log_dir, "ans_zy.txt")
                with open(ans_zy_path, "w", encoding="utf-8") as f:
                    f.write(run_zy["output"])
                if run_zy.get("stderr") and not run_zy["stderr"].isspace():
                    with open(os.path.join(test_log_dir, "err_zy.txt"), "w", encoding="utf-8") as ef:
                        ef.write(run_zy["stderr"])
            else:
                # 记录参考实现编译错误但不影响通过/失败判定
                with open(os.path.join(test_log_dir, "compile_error_zy.txt"), "w", encoding="utf-8") as ef:
                    ef.write(generate_zy["error"])

        # 运行时错误定义：
        # - 我们在 _run_llvm_file 中用 -1 标识异常/超时（超时已单独判定）
        # - stderr 非空（LLVM/程序崩溃信息）
        # 负返回码本身不再作为 RE 判定条件（例如 -8 应按返回值比较判定）
        is_runtime_error = (
            signed_return_code == -1 and not is_timeout
        ) or (
            run_ours.get("stderr") and not run_ours["stderr"].isspace()
        )

        if is_runtime_error or is_timeout:
            with open(ans_ours_path, "w", encoding="utf-8") as f:
                f.write("TLE" if is_timeout else "RE")

        # 比较输出 (我们编译器 vs 期望)
        # 若超时则无需再比较输出
        compare_result = {"match": False, "expected": "", "diff": "TIMEOUT"} if is_timeout else \
            self._compare_output(program_output, test_case["expected_output"], signed_return_code)

        if not compare_result["match"] or is_runtime_error or is_timeout:
            # 复制源等文件方便调试
            shutil.copy2(test_case["source_file"], os.path.join(test_log_dir, os.path.basename(test_case["source_file"])))
            if test_case["input_file"]:
                shutil.copy2(test_case["input_file"], os.path.join(test_log_dir, os.path.basename(test_case["input_file"])))
            shutil.copy2(test_case["expected_output"], os.path.join(test_log_dir, os.path.basename(test_case["expected_output"])))

            # 写差异 / RE 标记
            if is_runtime_error:
                compare_result["match"] = False
                compare_result["diff"] = "Runtime Error"
            elif is_timeout:
                compare_result["match"] = False
                compare_result["diff"] = "Time Limit Exceeded"
            else:
                diff_file = os.path.join(test_log_dir, f"{test_case['name']}_diff.txt")
                with open(diff_file, "w", encoding="utf-8") as df:
                    df.write(compare_result["diff"])

        # 全通过则精简日志
        if compare_result["match"]:
            shutil.rmtree(test_log_dir, ignore_errors=True)

        return {
            "name": test_case["name"],
            "success": compare_result["match"],
            "stage": "run",  # 此处固定为 run
            "return_code": signed_return_code,
            "output": program_output,
            "expected": compare_result["expected"],
            "diff": compare_result["diff"] if not compare_result["match"] else None,
            "log_dir": test_log_dir if not compare_result["match"] else "",
            "timeout": is_timeout,
            "runtime_error": is_runtime_error
        }

    # 辅助：处理编译失败
    def _handle_compile_failure(self, test_case: Dict, test_log_dir: str, error_msg: str, label: str):
        """保存编译失败信息到日志"""
        shutil.copy2(test_case["source_file"], os.path.join(test_log_dir, os.path.basename(test_case["source_file"])))
        if test_case["input_file"]:
            shutil.copy2(test_case["input_file"], os.path.join(test_log_dir, os.path.basename(test_case["input_file"])))
        
        # 判断错误类型，如果是TLE则写入不同的错误信息
        is_timeout = error_msg == "TLE"
        error_file_name = "timeout_error" if is_timeout else "compile_error"
        with open(os.path.join(test_log_dir, f"{error_file_name}_{label}.txt"), "w", encoding="utf-8") as ef:
            ef.write("编译超时(TLE)" if is_timeout else error_msg)
            
        # 在对应 ans 文件中写入适当的错误标记
        ans_file = os.path.join(test_log_dir, f"ans_{label}.txt")
        with open(ans_file, "w", encoding="utf-8") as af:
            af.write("TLE" if is_timeout else "RE")
    
    def _compile_java_source(self) -> Dict:
        """
        预编译Java源代码
        
        Returns:
            编译结果信息
        """
        if not self.use_source_code or self.java_compiled:
            return {"success": True}
            
        try:
            print("预编译Java源码...")
            
            # 确保输出目录存在
            os.makedirs("Frontend", exist_ok=True)
            os.makedirs("IR", exist_ok=True)
            os.makedirs("IR/Pass", exist_ok=True)
            os.makedirs("IR/Pass/Utils", exist_ok=True)
            os.makedirs("IR/Type", exist_ok=True)
            os.makedirs("IR/Value", exist_ok=True)
            os.makedirs("IR/Value/Instructions", exist_ok=True)
            os.makedirs("IR/Visitor", exist_ok=True)
            
            # 在Windows上，需要找到所有Java文件并逐一编译
            java_files = []
            for root, _, files in os.walk("src"):
                for file in files:
                    if file.endswith(".java"):
                        java_files.append(os.path.join(root, file))
            
            # 编译所有Java文件
            compile_cmd = ["javac", "-d", ".", "-cp", "."] + java_files
            
            compile_process = subprocess.run(
                compile_cmd,
                capture_output=True,
                text=True,
                timeout=60  # 增加超时时间，因为需要编译更多文件
            )
            
            if compile_process.returncode != 0:
                print(f"Java编译失败: {compile_process.stderr}")
                return {
                    "success": False,
                    "error": f"Java compilation failed with code {compile_process.returncode}\n{compile_process.stderr}"
                }
                
            self.java_compiled = True
            print("Java源码编译成功。")
            return {"success": True}
            
        except subprocess.TimeoutExpired:
            return {
                "success": False,
                "error": "Timeout when compiling Java source"
            }
        except Exception as e:
            return {
                "success": False,
                "error": str(e)
            }

    def _generate_llvm_ir(self, jar_path: str, source_file: str, output_file: str) -> Dict:
        """
        使用TestIR.jar或直接编译运行Java源码生成LLVM IR
        
        Args:
            jar_path: TestIR.jar 路径或 "src" 表示使用源码
            source_file: 源文件路径
            output_file: 输出文件路径
            
        Returns:
            生成结果信息
        """
        process = None
        try:
            # 新评测兼容：Compiler 会固定输出到项目根目录的 IR_output.ll
            # 为避免误用旧文件，调用前先清理同名残留
            fixed_ir_name = "IR_output.ll"
            fixed_ir_path = os.path.join(os.getcwd(), fixed_ir_name)
            try:
                if os.path.exists(fixed_ir_path):
                    os.remove(fixed_ir_path)
            except Exception:
                pass

            if jar_path == "src" or self.use_source_code:
                # 直接运行编译后的Java类
                run_cmd = ["java", "-cp", ".", "Compiler", source_file]
                process = subprocess.run(
                    run_cmd,
                    capture_output=True,
                    text=True,
                    timeout=30  # 减少到30秒，与执行时间限制一致
                )
            else:
                # 使用JAR包（保持原接口：允许传入输出文件路径）
                cmd = ["java", "-jar", jar_path, source_file, output_file]
                process = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=30  # 减少到30秒，与执行时间限制一致
                )
            
            if process.returncode != 0:
                return {
                    "success": False,
                    "error": f"Compiler exited with code {process.returncode}\n{process.stderr}"
                }
            
            # 若未生成期望路径的 IR 文件，尝试从固定文件名回填
            if not os.path.exists(output_file):
                if os.path.exists(fixed_ir_path):
                    try:
                        shutil.copy2(fixed_ir_path, output_file)
                    except Exception as copy_err:
                        return {
                            "success": False,
                            "error": f"Failed to place IR output at {output_file}: {copy_err}"
                        }
                else:
                    return {
                        "success": False,
                        "error": f"LLVM IR file was not generated\n{process.stderr}"
                    }
                
            return {
                "success": True,
                "output": process.stdout
            }
            
        except subprocess.TimeoutExpired:
            # 确保进程被终止
            try:
                if 'process' in locals() and process:
                    # subprocess.run已经自动处理了超时进程的终止
                    pass
            except:
                pass  # 忽略终止进程时可能的错误
                
            print(f"编译超时: {source_file}")
            return {
                "success": False,
                "error": "TLE",  # 标记为TLE而不是普通编译错误
                "timeout": True  # 添加超时标记
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
        # Link runtime library with the generated IR using llvm-link to avoid
        # symbol resolution issues when executing with lli.
        linked_file = ll_file + ".linked.bc"
        if os.path.exists(self.runtime_bc):
            try:
                link_cmd = ["llvm-link", "-o", linked_file, ll_file, self.runtime_bc]
                subprocess.run(link_cmd, check=True, capture_output=True, text=True)
                cmd = ["lli", linked_file]
            except Exception as e:
                print(f"Warning: llvm-link failed ({e}). Falling back to single module execution.")
                cmd = ["lli", ll_file]
        else:
            cmd = ["lli", ll_file]
        
        proc = None
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
                stdout, stderr = proc.communicate(input=input_data, timeout=30)
                
                # 过滤 LLVM 打印的非错误信息（如执行时间统计）
                stderr_filtered = "\n".join(
                    line for line in stderr.splitlines()
                    if line.strip() and not re.match(r"^TOTAL:\s*\d+H-\d+M-\d+S-\d+us$", line.strip())
                )
                
                if stderr_filtered:
                    print(f"Warning: stderr for {ll_file}: {stderr_filtered}", file=sys.stderr)
                
                return {
                    "return_code": proc.returncode,
                    "output": stdout,
                    "stderr": stderr_filtered
                }
            else:
                # 执行命令但不提供输入
                proc = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=30
                )
                
                # 过滤 LLVM 打印的非错误信息（如执行时间统计）
                stderr_filtered = "\n".join(
                    line for line in proc.stderr.splitlines()
                    if line.strip() and not re.match(r"^TOTAL:\s*\d+H-\d+M-\d+S-\d+us$", line.strip())
                )

                if stderr_filtered:
                    print(f"Warning: stderr for {ll_file}: {stderr_filtered}", file=sys.stderr)

                return {
                    "return_code": proc.returncode,
                    "output": proc.stdout,
                    "stderr": stderr_filtered
                }
                
        except subprocess.TimeoutExpired:
            # 确保进程被终止
            try:
                if proc and isinstance(proc, subprocess.Popen):
                    proc.kill()
            except:
                pass  # 忽略终止进程时可能的错误
                
            print(f"执行超时: {ll_file}")
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
    
    def _compare_output(self, actual_output: str, expected_file: str, return_code: int = 0) -> Dict:
        """
        比较实际输出与期望输出
        
        Args:
            actual_output: 实际输出内容
            expected_file: 期望输出文件路径
            
        Returns:
            比较结果
        """
        try:
            # 根据文件类型读取期望输出
            if expected_file.endswith('.json'):
                with open(expected_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)

                # JSON 格式的测试样例（tests 目录）同时可能包含返回值
                expected_output = data.get('out', '')
                expected_return = data.get('return', None)
            else:
                # 对于非 JSON 的 .out 文件，若最后一行是整数，则视为返回值
                with open(expected_file, 'r', encoding='utf-8') as f:
                    raw_expected = f.read()

                # 预处理：检测最后一行是否为返回值（整数）
                lines = raw_expected.rstrip("\n").split("\n")
                expected_return = None

                # 当存在至少两行且最后一行是整数（允许负号）时，认定为返回值
                if len(lines) >= 2 and re.fullmatch(r"-?\d+", lines[-1].strip()):
                    expected_return = lines[-1].strip()
                    # 去掉最后一行，只保留标准输出部分
                    expected_output = "\n".join(lines[:-1]) + "\n" if lines[:-1] else ""
                else:
                    expected_output = raw_expected
                    
            # 规范化16进制浮点数表示
            actual_output_norm = self._normalize_hex_floats(actual_output.strip())
            expected_output_norm = self._normalize_hex_floats(expected_output.strip())
            
            # 删除多余空白
            actual_output_norm = ' '.join(actual_output_norm.split())
            expected_output_norm = ' '.join(expected_output_norm.split())

            # 比较标准输出
            output_match = (actual_output_norm == expected_output_norm)

            # 比较返回值（若 JSON 中提供）
            if expected_return is not None:
                return_match = (str(return_code) == str(expected_return))
            else:
                return_match = True

            if output_match and return_match:
                return {
                    "match": True,
                    "expected": expected_output,
                    "diff": ""
                }
            else:
                # 生成差异（仅就输出差异生成；返回值差异单独说明）
                diff_lines = []
                if not output_match:
                    diff_lines.extend(difflib.unified_diff(
                        expected_output_norm.splitlines(keepends=True),
                        actual_output_norm.splitlines(keepends=True),
                        fromfile='Expected stdout',
                        tofile='Actual stdout'
                    ))
                if not return_match:
                    diff_lines.append(f"Return value mismatch: expected {expected_return}, got {return_code}\n")

                return {
                    "match": False,
                    "expected": expected_output if expected_output is not None else "",
                    "diff": ''.join(diff_lines)
                }
        except Exception as e:
            return {
                "match": False,
                "expected": f"Error reading expected output: {str(e)}",
                "diff": str(e)
            }
    
    def _normalize_hex_floats(self, text: str) -> str:
        """
        规范化16进制浮点数表示，去掉尾随的零
        
        Args:
            text: 输入文本
            
        Returns:
            规范化后的文本
        """
        def replace_hex_float(match):
            # 提取匹配的16进制浮点数
            hex_float = match.group(0)
            
            # 将 0x1.e691e60000000p+1 格式分解为基本部分
            parts = hex_float.split('p')
            if len(parts) != 2:
                return hex_float
                
            # 处理基数部分
            base_part = parts[0]
            if '.' in base_part:
                int_part, frac_part = base_part.split('.')
                # 去掉尾随的零
                frac_part = frac_part.rstrip('0')
                # 如果小数部分为空，则去掉小数点
                if frac_part == '':
                    base_part = int_part
                else:
                    base_part = f"{int_part}.{frac_part}"
            
            # 重新组合
            return f"{base_part}p{parts[1]}"
            
        # 使用正则表达式匹配16进制浮点数格式，如0x1.e691e60000000p+1或0x1p+0
        pattern = r'0x[0-9a-f]+(\.[0-9a-f]*)?p[+-]?\d+'
        return re.sub(pattern, replace_hex_float, text, flags=re.IGNORECASE)
    
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
            
        # 如果使用源码直接编译，先预编译Java源码
        if self.use_source_code:
            compile_result = self._compile_java_source()
            if not compile_result["success"]:
                print(f"预编译Java源码失败: {compile_result['error']}")
                return self.stats
        
        # 创建总结报告文件
        summary_file = os.path.join(self.current_log_dir, "summary.txt")
        
        with open(summary_file, 'w', encoding='utf-8') as summary:
            summary.write(f"编译器测试报告 - {self.timestamp}\n")
            summary.write("=" * 50 + "\n\n")
            
            for i, test_case in enumerate(test_cases):
                print(f"[{i+1}/{len(test_cases)}] Testing {test_case['name']}...")
                
                result = self.run_test_case(test_case)
                
                # 根据结果确定状态
                if result["success"]:
                    self.stats["passed"] += 1
                    status = "AC"  # Accepted
                else:
                    self.stats["failed"] += 1
                    # 进一步区分错误类型
                    if result.get("timeout"):
                        status = "TLE"  # Time Limit Exceeded (无论是编译阶段还是执行阶段)
                    elif result.get("stage") == "compile" and result.get("error") != "TLE":
                        status = "CE"  # Compile Error (非超时)
                    elif result.get("runtime_error"):
                        status = "RE"  # Runtime Error
                    else:
                        status = "WA"  # Wrong Answer
                 
                self.stats["details"][test_case["name"]] = {
                    "status": status,
                    "result": result
                }
                
                # 记录到摘要文件
                summary.write(f"Test {i+1}: {test_case['name']} - {status}\n")
                if not result["success"]:
                    if status == "TLE":
                        phase = "编译" if result.get("stage") == "compile" else "执行"
                        summary.write(f"  超时错误 (TLE): {phase}阶段超过30秒\n")
                        summary.write(f"  日志目录: {result['log_dir']}\n")
                    elif status == "CE":
                        summary.write(f"  编译错误: {result['error']}\n")
                    elif status == "RE":
                        summary.write("  运行时错误 (RE)\n")
                        summary.write(f"  日志目录: {result['log_dir']}\n")
                    else:  # WA
                        summary.write("  输出不匹配 (WA)\n")
                        summary.write("  差异如下:\n")
                        summary.write(result.get("diff", "<no diff>") + "\n")
                        summary.write(f"  日志目录: {result['log_dir']}\n")
                summary.write("\n")
                
                # 显示测试结果
                color_map = {"AC": Fore.GREEN, "WA": Fore.RED, "RE": Fore.YELLOW, "CE": Fore.MAGENTA, "TLE": Fore.CYAN}
                colored_status = f"{color_map.get(status, '')}{status}{Style.RESET_ALL}"
                print(f"  Result: {colored_status}")
                if not result["success"]:
                    if status == "TLE":
                        phase = "编译" if result.get("stage") == "compile" else "执行"
                        print(f"  {Fore.CYAN}超时错误 (TLE){Style.RESET_ALL}: {phase}阶段超过30秒")
                        print(f"  日志目录: {result['log_dir']}")
                    elif status == "CE":
                        print(f"  {Fore.MAGENTA}编译错误 (CE){Style.RESET_ALL}: {result['error']}")
                    elif status == "RE":
                        # 打印返回码用于调试符号化转换
                        print(f"  {Fore.YELLOW}运行时错误 (RE){Style.RESET_ALL}, return_code={result.get('return_code')}")
                        print(f"  日志目录: {result['log_dir']}")
                    else:  # WA
                        print(f"  {Fore.RED}输出不匹配 (WA){Style.RESET_ALL}")
                        # 直接打印差异，便于快速定位问题
                        print(result.get("diff", "<no diff>"))
                        print(f"  日志目录: {result['log_dir']}")
            
            # 写入统计信息
            summary.write("=" * 50 + "\n")
            summary.write(f"测试结果: {self.stats['passed']}/{self.stats['total']} passed\n")
            # Avoid division by zero
            pass_rate = (self.stats['passed'] / self.stats['total'] * 300) if self.stats['total'] > 0 else 0
            summary.write(f"通过率: {pass_rate:.2f}%\n")
            summary.write("=" * 50 + "\n")
        
        return self.stats

    def _prepare_runtime(self):
        """
        准备运行时库 (sylib.c)
        
        - 编译 sylib.c 生成 sylib.bc
        - 将 sylib.bc 复制到当前日志目录
        """
        print(f"Preparing runtime library: {self.runtime_c} -> {self.runtime_bc}")
        
        # 如果 runtime bitcode/IR 已存在，直接复用
        if os.path.exists(self.runtime_bc):
            print(f"Runtime library already present: {self.runtime_bc}")
            shutil.copy2(self.runtime_bc, self.current_log_dir)
            return

        # 如不存在，则尝试通过 clang 编译 sylib.c 生成
        if not os.path.exists(self.runtime_c):
            print(f"Error: Runtime library file {self.runtime_c} not found and {self.runtime_bc} missing.")
            sys.exit(1)

        try:
            clang_candidates = [
                "clang",
                "clang-17",
                "clang-16",
                "clang-15",
                "clang-14",
                "clang-13",
                "clang-12",
            ]

            for c in clang_candidates:
                if shutil.which(c):
                    clang_bin = c
                    break
            else:
                print("Error: clang not found. Please install LLVM/Clang and ensure it is in PATH.")
                sys.exit(1)

            cmd = [clang_bin, "-O2", "-emit-llvm", "-c", self.runtime_c, "-o", self.runtime_bc]
            process = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=30
            )

            if process.returncode != 0:
                print(f"Error compiling runtime library {self.runtime_c}:")
                print(process.stderr)
                sys.exit(1)

            print(f"Runtime library compiled successfully: {self.runtime_bc}")

            shutil.copy2(self.runtime_bc, self.current_log_dir)
            print(f"Runtime library bitcode copied to: {self.current_log_dir}")

        except subprocess.TimeoutExpired:
            print(f"Timeout when compiling runtime library {self.runtime_c}")
            sys.exit(1)
        except Exception as e:
            print(f"Error compiling runtime library {self.runtime_c}: {str(e)}")
            sys.exit(1)

    def _to_signed32(self, code: int) -> int:
        """
        将无符号 32 位整数转换为有符号 32 位整数。
        
        Args:
            code: 无符号 32 位整数
            
        Returns:
            有符号 32 位整数
        """
        # 对于 32 位无符号整数，范围是 0 到 0xFFFFFFFF
        # 将其转换为有符号整数，范围是 -2^31 到 2^31 - 1
        # 具体来说，如果 code > 2^31 - 1，则表示负数
        if code > 0x7FFFFFFF:
            # 将无符号32位数转为有符号32位数：减去 2^32
            return code - 0x100000000
        return code


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description='Compiler Evaluator')
    parser.add_argument('--jar', dest='jar', default='src', help='Path to your compiler TestIR.jar (ours) or "src" to use source code directly')
    parser.add_argument('--jar-zy', dest='jar_zy', help='Path to reference TestIR1.jar (zy)', default=None)
    parser.add_argument('--test-dirs', nargs='+', default=['tests', 'ARM-性能', 'functional', 'h_functional'], help='Directories containing test cases (ignored when --file is set)')
    parser.add_argument('--file', dest='single_file', help='Run evaluation for a single SysY source file')
    parser.add_argument('--logs-dir', default='logs', help='Directory for log files')
    
    args = parser.parse_args()
    
    evaluator = CompilerEvaluator(
        jar_ours=args.jar,
        jar_zy=args.jar_zy,
        test_dirs=args.test_dirs,
        logs_dir=args.logs_dir,
        single_file=args.single_file
    )
    
    results = evaluator.evaluate_all()
    
    print("=" * 50)
    print(f"测试结果: {results['passed']}/{results['total']} passed")
    
    # Avoid division by zero
    if results['total'] > 0:
        pass_rate = results['passed'] / results['total'] * 300
        print(f"通过率: {pass_rate:.2f}%")
    else:
        print("通过率: 0.00% (没有找到测试用例)")
    
    print(f"详细日志保存在: {evaluator.current_log_dir}")
    print("=" * 50)
    
    return 0 if results['failed'] == 0 else 1

    
if __name__ == "__main__":
    sys.exit(main()) 
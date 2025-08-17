#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清理.class文件的脚本
递归删除指定目录及其子目录中的所有.class文件
"""

import os
import sys
import argparse
from pathlib import Path


def find_class_files(directory):
    """
    递归查找指定目录中的所有.class文件
    
    Args:
        directory (str): 要搜索的根目录
        
    Returns:
        list: 找到的.class文件路径列表
    """
    class_files = []
    directory_path = Path(directory)
    
    if not directory_path.exists():
        print(f"错误：目录 '{directory}' 不存在")
        return class_files
    
    # 使用glob递归查找所有.class文件
    for class_file in directory_path.rglob("*.class"):
        class_files.append(class_file)
    
    return class_files


def delete_class_files(class_files, dry_run=False):
    """
    删除.class文件
    
    Args:
        class_files (list): 要删除的.class文件路径列表
        dry_run (bool): 如果为True，只显示要删除的文件而不实际删除
        
    Returns:
        tuple: (成功删除的文件数, 失败删除的文件数)
    """
    success_count = 0
    failure_count = 0
    
    if not class_files:
        print("没有找到.class文件")
        return success_count, failure_count
    
    print(f"找到 {len(class_files)} 个.class文件:")
    
    for class_file in class_files:
        if dry_run:
            print(f"  [预览] {class_file}")
        else:
            try:
                class_file.unlink()  # 删除文件
                print(f"  [已删除] {class_file}")
                success_count += 1
            except Exception as e:
                print(f"  [失败] {class_file} - 错误: {e}")
                failure_count += 1
    
    return success_count, failure_count


def main():
    """主函数"""
    parser = argparse.ArgumentParser(
        description="清理.class文件的脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例用法:
  python clean_class_files.py                    # 清理当前目录
  python clean_class_files.py ./src             # 清理src目录
  python clean_class_files.py --dry-run         # 预览模式，不实际删除
  python clean_class_files.py -r .              # 明确指定递归清理当前目录
        """
    )
    
    parser.add_argument(
        "directory",
        nargs="?",
        default=".",
        help="要清理的目录路径 (默认: 当前目录)"
    )
    
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="预览模式，显示要删除的文件但不实际删除"
    )
    
    parser.add_argument(
        "-r", "--recursive",
        action="store_true",
        default=True,
        help="递归搜索子目录 (默认启用)"
    )
    
    parser.add_argument(
        "-y", "--yes",
        action="store_true",
        help="自动确认删除，不询问用户"
    )
    
    args = parser.parse_args()
    
    # 显示将要操作的目录
    abs_directory = os.path.abspath(args.directory)
    print(f"清理目录: {abs_directory}")
    print(f"递归搜索: {'是' if args.recursive else '否'}")
    print(f"预览模式: {'是' if args.dry_run else '否'}")
    print("-" * 50)
    
    # 查找.class文件
    class_files = find_class_files(args.directory)
    
    if not class_files:
        print("✓ 没有找到.class文件，目录已经干净")
        return
    
    # 如果是预览模式，直接显示文件列表
    if args.dry_run:
        delete_class_files(class_files, dry_run=True)
        print(f"\n预览完成。要实际删除这些文件，请重新运行不带 --dry-run 参数的命令")
        return
    
    # 询问用户确认
    if not args.yes:
        print(f"\n找到 {len(class_files)} 个.class文件")
        response = input("确认删除这些文件吗? (y/N): ").strip().lower()
        if response not in ['y', 'yes', 'Y', 'YES']:
            print("操作已取消")
            return
    
    # 执行删除
    success_count, failure_count = delete_class_files(class_files, dry_run=False)
    
    print("-" * 50)
    print(f"删除完成!")
    print(f"成功删除: {success_count} 个文件")
    if failure_count > 0:
        print(f"删除失败: {failure_count} 个文件")
    else:
        print("✓ 所有.class文件都已成功删除")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n操作被用户中断")
        sys.exit(1)
    except Exception as e:
        print(f"\n发生错误: {e}")
        sys.exit(1) 
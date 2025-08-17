#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
编译器评测记录管理系统
功能：记录每次评测信息，支持自定义对比功能
"""

import re
import json
import sqlite3
import os
from datetime import datetime
from typing import List, Dict, Any, Optional, Tuple
from dataclasses import dataclass, asdict
from bs4 import BeautifulSoup
import argparse


@dataclass
class TestCase:
    """单个测试案例"""
    no: int
    name: str
    result: str
    score: Optional[float] = None
    time: Optional[float] = None
    log: Optional[str] = None


@dataclass
class EvaluationSummary:
    """评测总结"""
    total_score: int
    total_time: float
    final_status: str
    board_id: int


@dataclass
class EvaluationRecord:
    """完整的评测记录"""
    id: Optional[int] = None
    timestamp: Optional[str] = None
    summary: Optional[EvaluationSummary] = None
    functional_tests: List[TestCase] = None
    h_functional_tests: List[TestCase] = None
    performance_tests: List[TestCase] = None
    notes: Optional[str] = None

    def __post_init__(self):
        if self.timestamp is None:
            self.timestamp = datetime.now().isoformat()
        if self.functional_tests is None:
            self.functional_tests = []
        if self.h_functional_tests is None:
            self.h_functional_tests = []
        if self.performance_tests is None:
            self.performance_tests = []


class HTMLParser:
    """HTML评测数据解析器"""

    @staticmethod
    def parse_html_file(file_path: str) -> EvaluationRecord:
        """解析HTML文件"""
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        return HTMLParser.parse_html_content(content)

    @staticmethod
    def parse_html_content(content: str) -> EvaluationRecord:
        """解析HTML内容"""
        soup = BeautifulSoup(content, 'html.parser')
        
        record = EvaluationRecord()
        
        # 解析summary
        record.summary = HTMLParser._parse_summary(soup)
        
        # 解析各类测试
        record.functional_tests = HTMLParser._parse_test_section(soup, "Functional")
        record.h_functional_tests = HTMLParser._parse_test_section(soup, "H_Functional")
        record.performance_tests = HTMLParser._parse_test_section(soup, "Performance")
        
        return record

    @staticmethod
    def _parse_summary(soup: BeautifulSoup) -> Optional[EvaluationSummary]:
        """解析Summary部分"""
        try:
            summary_table = soup.find('div', class_='summary')
            if not summary_table:
                return None
            
            rows = summary_table.find('table').find_all('tr')
            if len(rows) < 2:
                return None
                
            data_row = rows[1].find_all('td')
            if len(data_row) < 4:
                return None
            
            score = int(data_row[0].get_text().strip())
            time_text = data_row[1].get_text().strip()
            time = float(re.findall(r'[\d.]+', time_text)[0])
            status = data_row[2].get_text().strip()
            board = int(data_row[3].get_text().strip())
            
            return EvaluationSummary(score, time, status, board)
        except Exception as e:
            print(f"解析Summary时出错: {e}")
            return None

    @staticmethod
    def _parse_test_section(soup: BeautifulSoup, section_name: str) -> List[TestCase]:
        """解析测试部分"""
        tests = []
        try:
            # 查找对应的测试部分
            section_header = soup.find('h3', string=section_name)
            if not section_header:
                return tests
            
            # 找到该部分的表格
            table = section_header.find_next('table')
            if not table:
                return tests
            
            rows = table.find('tbody').find_all('tr')[1:]  # 跳过表头
            
            for row in rows:
                cells = row.find_all('td')
                if len(cells) < 3:
                    continue
                    
                test_case = TestCase(
                    no=int(cells[0].get_text().strip()),
                    name=cells[1].get_text().strip(),
                    result=cells[2].get_text().strip()
                )
                
                # 根据section类型解析额外信息
                if section_name == "Performance":
                    if len(cells) > 3:
                        try:
                            test_case.time = float(cells[3].get_text().strip())
                        except:
                            pass
                    # 确保Performance测试不会有score数据
                    test_case.score = None
                else:
                    # 对于Functional和H_Functional测试
                    if len(cells) > 3:
                        try:
                            test_case.score = float(cells[3].get_text().strip())
                        except:
                            pass
                    # 确保非Performance测试不会有time数据
                    test_case.time = None
                
                if len(cells) > 4:
                    test_case.log = cells[4].get_text().strip()
                
                tests.append(test_case)
                
        except Exception as e:
            print(f"解析{section_name}时出错: {e}")
        
        return tests


class DatabaseManager:
    """数据库管理器"""

    def __init__(self, db_path: str = "evaluations.db"):
        self.db_path = db_path
        self.init_database()

    def init_database(self):
        """初始化数据库"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        # 创建评测记录表
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS evaluations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                total_score INTEGER,
                total_time REAL,
                final_status TEXT,
                board_id INTEGER,
                notes TEXT
            )
        ''')
        
        # 创建测试案例表
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS test_cases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                evaluation_id INTEGER,
                section TEXT,
                no INTEGER,
                name TEXT,
                result TEXT,
                score REAL,
                time REAL,
                log TEXT,
                FOREIGN KEY (evaluation_id) REFERENCES evaluations (id)
            )
        ''')
        
        conn.commit()
        conn.close()

    def save_record(self, record: EvaluationRecord) -> int:
        """保存评测记录"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        # 保存主记录
        cursor.execute('''
            INSERT INTO evaluations (timestamp, total_score, total_time, final_status, board_id, notes)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (
            record.timestamp,
            record.summary.total_score if record.summary else None,
            record.summary.total_time if record.summary else None,
            record.summary.final_status if record.summary else None,
            record.summary.board_id if record.summary else None,
            record.notes
        ))
        
        evaluation_id = cursor.lastrowid
        
        # 保存测试案例
        sections = [
            ('Functional', record.functional_tests),
            ('H_Functional', record.h_functional_tests),
            ('Performance', record.performance_tests)
        ]
        
        for section_name, test_cases in sections:
            for test_case in test_cases:
                cursor.execute('''
                    INSERT INTO test_cases (evaluation_id, section, no, name, result, score, time, log)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ''', (
                    evaluation_id, section_name, test_case.no, test_case.name,
                    test_case.result, test_case.score, test_case.time, test_case.log
                ))
        
        conn.commit()
        conn.close()
        
        return evaluation_id

    def get_all_records(self) -> List[EvaluationRecord]:
        """获取所有评测记录"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        cursor.execute('SELECT * FROM evaluations ORDER BY timestamp DESC')
        evaluation_rows = cursor.fetchall()
        
        records = []
        for row in evaluation_rows:
            record = EvaluationRecord(
                id=row[0],
                timestamp=row[1],
                summary=EvaluationSummary(row[2], row[3], row[4], row[5]),
                notes=row[6]
            )
            
            # 获取测试案例
            cursor.execute('''
                SELECT section, no, name, result, score, time, log
                FROM test_cases
                WHERE evaluation_id = ?
                ORDER BY section, no
            ''', (row[0],))
            
            test_rows = cursor.fetchall()
            
            for test_row in test_rows:
                section = test_row[0]
                
                # 根据section类型，确保正确的数据类型
                if section == 'Performance':
                    test_case = TestCase(
                        no=test_row[1],
                        name=test_row[2],
                        result=test_row[3],
                        score=None,  # Performance测试不应该有score
                        time=test_row[5],
                        log=test_row[6]
                    )
                    record.performance_tests.append(test_case)
                else:
                    # Functional和H_Functional测试
                    test_case = TestCase(
                        no=test_row[1],
                        name=test_row[2],
                        result=test_row[3],
                        score=test_row[4],
                        time=None,  # 非Performance测试不应该有time
                        log=test_row[6]
                    )
                    
                    if section == 'Functional':
                        record.functional_tests.append(test_case)
                    elif section == 'H_Functional':
                        record.h_functional_tests.append(test_case)
            
            records.append(record)
        
        conn.close()
        return records

    def get_record_by_id(self, record_id: int) -> Optional[EvaluationRecord]:
        """根据ID获取记录"""
        records = self.get_all_records()
        for record in records:
            if record.id == record_id:
                return record
        return None

    def delete_record(self, record_id: int) -> bool:
        """删除指定ID的评测记录"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        try:
            # 先删除相关的测试案例
            cursor.execute('DELETE FROM test_cases WHERE evaluation_id = ?', (record_id,))
            
            # 再删除主记录
            cursor.execute('DELETE FROM evaluations WHERE id = ?', (record_id,))
            
            # 检查是否真的删除了记录
            if cursor.rowcount > 0:
                conn.commit()
                return True
            else:
                return False
        except Exception as e:
            conn.rollback()
            raise e
        finally:
            conn.close()


class ComparisonEngine:
    """对比引擎"""

    @staticmethod
    def compare_records(record1: EvaluationRecord, record2: EvaluationRecord) -> Dict[str, Any]:
        """对比两个评测记录"""
        comparison = {
            'summary': ComparisonEngine._compare_summary(record1.summary, record2.summary),
            'functional': ComparisonEngine._compare_test_sections(
                record1.functional_tests, record2.functional_tests, 'Functional'
            ),
            'h_functional': ComparisonEngine._compare_test_sections(
                record1.h_functional_tests, record2.h_functional_tests, 'H_Functional'
            ),
            'performance': ComparisonEngine._compare_test_sections(
                record1.performance_tests, record2.performance_tests, 'Performance'
            )
        }
        return comparison

    @staticmethod
    def _compare_summary(summary1: EvaluationSummary, summary2: EvaluationSummary) -> Dict[str, Any]:
        """对比评测摘要"""
        if not summary1 or not summary2:
            return {"error": "缺少摘要数据"}
        
        return {
            'score_diff': summary1.total_score - summary2.total_score,
            'time_diff': summary1.total_time - summary2.total_time,
            'status_changed': summary1.final_status != summary2.final_status,
            'record1': asdict(summary1),
            'record2': asdict(summary2)
        }

    @staticmethod
    def _compare_test_sections(tests1: List[TestCase], tests2: List[TestCase], section_name: str) -> Dict[str, Any]:
        """对比测试部分"""
        # 创建测试名称到测试案例的映射
        tests1_map = {test.name: test for test in tests1}
        tests2_map = {test.name: test for test in tests2}
        
        # 找出变化
        improved = []  # 从非AC变成AC
        regressed = []  # 从AC变成非AC
        new_tests = []  # 新增的测试
        removed_tests = []  # 移除的测试
        unchanged = []  # 无变化的测试
        time_changes = []  # 运行时间变化（主要针对性能测试）
        wa_unchanged = []  # WA等非AC结果但结果无变化的测试（仅用于性能测试）
        
        all_test_names = set(tests1_map.keys()) | set(tests2_map.keys())
        
        for test_name in all_test_names:
            test1 = tests1_map.get(test_name)
            test2 = tests2_map.get(test_name)
            
            if test1 and test2:
                # 结果变化检查
                if test1.result != test2.result:
                    if test2.result != 'AC' and test1.result == 'AC':
                        improved.append({'name': test_name, 'from': test2.result, 'to': test1.result})
                    elif test2.result == 'AC' and test1.result != 'AC':
                        regressed.append({'name': test_name, 'from': test2.result, 'to': test1.result})
                else:
                    # 结果相同，需要根据测试类型进行分类
                    if section_name == 'Performance':
                        # 性能测试：需要区分AC和非AC结果
                        if test1.result == 'AC' and test2.result == 'AC':
                            # 两次都是AC，检查时间变化
                            has_significant_time_change = False
                            if (test1.time is not None and test2.time is not None and test2.time > 0):
                                time_diff = test1.time - test2.time  # 本次 - 上次
                                time_change_percent = (time_diff / test2.time) * 100
                                
                                # 只记录有显著变化的时间（变化超过5%或0.1秒）
                                if abs(time_diff) > 0.1 or abs(time_change_percent) > 5:
                                    has_significant_time_change = True
                                    time_changes.append({
                                        'name': test_name,
                                        'time1': test1.time,  # 本次
                                        'time2': test2.time,  # 上次
                                        'time_diff': time_diff,
                                        'time_change_percent': time_change_percent,
                                        'result1': test1.result,
                                        'result2': test2.result
                                    })
                            
                            # AC且时间无显著变化 -> 无变化
                            if not has_significant_time_change:
                                unchanged.append({'name': test_name, 'result': test1.result, 'time': test1.time})
                        else:
                            # 两次都是非AC结果（WA、TLE等）-> WA等无变化
                            wa_unchanged.append({'name': test_name, 'result': test1.result, 'time': test1.time})
                    else:
                        # 非性能测试：结果相同就是无变化
                        unchanged.append({'name': test_name, 'result': test1.result, 'time': test1.time})
                        
            elif test1 and not test2:
                removed_tests.append({'name': test_name, 'result': test1.result, 'time': test1.time})
            elif not test1 and test2:
                new_tests.append({'name': test_name, 'result': test2.result, 'time': test2.time})
        
        return {
            'section': section_name,
            'improved': improved,
            'regressed': regressed,
            'new_tests': new_tests,
            'removed_tests': removed_tests,
            'unchanged': unchanged,
            'unchanged_count': len(unchanged),
            'wa_unchanged': wa_unchanged,
            'wa_unchanged_count': len(wa_unchanged),
            'time_changes': time_changes,
            'total_changes': len(improved) + len(regressed) + len(new_tests) + len(removed_tests)
        }


class CLI:
    """命令行界面"""

    def __init__(self):
        self.db = DatabaseManager()
        self.parser = HTMLParser()

    def run(self):
        """运行命令行界面"""
        parser = argparse.ArgumentParser(description='编译器评测记录管理系统')
        subparsers = parser.add_subparsers(dest='command', help='可用命令')

        # 添加记录命令
        add_parser = subparsers.add_parser('add', help='添加新的评测记录')
        add_parser.add_argument('file', help='HTML评测文件路径')
        add_parser.add_argument('--notes', help='备注信息')

        # 列出记录命令
        list_parser = subparsers.add_parser('list', help='列出所有评测记录')

        # 对比记录命令
        compare_parser = subparsers.add_parser('compare', help='对比两个评测记录')
        compare_parser.add_argument('id1', type=int, help='第一个记录ID')
        compare_parser.add_argument('id2', type=int, help='第二个记录ID')

        # 显示记录详情命令
        show_parser = subparsers.add_parser('show', help='显示记录详情')
        show_parser.add_argument('id', type=int, help='记录ID')

        args = parser.parse_args()

        if args.command == 'add':
            self.add_record(args.file, args.notes)
        elif args.command == 'list':
            self.list_records()
        elif args.command == 'compare':
            self.compare_records(args.id1, args.id2)
        elif args.command == 'show':
            self.show_record(args.id)
        else:
            parser.print_help()

    def add_record(self, file_path: str, notes: str = None):
        """添加新记录"""
        try:
            record = self.parser.parse_html_file(file_path)
            record.notes = notes
            record_id = self.db.save_record(record)
            print(f"✅ 成功添加评测记录，ID: {record_id}")
            print(f"📊 总分: {record.summary.total_score if record.summary else 'N/A'}")
            print(f"⏱️  总时间: {record.summary.total_time if record.summary else 'N/A'}s")
            print(f"📈 状态: {record.summary.final_status if record.summary else 'N/A'}")
        except Exception as e:
            print(f"❌ 添加记录失败: {e}")

    def list_records(self):
        """列出所有记录"""
        records = self.db.get_all_records()
        if not records:
            print("📋 暂无评测记录")
            return

        print("📋 评测记录列表:")
        print("-" * 80)
        print(f"{'ID':<4} {'时间':<20} {'总分':<6} {'时间(s)':<10} {'状态':<6} {'备注':<20}")
        print("-" * 80)
        
        for record in records:
            timestamp = record.timestamp[:19] if record.timestamp else 'N/A'
            score = record.summary.total_score if record.summary else 'N/A'
            time = f"{record.summary.total_time:.1f}" if record.summary else 'N/A'
            status = record.summary.final_status if record.summary else 'N/A'
            notes = record.notes[:18] + '...' if record.notes and len(record.notes) > 20 else (record.notes or '')
            
            print(f"{record.id:<4} {timestamp:<20} {score:<6} {time:<10} {status:<6} {notes:<20}")

    def show_record(self, record_id: int):
        """显示记录详情"""
        record = self.db.get_record_by_id(record_id)
        if not record:
            print(f"❌ 未找到ID为{record_id}的记录")
            return

        print(f"📄 评测记录详情 (ID: {record.id})")
        print("=" * 60)
        
        if record.summary:
            print(f"📊 总分: {record.summary.total_score}")
            print(f"⏱️  总时间: {record.summary.total_time}s")
            print(f"📈 最终状态: {record.summary.final_status}")
            print(f"🖥️  开发板: {record.summary.board_id}")
        
        print(f"🕐 时间: {record.timestamp}")
        if record.notes:
            print(f"📝 备注: {record.notes}")
        
        # 显示各部分统计
        sections = [
            ('Functional', record.functional_tests),
            ('H_Functional', record.h_functional_tests),
            ('Performance', record.performance_tests)
        ]
        
        for section_name, tests in sections:
            if tests:
                ac_count = sum(1 for test in tests if test.result == 'AC')
                total_count = len(tests)
                print(f"🧪 {section_name}: {ac_count}/{total_count} AC")

    def compare_records(self, id1: int, id2: int):
        """对比两个记录"""
        record1 = self.db.get_record_by_id(id1)
        record2 = self.db.get_record_by_id(id2)
        
        if not record1:
            print(f"❌ 未找到ID为{id1}的记录")
            return
        if not record2:
            print(f"❌ 未找到ID为{id2}的记录")
            return

        comparison = ComparisonEngine.compare_records(record1, record2)
        
        print(f"🔍 对比记录 {id1} vs {id2}")
        print("=" * 60)
        
        # 显示摘要对比
        summary_comp = comparison['summary']
        if 'error' not in summary_comp:
            print("📊 摘要对比:")
            print(f"   分数变化: {summary_comp['score_diff']:+d}")
            print(f"   时间变化: {summary_comp['time_diff']:+.2f}s")
            print(f"   状态变化: {'是' if summary_comp['status_changed'] else '否'}")
        
        # 显示各部分对比
        sections = ['functional', 'h_functional', 'performance']
        section_names = ['Functional', 'H_Functional', 'Performance']
        
        for i, section in enumerate(sections):
            comp = comparison[section]
            print(f"\n🧪 {section_names[i]}测试对比:")
            
            if comp['improved']:
                print(f"   ✅ 改进: {len(comp['improved'])}个")
                for item in comp['improved'][:5]:  # 只显示前5个
                    print(f"      {item['name']}: {item['from']} → {item['to']}")
                if len(comp['improved']) > 5:
                    print(f"      ... 还有{len(comp['improved']) - 5}个")
            
            if comp['regressed']:
                print(f"   ❌ 退化: {len(comp['regressed'])}个")
                for item in comp['regressed'][:5]:
                    print(f"      {item['name']}: {item['from']} → {item['to']}")
                if len(comp['regressed']) > 5:
                    print(f"      ... 还有{len(comp['regressed']) - 5}个")
            
            # 性能测试的运行时间对比
            if section == 'performance' and comp.get('time_changes'):
                print(f"   ⏱️ AC测试运行时间变化: {len(comp['time_changes'])}个")
                # 按时间变化幅度排序，显示最显著的变化
                time_changes_sorted = sorted(comp['time_changes'], 
                                           key=lambda x: abs(x['time_change_percent']), reverse=True)
                for item in time_changes_sorted[:10]:  # 显示前10个最显著的变化
                    time_change_str = f"{item['time_diff']:+.3f}s ({item['time_change_percent']:+.1f}%)"
                    if item['time_diff'] < 0:
                        emoji = "🚀"  # 时间减少，性能提升
                    else:
                        emoji = "🐌"  # 时间增加，性能下降
                    print(f"      {emoji} {item['name']}: {item['time2']:.3f}s → {item['time1']:.3f}s ({time_change_str})")
                if len(comp['time_changes']) > 10:
                    print(f"      ... 还有{len(comp['time_changes']) - 10}个时间变化")
            
            if comp['new_tests']:
                print(f"   🆕 新增: {len(comp['new_tests'])}个")
                for item in comp['new_tests'][:3]:
                    time_str = f" ({item['time']:.3f}s)" if item.get('time') is not None else ""
                    print(f"      {item['name']}: {item['result']}{time_str}")
                if len(comp['new_tests']) > 3:
                    print(f"      ... 还有{len(comp['new_tests']) - 3}个新增测试")
            
            if comp['removed_tests']:
                print(f"   🗑️  移除: {len(comp['removed_tests'])}个")
                for item in comp['removed_tests'][:3]:
                    time_str = f" ({item['time']:.3f}s)" if item.get('time') is not None else ""
                    print(f"      {item['name']}: {item['result']}{time_str}")
                if len(comp['removed_tests']) > 3:
                    print(f"      ... 还有{len(comp['removed_tests']) - 3}个移除测试")
            
            print(f"   ⏸️  无变化: {comp['unchanged_count']}个")


def main():
    """主函数"""
    cli = CLI()
    cli.run()


if __name__ == "__main__":
    main() 
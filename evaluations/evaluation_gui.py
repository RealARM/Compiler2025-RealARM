#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
编译器评测记录管理系统 - GUI版本
功能：记录每次评测信息，支持自定义对比功能
"""

import tkinter as tk
from tkinter import ttk, filedialog, messagebox, scrolledtext
import os
from datetime import datetime
from evaluation_manager import DatabaseManager, HTMLParser, ComparisonEngine, EvaluationRecord


class EvaluationGUI:
    """评测记录管理GUI"""
    
    def __init__(self, root):
        self.root = root
        self.root.title("🚀 编译器评测记录管理系统")
        self.root.geometry("1200x800")
        self.root.minsize(1000, 600)
        
        # 设置现代化主题
        self.setup_theme()
        
        # 初始化数据管理器
        self.db = DatabaseManager()
        self.parser = HTMLParser()
        
        self.create_widgets()
        self.refresh_records()
    
    def setup_theme(self):
        """设置现代化主题"""
        style = ttk.Style()
        
        # 设置主题
        style.theme_use('clam')
        
        # 自定义样式
        style.configure('Title.TLabel', font=('Segoe UI', 18, 'bold'), foreground='#2c3e50')
        style.configure('Heading.TLabel', font=('Segoe UI', 12, 'bold'), foreground='#34495e')
        style.configure('Modern.TButton', font=('Segoe UI', 10))
        style.configure('Success.TButton', font=('Segoe UI', 10))
        style.configure('Warning.TButton', font=('Segoe UI', 10))
        style.configure('Danger.TButton', font=('Segoe UI', 10))
        
        # 配置按钮颜色
        style.map('Success.TButton',
                 background=[('active', '#27ae60'), ('!active', '#2ecc71')])
        style.map('Warning.TButton',
                 background=[('active', '#e67e22'), ('!active', '#f39c12')])
        style.map('Danger.TButton',
                 background=[('active', '#c0392b'), ('!active', '#e74c3c')])
        
        # 设置根窗口背景
        self.root.configure(bg='#ecf0f1')
    
    def create_widgets(self):
        """创建界面组件"""
        # 创建主框架
        main_frame = ttk.Frame(self.root, padding="15")
        main_frame.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        
        # 配置网格权重
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)
        main_frame.columnconfigure(1, weight=2)
        main_frame.columnconfigure(2, weight=1)
        main_frame.rowconfigure(2, weight=1)
        
        # 创建标题区域
        self.create_header(main_frame)
        
        # 创建工具栏
        self.create_toolbar(main_frame)
        
        # 创建主要内容区域
        self.create_main_content(main_frame)
        
        # 创建状态栏
        self.create_statusbar(main_frame)
    
    def create_header(self, parent):
        """创建标题区域"""
        header_frame = ttk.Frame(parent)
        header_frame.grid(row=0, column=0, columnspan=3, sticky=(tk.W, tk.E), pady=(0, 20))
        
        # 主标题
        title_label = ttk.Label(header_frame, text="🚀 编译器评测记录管理系统", 
                               style='Title.TLabel')
        title_label.pack()
        
        # 副标题
        subtitle_label = ttk.Label(header_frame, 
                                  text="智能记录 • 精准对比 • 高效管理", 
                                  font=('Segoe UI', 11), foreground='#7f8c8d')
        subtitle_label.pack(pady=(5, 0))
    
    def create_toolbar(self, parent):
        """创建工具栏"""
        toolbar_frame = ttk.LabelFrame(parent, text="🛠️ 操作工具栏", padding="10")
        toolbar_frame.grid(row=1, column=0, columnspan=3, sticky=(tk.W, tk.E), pady=(0, 15))
        
        # 主要操作按钮
        self.add_button = ttk.Button(toolbar_frame, text="📁 添加评测记录", 
                                    command=self.add_record, style='Success.TButton')
        self.add_button.pack(side=tk.LEFT, padx=(0, 10))
        
        self.refresh_button = ttk.Button(toolbar_frame, text="🔄 刷新列表", 
                                        command=self.refresh_records, style='Modern.TButton')
        self.refresh_button.pack(side=tk.LEFT, padx=(0, 10))
        
        # 分隔线
        separator = ttk.Separator(toolbar_frame, orient='vertical')
        separator.pack(side=tk.LEFT, fill='y', padx=10)
        
        # 记录操作按钮
        self.compare_button = ttk.Button(toolbar_frame, text="🔍 对比记录", 
                                        command=self.compare_records, state='disabled',
                                        style='Warning.TButton')
        self.compare_button.pack(side=tk.LEFT, padx=(0, 10))
        
        self.delete_button = ttk.Button(toolbar_frame, text="🗑️ 删除记录", 
                                       command=self.delete_record, state='disabled',
                                       style='Danger.TButton')
        self.delete_button.pack(side=tk.LEFT)
        
        # 统计信息标签
        self.stats_label = ttk.Label(toolbar_frame, text="", 
                                    font=('Segoe UI', 10), foreground='#7f8c8d')
        self.stats_label.pack(side=tk.RIGHT, padx=(10, 0))
    
    def create_main_content(self, parent):
        """创建主要内容区域"""
        # 记录列表区域
        self.create_record_list(parent)
        
        # 详情显示区域
        self.create_detail_panel(parent)
    
    def create_record_list(self, parent):
        """创建记录列表"""
        list_frame = ttk.LabelFrame(parent, text="📋 评测记录列表", padding="10")
        list_frame.grid(row=2, column=0, columnspan=2, sticky=(tk.W, tk.E, tk.N, tk.S), 
                       padx=(0, 15))
        list_frame.columnconfigure(0, weight=1)
        list_frame.rowconfigure(0, weight=1)
        
        # 创建Treeview容器
        tree_container = ttk.Frame(list_frame)
        tree_container.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        tree_container.columnconfigure(0, weight=1)
        tree_container.rowconfigure(0, weight=1)
        
        # 创建Treeview
        columns = ('ID', 'Time', 'Score', 'Duration', 'Status', 'Notes')
        self.tree = ttk.Treeview(tree_container, columns=columns, show='headings', height=18)
        
        # 定义列标题和宽度
        column_configs = {
            'ID': ('ID', 60),
            'Time': ('⏰ 时间', 140),
            'Score': ('📊 总分', 80),
            'Duration': ('⏱️ 时长', 100),
            'Status': ('📈 状态', 80),
            'Notes': ('📝 备注', 250)
        }
        
        for col_id, (title, width) in column_configs.items():
            self.tree.heading(col_id, text=title)
            self.tree.column(col_id, width=width)
        
        # 添加滚动条
        v_scrollbar = ttk.Scrollbar(tree_container, orient=tk.VERTICAL, command=self.tree.yview)
        h_scrollbar = ttk.Scrollbar(tree_container, orient=tk.HORIZONTAL, command=self.tree.xview)
        self.tree.configure(yscrollcommand=v_scrollbar.set, xscrollcommand=h_scrollbar.set)
        
        # 布局
        self.tree.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        v_scrollbar.grid(row=0, column=1, sticky=(tk.N, tk.S))
        h_scrollbar.grid(row=1, column=0, sticky=(tk.W, tk.E))
        
        # 绑定事件
        self.tree.bind('<<TreeviewSelect>>', self.on_select)
        self.tree.bind('<Double-1>', self.show_details)
        
        # 设置行样式
        self.tree.tag_configure('odd', background='#f8f9fa')
        self.tree.tag_configure('even', background='#ffffff')
    
    def create_detail_panel(self, parent):
        """创建详情面板"""
        detail_frame = ttk.LabelFrame(parent, text="📊 记录详情", padding="10")
        detail_frame.grid(row=2, column=2, sticky=(tk.W, tk.E, tk.N, tk.S))
        detail_frame.columnconfigure(0, weight=1)
        detail_frame.rowconfigure(0, weight=1)
        
        # 详情文本区域
        self.detail_text = scrolledtext.ScrolledText(detail_frame, width=45, height=25, 
                                                    wrap=tk.WORD, font=('Consolas', 10))
        self.detail_text.grid(row=0, column=0, sticky=(tk.W, tk.E, tk.N, tk.S))
        
        # 设置文本样式
        self.detail_text.tag_configure('title', font=('Segoe UI', 12, 'bold'), foreground='#2c3e50')
        self.detail_text.tag_configure('section', font=('Segoe UI', 11, 'bold'), foreground='#34495e')
        self.detail_text.tag_configure('success', foreground='#27ae60')
        self.detail_text.tag_configure('warning', foreground='#f39c12')
        self.detail_text.tag_configure('error', foreground='#e74c3c')
    
    def create_statusbar(self, parent):
        """创建状态栏"""
        status_frame = ttk.Frame(parent)
        status_frame.grid(row=3, column=0, columnspan=3, sticky=(tk.W, tk.E), pady=(15, 0))
        
        self.status_label = ttk.Label(status_frame, text="就绪", 
                                     font=('Segoe UI', 9), foreground='#7f8c8d')
        self.status_label.pack(side=tk.LEFT)
        
        # 版本信息
        version_label = ttk.Label(status_frame, text="v2.0.0", 
                                 font=('Segoe UI', 9), foreground='#bdc3c7')
        version_label.pack(side=tk.RIGHT)
    
    def add_record(self):
        """添加新记录"""
        file_path = filedialog.askopenfilename(
            title="选择评测文件",
            filetypes=[("文本文件", "*.txt"), ("HTML文件", "*.html *.htm"), ("所有文件", "*.*")]
        )
        
        if not file_path:
            return
        
        self.status_label.config(text="正在解析文件...")
        self.root.update()
        
        # 弹出备注输入对话框
        notes = self.get_notes_dialog()
        
        if notes is None: # 检查是否取消
            self.status_label.config(text="添加记录已取消")
            return
        
        try:
            record = self.parser.parse_html_file(file_path)
            record.notes = notes
            record_id = self.db.save_record(record)
            
            self.status_label.config(text=f"成功添加记录 ID: {record_id}")
            messagebox.showinfo("✅ 成功", f"成功添加评测记录！\n\nID: {record_id}\n总分: {record.summary.total_score if record.summary else 'N/A'}")
            self.refresh_records()
            
        except Exception as e:
            self.status_label.config(text="添加记录失败")
            messagebox.showerror("❌ 错误", f"添加记录失败:\n\n{str(e)}")
    
    def get_notes_dialog(self):
        """获取备注信息的对话框 - 重新设计"""
        dialog = tk.Toplevel(self.root)
        dialog.title("📝 添加备注")
        dialog.geometry("500x400")
        dialog.transient(self.root)
        dialog.grab_set()
        dialog.resizable(True, True)
        
        # 居中显示
        self.center_window(dialog, 500, 400)
        
        # 配置dialog的grid权重
        dialog.grid_rowconfigure(0, weight=1)
        dialog.grid_columnconfigure(0, weight=1)
        
        # 主框架，使用grid布局
        main_frame = ttk.Frame(dialog, padding="20")
        main_frame.grid(row=0, column=0, sticky="nsew")
        main_frame.grid_rowconfigure(2, weight=1)  # 文本框行可扩展
        main_frame.grid_columnconfigure(0, weight=1)
        
        # 标题区域
        title_frame = ttk.Frame(main_frame)
        title_frame.grid(row=0, column=0, sticky="ew", pady=(0, 15))
        title_frame.grid_columnconfigure(0, weight=1)
        
        title_label = ttk.Label(title_frame, text="📝 为这次评测添加备注", 
                               font=('Segoe UI', 14, 'bold'))
        title_label.grid(row=0, column=0)
        
        # 说明区域
        desc_frame = ttk.Frame(main_frame)
        desc_frame.grid(row=1, column=0, sticky="ew", pady=(0, 15))
        desc_frame.grid_columnconfigure(0, weight=1)
        
        desc_text = "备注可以帮助您更好地记录和管理这次评测：\n• 记录当前代码版本或修改内容\n• 标注测试目的和期望结果\n• 备注已知问题或特殊情况"
        desc_label = ttk.Label(desc_frame, text=desc_text, 
                              font=('Segoe UI', 9), foreground='#7f8c8d')
        desc_label.grid(row=0, column=0, sticky="w")
        
        # 文本输入区域
        text_frame = ttk.LabelFrame(main_frame, text="📝 备注内容", padding="10")
        text_frame.grid(row=2, column=0, sticky="nsew", pady=(0, 15))
        text_frame.grid_rowconfigure(0, weight=1)
        text_frame.grid_columnconfigure(0, weight=1)
        
        # 创建文本框和滚动条
        notes_text = tk.Text(text_frame, width=50, height=8, 
                            font=('Segoe UI', 10), wrap=tk.WORD,
                            relief=tk.SOLID, borderwidth=1)
        scrollbar = ttk.Scrollbar(text_frame, orient=tk.VERTICAL, command=notes_text.yview)
        notes_text.configure(yscrollcommand=scrollbar.set)
        
        notes_text.grid(row=0, column=0, sticky="nsew", padx=(0, 5))
        scrollbar.grid(row=0, column=1, sticky="ns")
        
        # 添加placeholder文本
        placeholder_text = "例如：修复了编译器的类型检查bug，预期性能测试会有提升..."
        notes_text.insert(1.0, placeholder_text)
        notes_text.configure(foreground='#999999')
        
        def on_focus_in(event):
            if notes_text.get(1.0, tk.END).strip() == placeholder_text:
                notes_text.delete(1.0, tk.END)
                notes_text.configure(foreground='#000000')
        
        def on_focus_out(event):
            if not notes_text.get(1.0, tk.END).strip():
                notes_text.insert(1.0, placeholder_text)
                notes_text.configure(foreground='#999999')
        
        notes_text.bind('<FocusIn>', on_focus_in)
        notes_text.bind('<FocusOut>', on_focus_out)
        notes_text.focus()
        
        # 字符计数标签
        count_label = ttk.Label(main_frame, text="字符数: 0", 
                               font=('Segoe UI', 8), foreground='#7f8c8d')
        count_label.grid(row=3, column=0, sticky="e", pady=(0, 10))
        
        def update_count(event=None):
            content = notes_text.get(1.0, tk.END).strip()
            if content == placeholder_text:
                count_label.config(text="字符数: 0")
            else:
                count_label.config(text=f"字符数: {len(content)}")
        
        notes_text.bind('<KeyRelease>', update_count)
        
        # 按钮区域
        button_frame = ttk.Frame(main_frame)
        button_frame.grid(row=4, column=0, sticky="ew")
        button_frame.grid_columnconfigure(0, weight=1)
        
        # 按钮子框架，右对齐
        btn_sub_frame = ttk.Frame(button_frame)
        btn_sub_frame.grid(row=0, column=0, sticky="e")
        
        result = {'notes': '', 'cancelled': False}
        
        def save_notes():
            content = notes_text.get(1.0, tk.END).strip()
            if content == placeholder_text:
                result['notes'] = ''
            else:
                result['notes'] = content
            dialog.destroy()
        
        def cancel():
            result['cancelled'] = True
            dialog.destroy()
        
        # 快捷键绑定
        def on_ctrl_enter(event):
            save_notes()
        
        def on_escape(event):
            cancel()
        
        dialog.bind('<Control-Return>', on_ctrl_enter)
        dialog.bind('<Escape>', on_escape)
        
        # 按钮
        cancel_btn = ttk.Button(btn_sub_frame, text="❌ 取消", command=cancel)
        cancel_btn.grid(row=0, column=0, padx=(0, 10), pady=5, ipadx=15, ipady=3)
        
        confirm_btn = ttk.Button(btn_sub_frame, text="✅ 确定 (Ctrl+Enter)", command=save_notes)
        confirm_btn.grid(row=0, column=1, pady=5, ipadx=15, ipady=3)
        
        # 提示标签
        tip_label = ttk.Label(button_frame, text="提示：按 Ctrl+Enter 快速确定，按 Esc 取消", 
                             font=('Segoe UI', 8), foreground='#95a5a6')
        tip_label.grid(row=1, column=0, pady=(5, 0), sticky="w")
        
        dialog.wait_window()
        
        if result['cancelled']:
            return None
        return result['notes']

    def center_window(self, window, width, height):
        """将窗口居中显示"""
        screen_width = window.winfo_screenwidth()
        screen_height = window.winfo_screenheight()
        x = (screen_width // 2) - (width // 2)
        y = (screen_height // 2) - (height // 2)
        window.geometry(f"{width}x{height}+{x}+{y}")
    
    def refresh_records(self):
        """刷新记录列表"""
        self.status_label.config(text="正在刷新记录列表...")
        self.root.update()
        
        # 清空现有项目
        for item in self.tree.get_children():
            self.tree.delete(item)
        
        # 获取所有记录
        records = self.db.get_all_records()
        
        for i, record in enumerate(records):
            timestamp = record.timestamp[:19].replace('T', ' ') if record.timestamp else 'N/A'
            score = record.summary.total_score if record.summary else 'N/A'
            duration = f"{record.summary.total_time:.1f}s" if record.summary else 'N/A'
            status = record.summary.final_status if record.summary else 'N/A'
            notes = record.notes[:30] + '...' if record.notes and len(record.notes) > 30 else (record.notes or '')
            
            # 设置行标签用于样式
            tag = 'even' if i % 2 == 0 else 'odd'
            
            self.tree.insert('', tk.END, values=(
                record.id, timestamp, score, duration, status, notes
            ), tags=(tag,))
        
        # 更新统计信息
        self.update_stats(len(records))
        self.status_label.config(text="记录列表已刷新")
    
    def update_stats(self, record_count):
        """更新统计信息"""
        self.stats_label.config(text=f"📊 共 {record_count} 条记录")
    
    def on_select(self, event):
        """选择记录时的处理"""
        selection = self.tree.selection()
        if selection:
            # 根据选择数量更新按钮状态
            if len(selection) == 1:
                self.compare_button.config(state='disabled')
                self.delete_button.config(state='normal')
            elif len(selection) == 2:
                self.compare_button.config(state='normal')
                self.delete_button.config(state='disabled')
            else:
                self.compare_button.config(state='disabled')
                self.delete_button.config(state='disabled')
            
            # 显示第一个选中记录的详情
            item = self.tree.item(selection[0])
            record_id = int(item['values'][0])
            self.show_record_details(record_id)
        else:
            self.compare_button.config(state='disabled')
            self.delete_button.config(state='disabled')
    
    def show_record_details(self, record_id: int):
        """显示记录详情"""
        record = self.db.get_record_by_id(record_id)
        if not record:
            return
        
        self.detail_text.delete(1.0, tk.END)
        
        # 标题
        self.detail_text.insert(tk.END, f"📄 评测记录详情\n", 'title')
        self.detail_text.insert(tk.END, f"ID: {record.id}\n", 'title')
        self.detail_text.insert(tk.END, "=" * 40 + "\n\n")
        
        # 基本信息
        if record.summary:
            self.detail_text.insert(tk.END, "📊 基本信息:\n", 'section')
            self.detail_text.insert(tk.END, f"总分: {record.summary.total_score}\n")
            self.detail_text.insert(tk.END, f"总时间: {record.summary.total_time:.2f}s\n")
            
            # 根据状态设置颜色
            status_tag = 'success' if record.summary.final_status == 'AC' else 'warning' if record.summary.final_status == 'PE' else 'error'
            self.detail_text.insert(tk.END, f"状态: {record.summary.final_status}\n", status_tag)
            self.detail_text.insert(tk.END, f"开发板: {record.summary.board_id}\n")
        
        self.detail_text.insert(tk.END, f"时间: {record.timestamp[:19].replace('T', ' ')}\n")
        if record.notes:
            self.detail_text.insert(tk.END, f"备注: {record.notes}\n")
        
        self.detail_text.insert(tk.END, "\n" + "=" * 30 + "\n")
        self.detail_text.insert(tk.END, "📊 测试统计:\n\n", 'section')
        
        # 显示各部分统计
        sections = [
            ('🧪 Functional', record.functional_tests),
            ('🔬 H_Functional', record.h_functional_tests),
            ('⚡ Performance', record.performance_tests)
        ]
        
        for section_name, tests in sections:
            if tests:
                ac_count = sum(1 for test in tests if test.result == 'AC')
                wa_count = sum(1 for test in tests if test.result == 'WA')
                pe_count = sum(1 for test in tests if test.result == 'PE')
                total_count = len(tests)
                
                self.detail_text.insert(tk.END, f"{section_name} ({total_count}):\n")
                self.detail_text.insert(tk.END, f"  ✅ AC: {ac_count}\n", 'success')
                if wa_count > 0:
                    self.detail_text.insert(tk.END, f"  ❌ WA: {wa_count}\n", 'error')
                if pe_count > 0:
                    self.detail_text.insert(tk.END, f"  ⚠️ PE: {pe_count}\n", 'warning')
                
                # 对于Performance测试，显示时间信息
                if section_name.startswith('⚡ Performance') and tests:
                    avg_time = sum(test.time for test in tests if test.time is not None) / len([t for t in tests if t.time is not None])
                    max_time = max(test.time for test in tests if test.time is not None) if any(t.time is not None for t in tests) else 0
                    self.detail_text.insert(tk.END, f"  ⏱️ 平均时间: {avg_time:.3f}s\n")
                    self.detail_text.insert(tk.END, f"  🔥 最大时间: {max_time:.3f}s\n")
                
                self.detail_text.insert(tk.END, "\n")
        
        # 如果有失败的测试，显示详情
        failed_tests = []
        for section_name, tests in sections:
            for test in tests:
                if test.result != 'AC':
                    time_info = f" ({test.time:.3f}s)" if test.time is not None else ""
                    failed_tests.append(f"  {section_name.split()[1]}: {test.name} ({test.result}){time_info}")
        
        if failed_tests:
            self.detail_text.insert(tk.END, "❌ 失败的测试:\n", 'section')
            for failed_test in failed_tests[:15]:  # 显示前15个
                self.detail_text.insert(tk.END, failed_test + "\n", 'error')
            if len(failed_tests) > 15:
                self.detail_text.insert(tk.END, f"  ... 还有 {len(failed_tests) - 15} 个失败测试\n", 'warning')
    
    def show_details(self, event):
        """双击显示详细信息"""
        selection = self.tree.selection()
        if not selection:
            return
        
        item = self.tree.item(selection[0])
        record_id = int(item['values'][0])
        record = self.db.get_record_by_id(record_id)
        
        if not record:
            return
        
        # 创建详情窗口
        detail_window = tk.Toplevel(self.root)
        detail_window.title(f"📊 评测记录详情 - ID: {record_id}")
        detail_window.geometry("900x700")
        detail_window.transient(self.root)
        detail_window.minsize(800, 600)
        
        # 创建笔记本控件
        notebook = ttk.Notebook(detail_window)
        notebook.pack(fill=tk.BOTH, expand=True, padx=15, pady=15)
        
        # 概要标签页
        summary_frame = ttk.Frame(notebook)
        notebook.add(summary_frame, text="📊 概要")
        
        summary_text = scrolledtext.ScrolledText(summary_frame, font=('Consolas', 10))
        summary_text.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        # 显示概要信息
        summary_info = self.get_summary_text(record)
        summary_text.insert(1.0, summary_info)
        summary_text.config(state='disabled')
        
        # 各测试部分标签页
        sections = [
            ('🧪 Functional', record.functional_tests),
            ('🔬 H_Functional', record.h_functional_tests),
            ('⚡ Performance', record.performance_tests)
        ]
        
        for section_name, tests in sections:
            if tests:
                frame = ttk.Frame(notebook)
                notebook.add(frame, text=section_name)
                
                # 创建测试列表
                test_tree = ttk.Treeview(frame, columns=('No', 'Name', 'Result', 'Score/Time'), 
                                        show='headings')
                test_tree.heading('No', text='编号')
                test_tree.heading('Name', text='测试名称')
                test_tree.heading('Result', text='结果')
                test_tree.heading('Score/Time', text='分数/时间')
                
                test_tree.column('No', width=60)
                test_tree.column('Name', width=400)
                test_tree.column('Result', width=80)
                test_tree.column('Score/Time', width=120)
                
                for test in tests:
                    # 对于Performance测试，优先显示时间
                    if section_name.startswith('⚡ Performance') and test.time is not None:
                        score_time = f"{test.time:.3f}s"
                    else:
                        score_time = test.score if test.score is not None else (
                        f"{test.time:.3f}s" if test.time is not None else 'N/A'
                    )
                    
                    # 设置行颜色
                    tag = 'success' if test.result == 'AC' else 'warning' if test.result == 'PE' else 'error'
                    item_id = test_tree.insert('', tk.END, values=(test.no, test.name, test.result, score_time))
                    
                    # 配置标签样式
                    if test.result == 'AC':
                        test_tree.set(item_id, 'Result', '✅ AC')
                    elif test.result == 'WA':
                        test_tree.set(item_id, 'Result', '❌ WA')
                    elif test.result == 'PE':
                        test_tree.set(item_id, 'Result', '⚠️ PE')
                
                # 添加滚动条
                test_scrollbar = ttk.Scrollbar(frame, orient=tk.VERTICAL, command=test_tree.yview)
                test_tree.configure(yscrollcommand=test_scrollbar.set)
                
                test_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(10, 0), pady=10)
                test_scrollbar.pack(side=tk.RIGHT, fill=tk.Y, padx=(0, 10), pady=10)
    
    def get_summary_text(self, record):
        """获取概要文本"""
        text = f"📄 评测记录详情 (ID: {record.id})\n"
        text += "=" * 60 + "\n\n"
        
        if record.summary:
            text += f"📊 总分: {record.summary.total_score}\n"
            text += f"⏱️ 总时间: {record.summary.total_time}s\n"
            text += f"📈 最终状态: {record.summary.final_status}\n"
            text += f"🖥️ 开发板: {record.summary.board_id}\n"
        
        text += f"🕐 记录时间: {record.timestamp[:19].replace('T', ' ')}\n"
        if record.notes:
            text += f"📝 备注: {record.notes}\n"
        
        text += "\n" + "=" * 40 + "\n"
        text += "📊 详细统计:\n\n"
        
        sections = [
            ('🧪 Functional', record.functional_tests),
            ('🔬 H_Functional', record.h_functional_tests),
            ('⚡ Performance', record.performance_tests)
        ]
        
        for section_name, tests in sections:
            if tests:
                text += f"{section_name} 测试 ({len(tests)} 个):\n"
                
                # 统计各种结果
                result_stats = {}
                for test in tests:
                    result_stats[test.result] = result_stats.get(test.result, 0) + 1
                
                for result, count in sorted(result_stats.items()):
                    percentage = (count / len(tests)) * 100
                    emoji = "✅" if result == "AC" else "❌" if result == "WA" else "⚠️"
                    text += f"   {emoji} {result}: {count} ({percentage:.1f}%)\n"
                
                # 对于Performance测试，添加时间统计
                if section_name.startswith('⚡ Performance'):
                    times = [test.time for test in tests if test.time is not None]
                    if times:
                        text += f"   ⏱️ 平均时间: {sum(times)/len(times):.3f}s\n"
                        text += f"   🔥 最大时间: {max(times):.3f}s\n"
                        text += f"   ⚡ 最小时间: {min(times):.3f}s\n"
                
                text += "\n"
        
        return text
    
    def compare_records(self):
        """对比记录"""
        selection = self.tree.selection()
        if len(selection) != 2:
            messagebox.showwarning("⚠️ 警告", "请选择两个记录进行对比")
            return
        
        # 获取选中的记录ID
        id1 = int(self.tree.item(selection[0])['values'][0])
        id2 = int(self.tree.item(selection[1])['values'][0])
        
        record1 = self.db.get_record_by_id(id1)
        record2 = self.db.get_record_by_id(id2)
        
        if not record1 or not record2:
            messagebox.showerror("❌ 错误", "无法获取记录信息")
            return
        
        # 弹出选择对话框，让用户选择哪个是本次，哪个是上次
        choice = self.show_comparison_choice_dialog(record1, record2)
        if choice is None:
            return  # 用户取消了选择
        
        current_record, previous_record = choice
        
        self.status_label.config(text="正在生成对比报告...")
        self.root.update()
        
        # 创建对比窗口
        self.show_comparison_window(current_record, previous_record)
        
        self.status_label.config(text="对比完成")
    
    def show_comparison_choice_dialog(self, record1, record2):
        """显示对比选择对话框，让用户选择哪个是本次，哪个是上次"""
        dialog = tk.Toplevel(self.root)
        dialog.title("🔍 选择对比方式")
        dialog.geometry("600x500")
        dialog.transient(self.root)
        dialog.grab_set()
        dialog.resizable(False, False)
        
        # 居中显示
        self.center_window(dialog, 600, 500)
        
        # 配置dialog的grid权重
        dialog.grid_rowconfigure(0, weight=1)
        dialog.grid_columnconfigure(0, weight=1)
        
        # 主框架
        main_frame = ttk.Frame(dialog, padding="20")
        main_frame.grid(row=0, column=0, sticky="nsew")
        main_frame.grid_rowconfigure(1, weight=1)
        main_frame.grid_columnconfigure(0, weight=1)
        
        # 标题区域
        title_frame = ttk.Frame(main_frame)
        title_frame.grid(row=0, column=0, sticky="ew", pady=(0, 20))
        title_frame.grid_columnconfigure(0, weight=1)
        
        title_label = ttk.Label(title_frame, text="🔍 请选择对比方式", 
                               font=('Segoe UI', 16, 'bold'))
        title_label.grid(row=0, column=0)
        
        desc_label = ttk.Label(title_frame, text="请指定哪个记录作为'本次'（新版本），哪个作为'上次'（旧版本）进行对比", 
                              font=('Segoe UI', 10), foreground='#7f8c8d')
        desc_label.grid(row=1, column=0, pady=(5, 0))
        
        # 选择区域
        choice_frame = ttk.Frame(main_frame)
        choice_frame.grid(row=1, column=0, sticky="nsew", pady=(0, 20))
        choice_frame.grid_columnconfigure(0, weight=1)
        choice_frame.grid_columnconfigure(1, weight=1)
        
        # 格式化记录信息
        def format_record_info(record):
            timestamp = record.timestamp[:19].replace('T', ' ') if record.timestamp else 'N/A'
            score = f"{record.summary.total_score}分" if record.summary else 'N/A'
            duration = f"{record.summary.total_time:.1f}s" if record.summary else 'N/A'
            status = record.summary.final_status if record.summary else 'N/A'
            notes = record.notes[:50] + '...' if record.notes and len(record.notes) > 50 else (record.notes or '无备注')
            
            return f"ID: {record.id}\n时间: {timestamp}\n总分: {score}\n用时: {duration}\n状态: {status}\n备注: {notes}"
        
        # 选项1：record1作为本次
        option1_frame = ttk.LabelFrame(choice_frame, text="选项 1", padding="15")
        option1_frame.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        
        ttk.Label(option1_frame, text="🆕 本次（当前版本）", 
                 font=('Segoe UI', 12, 'bold'), foreground='#27ae60').pack(anchor="w")
        
        # 创建带滚动条的框架
        record1_frame = ttk.Frame(option1_frame)
        record1_frame.pack(fill=tk.BOTH, expand=True, pady=(5, 10))
        
        record1_info = tk.Text(record1_frame, height=8, width=35, font=('Courier', 9),
                              relief=tk.SOLID, borderwidth=1, wrap=tk.WORD, state=tk.DISABLED)
        record1_scrollbar = ttk.Scrollbar(record1_frame, orient=tk.VERTICAL, command=record1_info.yview)
        record1_info.configure(yscrollcommand=record1_scrollbar.set)
        
        record1_info.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        record1_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        record1_info.config(state=tk.NORMAL)
        record1_info.insert(1.0, format_record_info(record1))
        record1_info.config(state=tk.DISABLED)
        
        ttk.Label(option1_frame, text="📊 对比上次（历史版本）", 
                 font=('Segoe UI', 12, 'bold'), foreground='#e74c3c').pack(anchor="w")
        
        # 创建带滚动条的框架
        record2_frame = ttk.Frame(option1_frame)
        record2_frame.pack(fill=tk.BOTH, expand=True, pady=(5, 0))
        
        record2_info = tk.Text(record2_frame, height=8, width=35, font=('Courier', 9),
                              relief=tk.SOLID, borderwidth=1, wrap=tk.WORD, state=tk.DISABLED)
        record2_scrollbar = ttk.Scrollbar(record2_frame, orient=tk.VERTICAL, command=record2_info.yview)
        record2_info.configure(yscrollcommand=record2_scrollbar.set)
        
        record2_info.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        record2_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        record2_info.config(state=tk.NORMAL)
        record2_info.insert(1.0, format_record_info(record2))
        record2_info.config(state=tk.DISABLED)
        
        # 选项2：record2作为本次
        option2_frame = ttk.LabelFrame(choice_frame, text="选项 2", padding="15")
        option2_frame.grid(row=0, column=1, sticky="nsew", padx=(10, 0))
        
        ttk.Label(option2_frame, text="🆕 本次（当前版本）", 
                 font=('Segoe UI', 12, 'bold'), foreground='#27ae60').pack(anchor="w")
        
        # 创建带滚动条的框架
        record2_frame2 = ttk.Frame(option2_frame)
        record2_frame2.pack(fill=tk.BOTH, expand=True, pady=(5, 10))
        
        record2_info2 = tk.Text(record2_frame2, height=8, width=35, font=('Courier', 9),
                               relief=tk.SOLID, borderwidth=1, wrap=tk.WORD, state=tk.DISABLED)
        record2_scrollbar2 = ttk.Scrollbar(record2_frame2, orient=tk.VERTICAL, command=record2_info2.yview)
        record2_info2.configure(yscrollcommand=record2_scrollbar2.set)
        
        record2_info2.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        record2_scrollbar2.pack(side=tk.RIGHT, fill=tk.Y)
        
        record2_info2.config(state=tk.NORMAL)
        record2_info2.insert(1.0, format_record_info(record2))
        record2_info2.config(state=tk.DISABLED)
        
        ttk.Label(option2_frame, text="📊 对比上次（历史版本）", 
                 font=('Segoe UI', 12, 'bold'), foreground='#e74c3c').pack(anchor="w")
        
        # 创建带滚动条的框架
        record1_frame2 = ttk.Frame(option2_frame)
        record1_frame2.pack(fill=tk.BOTH, expand=True, pady=(5, 0))
        
        record1_info2 = tk.Text(record1_frame2, height=8, width=35, font=('Courier', 9),
                               relief=tk.SOLID, borderwidth=1, wrap=tk.WORD, state=tk.DISABLED)
        record1_scrollbar2 = ttk.Scrollbar(record1_frame2, orient=tk.VERTICAL, command=record1_info2.yview)
        record1_info2.configure(yscrollcommand=record1_scrollbar2.set)
        
        record1_info2.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        record1_scrollbar2.pack(side=tk.RIGHT, fill=tk.Y)
        
        record1_info2.config(state=tk.NORMAL)
        record1_info2.insert(1.0, format_record_info(record1))
        record1_info2.config(state=tk.DISABLED)
        
        # 按钮区域
        button_frame = ttk.Frame(main_frame)
        button_frame.grid(row=2, column=0, sticky="ew")
        button_frame.grid_columnconfigure(1, weight=1)
        
        result = {'choice': None}
        
        def choose_option1():
            result['choice'] = (record1, record2)  # record1作为本次，record2作为上次
            dialog.destroy()
        
        def choose_option2():
            result['choice'] = (record2, record1)  # record2作为本次，record1作为上次
            dialog.destroy()
        
        def cancel():
            dialog.destroy()
        
        # 快捷键绑定
        def on_key(event):
            if event.keysym == '1':
                choose_option1()
            elif event.keysym == '2':
                choose_option2()
            elif event.keysym == 'Escape':
                cancel()
        
        dialog.bind('<KeyPress>', on_key)
        dialog.focus_set()
        
        # 按钮
        ttk.Button(button_frame, text="✅ 选择选项1 (按键1)", command=choose_option1,
                  style='Accent.TButton').grid(row=0, column=0, padx=(0, 10), pady=10, ipadx=20, ipady=5)
        
        ttk.Button(button_frame, text="✅ 选择选项2 (按键2)", command=choose_option2,
                  style='Accent.TButton').grid(row=0, column=1, padx=(10, 10), pady=10, ipadx=20, ipady=5)
        
        ttk.Button(button_frame, text="❌ 取消 (Esc)", command=cancel).grid(row=0, column=2, 
                  padx=(10, 0), pady=10, ipadx=20, ipady=5)
        
        # 提示标签
        tip_label = ttk.Label(main_frame, text="💡 提示：也可以按数字键 1 或 2 快速选择，按 Esc 取消", 
                             font=('Segoe UI', 9), foreground='#95a5a6')
        tip_label.grid(row=3, column=0, pady=(5, 0))
        
        dialog.wait_window()
        return result['choice']
    
    def show_comparison_window(self, record1, record2):
        """显示对比窗口"""
        comp_window = tk.Toplevel(self.root)
        comp_window.title(f"🔍 对比报告: 本次(ID:{record1.id}) vs 上次(ID:{record2.id})")
        comp_window.geometry("1000x700")
        comp_window.transient(self.root)
        comp_window.minsize(900, 600)
        
        # 创建滚动文本区域
        comp_text = scrolledtext.ScrolledText(comp_window, wrap=tk.WORD, font=('Consolas', 10))
        comp_text.pack(fill=tk.BOTH, expand=True, padx=15, pady=15)
        
        # 进行对比
        comparison = ComparisonEngine.compare_records(record1, record2)
        
        # 生成对比报告
        report = self.generate_comparison_report(record1, record2, comparison)
        comp_text.insert(1.0, report)
        comp_text.config(state='disabled')
    
    def generate_comparison_report(self, record1, record2, comparison):
        """生成对比报告"""
        report = "📊 评测记录对比报告\n"
        report += "=" * 80 + "\n\n"
        
        # 基本信息
        report += "📋 基本信息\n"
        report += "─" * 40 + "\n"
        report += f"🆕 本次 (ID: {record1.id}): {record1.timestamp[:19].replace('T', ' ')}\n"
        report += f"📊 上次 (ID: {record2.id}): {record2.timestamp[:19].replace('T', ' ')}\n"
        if record1.notes:
            report += f"📝 本次备注: {record1.notes}\n"
        if record2.notes:
            report += f"📝 上次备注: {record2.notes}\n"
        report += "\n"
        
        # 摘要对比
        summary_comp = comparison['summary']
        if 'error' not in summary_comp:
            report += "📈 整体摘要对比\n"
            report += "─" * 40 + "\n"
            
            # 分数变化
            score_diff = summary_comp['score_diff']
            if score_diff > 0:
                report += f"🎯 总分变化: {score_diff:+d} (提升)\n"
            elif score_diff < 0:
                report += f"🎯 总分变化: {score_diff:+d} (下降)\n"
            else:
                report += f"🎯 总分变化: 无变化\n"
            
            # 时间变化（从性能角度）
            time_diff = summary_comp['time_diff']
            if time_diff > 0:
                report += f"⏱️ 总时间变化: {time_diff:+.2f}s (性能下降)\n"
            elif time_diff < 0:
                report += f"⏱️ 总时间变化: {time_diff:+.2f}s (性能提升)\n"
            else:
                report += f"⏱️ 总时间变化: 无变化\n"
            
            report += f"🔄 状态变化: {'是' if summary_comp['status_changed'] else '否'}\n\n"
        
        # 正确性测试对比
        report += "✅ 正确性测试对比\n"
        report += "=" * 80 + "\n\n"
        
        correctness_sections = ['functional', 'h_functional']
        correctness_names = ['🧪 Functional 测试', '🔬 H_Functional 测试']
        
        for i, section in enumerate(correctness_sections):
            comp = comparison[section]
            report += f"{correctness_names[i]}\n"
            report += "─" * 50 + "\n"
            
            # 统计信息
            total_changes = len(comp['improved']) + len(comp['regressed']) + len(comp['new_tests']) + len(comp['removed_tests'])
            
            if total_changes == 0:
                report += "🟢 无变化\n\n"
                continue
            
            # 改进的测试
            if comp['improved']:
                report += f"✅ 改进 ({len(comp['improved'])} 个)\n"
                for item in comp['improved'][:8]:  # 显示前8个
                    report += f"   • {item['name']}: {item['from']} → {item['to']}\n"
                if len(comp['improved']) > 8:
                    report += f"   • ... 还有 {len(comp['improved']) - 8} 个改进\n"
                report += "\n"
            
            # 退化的测试
            if comp['regressed']:
                report += f"❌ 退化 ({len(comp['regressed'])} 个)\n"
                for item in comp['regressed'][:8]:
                    report += f"   • {item['name']}: {item['from']} → {item['to']}\n"
                if len(comp['regressed']) > 8:
                    report += f"   • ... 还有 {len(comp['regressed']) - 8} 个退化\n"
                report += "\n"
            

            
            # 新增测试
            if comp['new_tests']:
                report += f"🆕 新增测试 ({len(comp['new_tests'])} 个)\n"
                for item in comp['new_tests'][:5]:
                    report += f"   • {item['name']}: {item['result']}\n"
                if len(comp['new_tests']) > 5:
                    report += f"   • ... 还有 {len(comp['new_tests']) - 5} 个新增测试\n"
                report += "\n"
            
            # 移除测试
            if comp['removed_tests']:
                report += f"🗑️ 移除测试 ({len(comp['removed_tests'])} 个)\n"
                for item in comp['removed_tests'][:5]:
                    report += f"   • {item['name']}: {item['result']}\n"
                if len(comp['removed_tests']) > 5:
                    report += f"   • ... 还有 {len(comp['removed_tests']) - 5} 个移除测试\n"
                report += "\n"
            
            # 无变化统计
            if comp['unchanged_count'] > 0:
                report += f"⏸️ 无变化: {comp['unchanged_count']} 个\n\n"
        
        # 性能测试对比
        report += "🚀 性能测试对比\n"
        report += "=" * 80 + "\n\n"
        
        perf_comp = comparison['performance']
        report += self._generate_performance_report(perf_comp)
        
        return report
    
    def _generate_performance_report(self, perf_comp):
        """生成性能测试对比报告"""
        report = ""
        
        # 检查是否有任何变化
        has_changes = (perf_comp['improved'] or perf_comp['regressed'] or 
                      perf_comp['new_tests'] or perf_comp['removed_tests'] or
                      perf_comp.get('time_changes', []))
        
        if not has_changes:
            return "🟢 性能测试无变化\n\n"
        
        # 正确性变化（通过/失败状态的变化）
        if perf_comp['improved'] or perf_comp['regressed']:
            report += "📊 正确性变化\n"
            report += "─" * 30 + "\n"
            
            if perf_comp['improved']:
                report += f"✅ 新通过 ({len(perf_comp['improved'])} 个)\n"
                for item in perf_comp['improved'][:5]:
                    report += f"   • {item['name']}: {item['from']} → {item['to']}\n"
                if len(perf_comp['improved']) > 5:
                    report += f"   • ... 还有 {len(perf_comp['improved']) - 5} 个\n"
                report += "\n"
            
            if perf_comp['regressed']:
                report += f"❌ 新失败 ({len(perf_comp['regressed'])} 个)\n"
                for item in perf_comp['regressed'][:5]:
                    report += f"   • {item['name']}: {item['from']} → {item['to']}\n"
                if len(perf_comp['regressed']) > 5:
                    report += f"   • ... 还有 {len(perf_comp['regressed']) - 5} 个\n"
                report += "\n"
        
        # 运行时间变化（只关心时间变化）
        if perf_comp.get('time_changes'):
            # 额外验证：确保只包含真正的Performance测试的时间变化
            valid_time_changes = [tc for tc in perf_comp['time_changes'] 
                                 if 'time1' in tc and 'time2' in tc and 
                                 tc['time1'] is not None and tc['time2'] is not None]
            
            if valid_time_changes:
                report += "⏱️ 运行时间变化详情\n"
                report += "─" * 90 + "\n"
                report += f"📊 共发现 {len(valid_time_changes)} 个AC测试的运行时间有显著变化 (>5% 或 >0.1s)\n"
                report += "💡 注：只对比两次都通过(AC)的测试时间，WA测试的时间不具备参考价值\n\n"
                
                time_changes = valid_time_changes
            
                # 按时间变化幅度排序
                time_changes_sorted = sorted(time_changes, 
                                           key=lambda x: abs(x['time_change_percent']), reverse=True)
                
                # 创建简单的表格显示性能变化
                report += f"{'测试名称':<25} {'前次':<8} {'本次':<8} {'变化':<12} {'百分比':<6} {'状态':<4}\n"
                report += "─" * 80 + "\n"
                
                for item in time_changes_sorted:
                    test_name = item['name']
                    # 简单截断测试名称
                    if len(test_name) > 28:
                        test_name = test_name[:25] + "..."
                    
                    time1_str = f"{item['time1']:.3f}s"
                    time2_str = f"{item['time2']:.3f}s"
                    
                    # 计算变化
                    time_diff = item['time_diff']
                    time_change_percent = item['time_change_percent']
                    
                    if time_diff < 0:
                        change_str = f"-{abs(time_diff):.3f}s"
                        percent_str = f"{time_change_percent:.1f}%"
                        status = "🚀"
                    else:
                        change_str = f"+{time_diff:.3f}s"
                        percent_str = f"{time_change_percent:.1f}%"
                        status = "🐌"
                    
                    # 构建数据行
                    report += f"{test_name:<30} {time2_str:<10} {time1_str:<10} {change_str:<12} {percent_str:<8} {status:<4}\n"
                
                # 添加性能变化总结
                if time_changes_sorted:
                    faster_count = sum(1 for item in time_changes_sorted if item['time_diff'] < 0)
                    slower_count = len(time_changes_sorted) - faster_count
                    avg_improvement = sum(item['time_change_percent'] for item in time_changes_sorted if item['time_diff'] < 0)
                    avg_regression = sum(item['time_change_percent'] for item in time_changes_sorted if item['time_diff'] > 0)
                    
                    report += f"\n📈 性能变化总结：\n"
                    if faster_count > 0:
                        avg_improvement_percent = avg_improvement / faster_count if faster_count > 0 else 0
                        report += f"   🚀 性能提升：{faster_count} 个测试，平均提升 {abs(avg_improvement_percent):.1f}%\n"
                    if slower_count > 0:
                        avg_regression_percent = avg_regression / slower_count if slower_count > 0 else 0
                        report += f"   🐌 性能下降：{slower_count} 个测试，平均下降 {avg_regression_percent:.1f}%\n"
                
                report += "\n"
        
        # 新增和移除的性能测试
        if perf_comp['new_tests']:
            report += f"🆕 新增性能测试 ({len(perf_comp['new_tests'])} 个)\n"
            for item in perf_comp['new_tests'][:5]:
                time_str = f" ({item['time']:.3f}s)" if item.get('time') is not None else ""
                report += f"   • {item['name']}: {item['result']}{time_str}\n"
            if len(perf_comp['new_tests']) > 5:
                report += f"   • ... 还有 {len(perf_comp['new_tests']) - 5} 个\n"
            report += "\n"
        
        if perf_comp['removed_tests']:
            report += f"🗑️ 移除性能测试 ({len(perf_comp['removed_tests'])} 个)\n"
            for item in perf_comp['removed_tests'][:5]:
                time_str = f" ({item['time']:.3f}s)" if item.get('time') is not None else ""
                report += f"   • {item['name']}: {item['result']}{time_str}\n"
            if len(perf_comp['removed_tests']) > 5:
                report += f"   • ... 还有 {len(perf_comp['removed_tests']) - 5} 个\n"
            report += "\n"
        
        # AC无变化统计和详情
        if perf_comp['unchanged_count'] > 0:
            report += f"⏸️ AC无变化: {perf_comp['unchanged_count']} 个\n"
            
            # 在报告中显示详细列表
            report += "   详细列表:\n"
            for i, item in enumerate(perf_comp['unchanged'], 1):
                time_str = f" ({item['time']:.3f}s)" if item.get('time') is not None else ""
                report += f"   {i:3d}. {item['name']}: {item['result']}{time_str}\n"
            report += "\n"

        # WA等无变化统计和详情
        if perf_comp['wa_unchanged_count'] > 0:
            report += f"❌ WA等无变化: {perf_comp['wa_unchanged_count']} 个\n"
            
            # 在报告中显示详细列表
            report += "   详细列表:\n"
            for i, item in enumerate(perf_comp['wa_unchanged'], 1):
                time_str = f" ({item['time']:.3f}s)" if item.get('time') is not None else ""
                report += f"   {i:3d}. {item['name']}: {item['result']}{time_str}\n"
            report += "\n"

        
        return report
    
    def delete_record(self):
        """删除记录"""
        selection = self.tree.selection()
        if not selection:
            messagebox.showwarning("⚠️ 警告", "请选择要删除的记录")
            return
        
        item = self.tree.item(selection[0])
        record_id = int(item['values'][0])
        
        # 确认删除
        if messagebox.askyesno("🗑️ 确认删除", 
                              f"确定要删除记录 ID: {record_id} 吗？\n\n⚠️ 此操作不可撤销！",
                              icon='warning'):
            try:
                self.status_label.config(text="正在删除记录...")
                self.root.update()
                
                success = self.db.delete_record(record_id)
                if success:
                    self.tree.delete(selection[0])
                    self.detail_text.delete(1.0, tk.END)
                    self.detail_text.insert(1.0, "请选择一个记录查看详情")
                    self.status_label.config(text=f"已删除记录 ID: {record_id}")
                    messagebox.showinfo("✅ 成功", f"记录 ID: {record_id} 已成功删除")
                    self.refresh_records()
                else:
                    self.status_label.config(text="删除失败")
                    messagebox.showerror("❌ 错误", "删除失败：记录不存在")
            except Exception as e:
                self.status_label.config(text="删除失败")
                messagebox.showerror("❌ 错误", f"删除失败:\n\n{str(e)}")


def main():
    """主函数"""
    root = tk.Tk()
    app = EvaluationGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main() 
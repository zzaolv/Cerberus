import os
from pathlib import Path

# --- 配置区域 ---
# 您可以在这里轻松定制需要展示和忽略的内容

# 1. 需要扫描并展示其内部结构的【顶层文件夹】列表
# (已根据您的要求移除了 'build')
DIRECTORIES_TO_TREE = [
    "app",
    "daemon",
    "gradle",
    "magisk",
]

# 2. 需要在根级别直接展示的【顶层文件】列表
FILES_TO_TREE = [
    "build.gradle.kts",
    "settings.gradle.kts",
]

# 3. 全局【排除列表】
# 任何名称匹配此列表的文件夹或文件都将被忽略，无论它在哪里。
# 这是保持目录树干净整洁的关键！
EXCLUDE_LIST = [
    "build",        # 排除所有编译输出目录
    ".git",         # 排除Git版本控制目录
    ".gradle",      # 排除Gradle缓存目录
    ".idea",        # 排除IntelliJ IDEA项目文件目录
    "__pycache__",  # 排除Python缓存目录
    ".DS_Store",    # 排除macOS系统文件
    "*.iml",        # 排除IntelliJ模块文件
    "local.properties", # 排除本地配置文件
]


# --- 树状图绘制符号 ---
PREFIX_MIDDLE = "├── "
PREFIX_LAST = "└── "
PREFIX_PARENT = "│   "
PREFIX_EMPTY = "    "

def generate_tree(directory: Path, prefix: str = ""):
    """
    递归函数，用于生成并打印一个目录的树状结构，会进行过滤。

    参数:
        directory (Path): 需要扫描的目录路径。
        prefix (str): 用于绘制树状图连接线的前缀字符串。
    """
    try:
        # 1. 获取所有子项
        all_paths = list(directory.iterdir())
        
        # 2. 过滤掉所有在 EXCLUDE_LIST 中的项
        filtered_paths = []
        for path in all_paths:
            is_excluded = False
            for pattern in EXCLUDE_LIST:
                if path.match(pattern):
                    is_excluded = True
                    break
            if not is_excluded:
                filtered_paths.append(path)

        # 3. 对过滤后的结果进行排序
        paths = sorted(filtered_paths, key=lambda p: (p.is_file(), p.name.lower()))
        
    except PermissionError:
        print(f"{prefix}└── ❗ [无权限访问]")
        return
        
    paths_count = len(paths)
    for i, path in enumerate(paths):
        is_last = i == (paths_count - 1)
        connector = PREFIX_LAST if is_last else PREFIX_MIDDLE
        
        print(f"{prefix}{connector}{path.name}")

        if path.is_dir():
            new_prefix = prefix + (PREFIX_EMPTY if is_last else PREFIX_PARENT)
            generate_tree(path, new_prefix)


def main():
    """
    脚本的主入口函数。
    """
    root_path = Path.cwd()
    print(f"🌳 项目核心目录结构 for: {root_path}\n.")

    # 合并要处理的顶层目录和文件
    top_level_items = DIRECTORIES_TO_TREE + FILES_TO_TREE
    
    # 过滤掉不存在的以及在排除列表中的顶层项
    existing_items = [
        item for item in top_level_items 
        if (root_path / item).exists() and not any((root_path / item).match(p) for p in EXCLUDE_LIST)
    ]
    
    if not existing_items:
        print("🤷 在当前目录下没有找到任何需要展示的文件夹或文件。")
        return

    # 排序（文件夹在前）
    sorted_items = sorted(existing_items, key=lambda name: not (root_path / name).is_dir())
    
    items_count = len(sorted_items)
    for i, item_name in enumerate(sorted_items):
        is_last = i == (items_count - 1)
        connector = PREFIX_LAST if is_last else PREFIX_MIDDLE
        
        item_path = root_path / item_name
        
        print(f"{connector}{item_name}")
        
        if item_path.is_dir():
            new_prefix = PREFIX_EMPTY if is_last else PREFIX_PARENT
            generate_tree(item_path, new_prefix)
    
    print("\n✨ 生成完毕！")


if __name__ == "__main__":
    main()

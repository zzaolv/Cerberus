import os
from pathlib import Path

# --- 配置 ---
# 这个脚本专门用于提取 'daemon' 目录的内容

# 1. 要扫描的根目录
SCAN_ROOT_DIR = 'daemon'

# 2. 在扫描时要【完全忽略】的子目录列表
EXCLUDE_DIRS = ['daemon/third_party']

# 3. 输出文件的名称
OUTPUT_FILENAME = "daemon_context.txt"

# --- 脚本主逻辑 (通常无需修改) ---

def write_file_content(output_file, file_path: Path):
    """
    将单个文件的相对路径和内容写入到输出文件中。
    
    参数:
        output_file: 已打开的输出文件的文件句柄。
        file_path: 要处理的文件的Path对象。
    """
    try:
        # 使用 'utf-8' 编码读取文件内容，这是最常见的编码。
        content = file_path.read_text(encoding='utf-8')
        print(f"✅ 成功处理: {file_path}")
        
        # 写入文件的相对路径，使用 as_posix() 确保路径分隔符为 '/'
        output_file.write(f"{file_path.as_posix()}\n")
        # 写入代码块的起始标记
        output_file.write("```\n")
        # 写入文件内容
        output_file.write(content)
        # 写入代码块的结束标记，并添加两个换行符以分隔条目
        output_file.write("\n```\n\n")
        
    except Exception as e:
        # 捕获所有可能的读取错误，例如权限问题或编码错误
        print(f"❌ 错误: 处理文件 {file_path} 时发生异常: {e}")

def main():
    """
    主函数，执行文件提取和写入操作。
    """
    base_path = Path.cwd()
    print(f"🚀 开始执行... 项目根目录: {base_path}")

    root_path = Path(SCAN_ROOT_DIR)
    # 将要排除的目录字符串列表转换为Path对象列表
    exclude_paths = [Path(p) for p in EXCLUDE_DIRS]

    if not root_path.is_dir():
        print(f"❌ 致命错误: 找不到要扫描的根目录 '{root_path}'。请确保脚本与 '{root_path}' 目录在同一级别。")
        return

    with open(OUTPUT_FILENAME, "w", encoding="utf-8") as f_out:
        print(f"📂 正在扫描目录: '{root_path}'...")
        if exclude_paths:
            print(f"   (排除规则: {', '.join(map(str, exclude_paths))})")

        # 使用 rglob('*') 递归查找所有子项，并排序以保证顺序
        for item_path in sorted(root_path.rglob('*')):
            # 检查当前项是否应该被排除
            is_excluded = False
            for excluded in exclude_paths:
                # 如果排除路径是当前项的父目录，或者就是当前项本身，则标记为排除
                if excluded in item_path.parents or excluded == item_path:
                    is_excluded = True
                    break
            
            if is_excluded:
                continue # 跳过这个被排除的文件或目录

            # 确保我们只处理文件，不处理目录本身
            if item_path.is_file():
                write_file_content(f_out, item_path)

    print(f"\n🎉 全部完成! 所有内容已成功保存到文件: {OUTPUT_FILENAME}")

if __name__ == "__main__":
    main()

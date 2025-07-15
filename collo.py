import os
from pathlib import Path

# --- 配置 ---
# 这是一个配置区域，您可以根据需要修改要查找的文件和目录

# 1. 要提取的特定文件的相对路径列表
# 脚本会精确查找这些文件。
SPECIFIC_FILES = [
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle/libs.versions.toml",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
]

# 2. 要递归提取其中所有文件的目录列表
# 脚本会进入这些目录，并提取里面的每一个文件。
RECURSIVE_DIRS = [
    "app/src/main/java",
    "app/src/main/kotlin", # 同时添加kotlin目录以防万一
]

# 3. 输出文件的名称
OUTPUT_FILENAME = "project_context.txt"

# --- 脚本主逻辑 ---

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
        
    except FileNotFoundError:
        # 这个错误理论上不会发生，因为我们已经检查过文件存在
        print(f"❌ 错误: 未找到文件 {file_path}")
    except Exception as e:
        # 捕获其他可能的读取错误，例如权限问题
        print(f"❌ 错误: 处理文件 {file_path} 时发生异常: {e}")

def main():
    """
    主函数，执行文件提取和写入操作。
    """
    # 获取当前脚本运行的目录作为项目根目录
    base_path = Path.cwd()
    print(f"🚀 开始执行... 项目根目录: {base_path}")

    # 使用 'w' 模式打开输出文件，如果文件已存在则会覆盖。
    # 使用 utf-8 编码确保能处理各种字符。
    with open(OUTPUT_FILENAME, "w", encoding="utf-8") as f_out:
        
        # --- 第一步: 处理指定的单个文件 ---
        print("\n--- 正在处理指定的配置文件 ---")
        for file_str in SPECIFIC_FILES:
            file_path = Path(file_str)
            # 检查文件是否存在并且它确实是一个文件（而不是目录）
            if file_path.is_file():
                write_file_content(f_out, file_path)
            else:
                print(f"⚠️  警告: 未找到指定的配置文件 {file_path}，已跳过。")

        # --- 第二步: 处理需要递归搜索的目录 ---
        print("\n--- 正在扫描并处理源码目录 ---")
        for dir_str in RECURSIVE_DIRS:
            dir_path = Path(dir_str)
            # 检查目录是否存在
            if dir_path.is_dir():
                print(f"📂 正在扫描目录: {dir_path}...")
                # 使用 rglob('*') 递归查找所有子项，并排序以保证顺序一致
                for item_path in sorted(dir_path.rglob('*')):
                    # 确保找到的是文件，而不是子目录
                    if item_path.is_file():
                        write_file_content(f_out, item_path)
            else:
                print(f"⚠️  警告: 未找到要扫描的源码目录 {dir_path}，已跳过。")

    print(f"\n🎉 全部完成! 所有内容已成功保存到文件: {OUTPUT_FILENAME}")

# 当该脚本作为主程序运行时，才执行main()函数
if __name__ == "__main__":
    main()

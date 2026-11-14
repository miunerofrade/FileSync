# uiautomator dump 坐标解析：stdin 读 XML，argv[1] 为要点的文本，stdout 输出 "x y"
import re
import sys

text = sys.argv[1]
xml = sys.stdin.buffer.read().decode("utf-8", "replace")
m = re.search(
    r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
)
if not m:
    sys.exit(1)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
print(x, y)

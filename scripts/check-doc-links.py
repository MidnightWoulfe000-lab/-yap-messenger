"""Validate local Markdown file links, excluding generated/vendored material."""
import pathlib
import re
import subprocess
from urllib.parse import unquote

root = pathlib.Path(__file__).resolve().parent.parent
names = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root).decode().split("\0")
broken = []
count = 0
for name in set(names):
    if not name.endswith(".md") or not (root / name).is_file():
        continue
    document = root / name
    for link in re.findall(r"\]\(([^)]+)\)", document.read_text(encoding="utf-8-sig")):
        link = link.strip("<>").split("#", 1)[0]
        if not link or "://" in link or link.startswith("mailto:"):
            continue
        count += 1
        if not (document.parent / unquote(link)).exists():
            broken.append(f"{name}: {link}")
if broken:
    raise SystemExit("Broken links:\n" + "\n".join(broken))
print(f"Validated {count} local Markdown file links")

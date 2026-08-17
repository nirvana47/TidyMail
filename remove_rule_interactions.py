import json
from pathlib import Path

p = Path("/Users/bytedance/Documents/trae_projects/TidyMail/tidymail-ux-rehaul/tidymail-ux-rehaul.design")
d = json.loads(p.read_text())

def stale(i):
    return (
        i.get("domId") in {"nav-rules", "footer-next-rules"}
        or "rule-studio" in i.get("targetPageId", "")
    )

before = sum(
    1
    for n in d["data"]
    for i in n.get("devMetadata", {}).get("interactions", [])
    if stale(i)
)

for n in d["data"]:
    n["devMetadata"]["interactions"] = [
        i for i in n.get("devMetadata", {}).get("interactions", []) if not stale(i)
    ]

p.write_text(json.dumps(d, indent=2) + "\n")

d2 = json.loads(p.read_text())
after = sum(
    1
    for n in d2["data"]
    for i in n.get("devMetadata", {}).get("interactions", [])
    if stale(i)
)
print({"before": before, "after": after})

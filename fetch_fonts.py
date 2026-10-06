import re, urllib.request, os
CSS_URL = "https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@500;600;700&family=Barlow:wght@400;500;600&display=swap"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
def get(u):
    return urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA}), timeout=60).read()
css = get(CSS_URL).decode("utf-8")
os.makedirs("out/fonts", exist_ok=True)
blocks = re.findall(r"/\*\s*([\w-]+)\s*\*/\s*(@font-face\s*\{[^}]*\})", css)
urls, out = {}, []
for subset, block in blocks:
    if subset not in ("latin", "latin-ext"):
        continue
    u = re.search(r"url\((https://[^)]+)\)", block).group(1)
    if u not in urls:
        name = "f%d.woff2" % len(urls)
        urls[u] = name
        open("out/fonts/" + name, "wb").write(get(u))
    out.append("/* %s */\n%s" % (subset, block.replace(u, "fonts/" + urls[u])))
open("out/fonts.css", "w").write("\n".join(out) + "\n")
open("out/source.css", "w").write(css)
for fam in ("barlow", "barlowcondensed"):
    open("out/OFL-%s.txt" % fam, "wb").write(get("https://raw.githubusercontent.com/google/fonts/main/ofl/%s/OFL.txt" % fam))
print(len(blocks), "blocks;", len(urls), "files")

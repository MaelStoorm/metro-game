#!/bin/bash
# iOS uygulamasının site klasör(ler)ini hazırlar (GitHub Actions'ta derlemeden önce çalışır).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 ios/site.py --html index.html --out ios/gen/RayliIstanbul/site --fonts-css android/app/fonts.css --fonts-dir android/app/src/main/assets/fonts --copy gizlilik.html icon-192.png

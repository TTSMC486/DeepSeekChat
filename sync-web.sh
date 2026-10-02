#!/usr/bin/env bash
# 把 web/index.html 同步为 Android assets（修改网页后运行一次即可）
set -e
cd "$(dirname "$0")"
cp -f web/index.html android/app/src/main/assets/index.html
echo "synced: web/index.html -> android/app/src/main/assets/index.html ($(wc -c < web/index.html) bytes)"

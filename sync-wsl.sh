#!/bin/bash
# Mirrors the working tree into WSL. Deletes first, because tar overwrites but never removes:
# a file deleted on a branch would otherwise linger and be compiled, which has already produced
# one false build failure and could as easily produce a false success.
set -e
cd "$(dirname "$0")"
for module in app core runtime platform gradle; do
    wsl.exe -d Ubuntu -e bash -lc "rm -rf ~/bram-src/$module/*/src ~/bram-src/$module/src 2>/dev/null; true"
done
tar cf - --exclude=.cxx --exclude=build --exclude=.gradle settings.gradle.kts build.gradle.kts \
    app core runtime platform gradle \
    | wsl.exe -d Ubuntu -e bash -lc 'cd ~/bram-src && tar xf -'

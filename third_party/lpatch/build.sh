#!/bin/sh
# Build lpatch for the head unit (ARMv7 Thumb, static, no libc) and for the host.
set -e
cd "$(dirname "$0")"
clang --target=armv7a-linux-gnueabi -mthumb -march=armv7-a -mfloat-abi=soft -Os \
  -ffreestanding -fno-builtin -nostdlib -static -fno-stack-protector -fuse-ld=lld \
  -Wl,-e,_start -Wl,--build-id=none -o lpatch lpatch.c
cc -DHOST -O2 -Wall -o lpatch-host lpatch.c
md5sum lpatch

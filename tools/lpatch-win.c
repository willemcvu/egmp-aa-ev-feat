/* Windows host build of third_party/lpatch/lpatch.c (unmodified): the same
 * code, with every file opened in binary mode.
 *   python -m ziglang cc -target x86_64-windows-gnu -DHOST -O2 \
 *       -I third_party/lpatch -o lpatch.exe tools/lpatch-win.c
 * (pip install ziglang; or any MinGW gcc). */
#include <fcntl.h>
#include <io.h>
#include <unistd.h>
#include <stdlib.h>
#define open(p, f, m) _open((p), (f) | _O_BINARY, (m))
#include "lpatch.c"

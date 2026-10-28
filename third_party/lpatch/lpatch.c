/*
 * lpatch - verify-then-patch tool for HMG Gen5W head units (HMG Leafia Plus).
 *
 * A recipe lists byte-level patches together with hashes of the regions
 * around them. Nothing is written unless every region hashes as expected,
 * so one recipe works on any firmware whose patched spots are identical,
 * even when the rest of the file differs.
 *
 *   lpatch check <recipe> [file]        report status, exit 0 if usable
 *   lpatch apply <recipe> <src> <dst>   write a patched copy of src to dst
 *   lpatch md5 <file>
 *   lpatch delta <patch.lpd> <src> <dst>  rebuild a file from a delta, only if src
 *                                       is the exact expected original
 *   lpatch zipadd <src.apk> <classes.dex> <dst.apk>
 *                                       add classes.dex (stored) to an APK
 *
 * Delta files (.lpd) rebuild a modified app's code from the head unit's own
 * stock copy. They hold the md5 of the original and of the result: nothing is
 * written unless the source is that exact original and the rebuilt file
 * matches exactly.
 *
 * Recipe lines (anything after # is a comment):
 *   id <name>
 *   file <path on the head unit>
 *   native <md5> [note]      file already provides the feature: nothing to do
 *   verify <hexoffset> <len> <md5 before> <md5 after>
 *   patch <hexoffset> <replacement-hex>
 *
 * Recipes hold only hashes of the head unit's own bytes (verify regions,
 * which include unchanged bytes around each change) and the new bytes we
 * write, so they contain no manufacturer code.
 *
 * check prints one "RESULT" line and one "SPOT" line per patch:
 *   PATCHABLE  every region is original      APPLIED  every region is patched
 *   NATIVE     known file with the feature   MISMATCH some spot is neither
 *   MISSING    file not found
 *
 * Builds freestanding for ARM (no libc, raw syscalls) or with -DHOST.
 * GPL-3.0-or-later.
 */
typedef unsigned int u32; typedef unsigned char u8; typedef unsigned long ul;

#ifdef HOST
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
static long xopen(const char *p, int fl, int m) { return open(p, fl, m); }
static long xread(int fd, void *b, ul n) { return read(fd, b, n); }
static long xwrite(int fd, const void *b, ul n) { return write(fd, b, n); }
static void xclose(int fd) { close(fd); }
static void xexit(int c) { exit(c); }
#define O_WR (O_WRONLY | O_CREAT | O_TRUNC)
#else
static long sys3(long n, long a, long b, long c)
{
    register long r7 asm("r7") = n; register long r0 asm("r0") = a;
    register long r1 asm("r1") = b; register long r2 asm("r2") = c;
    asm volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2) : "memory");
    return r0;
}
static long xopen(const char *p, int fl, int m) { return sys3(5, (long)p, fl, m); }
static long xread(int fd, void *b, ul n) { return sys3(3, fd, (long)b, n); }
static long xwrite(int fd, const void *b, ul n) { return sys3(4, fd, (long)b, n); }
static void xclose(int fd) { sys3(6, fd, 0, 0); }
static void xexit(int c) { sys3(1, c, 0, 0); for (;;) ; }
#define O_RDONLY 0
#define O_WR 0x241   /* O_WRONLY|O_CREAT|O_TRUNC */
#endif

#define MAXFILE (8u << 20)
#define MAXOUT (12u << 20)   /* an APK with its new classes.dex (HKMC_BtSettings is ~6 MB) */
#define MAXDELTA (6u << 20)
#define MAXRAW (16u << 20)
#define MAXRECIPE (256u << 10)
#define MAXPATCH 256
#define MAXNATIVE 32

static u8 fbuf[MAXFILE];
static u8 obuf[MAXOUT];
static u8 dbuf[MAXDELTA];
static u8 ubuf[MAXRAW];
static char rbuf[MAXRECIPE + 1];
static ul flen;

/* ---- output ---- */
static ul slen(const char *s) { ul n = 0; while (s[n]) n++; return n; }
static void out(const char *s) { xwrite(1, s, slen(s)); }
static void err(const char *s) { xwrite(2, s, slen(s)); }
static void outnum(ul v) { char b[24]; int i = 23; b[i] = 0; do { b[--i] = '0' + v % 10; v /= 10; } while (v); out(b + i); }
static void outhex(ul v) { char b[16]; int i = 15; b[i] = 0; do { b[--i] = "0123456789abcdef"[v & 15]; v >>= 4; } while (v); out("0x"); out(b + i); }
static void die(const char *s) { err("lpatch: "); err(s); err("\n"); xexit(2); }

/* ---- md5 (RFC 1321) ---- */
static const u32 K[64] = {
    0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
    0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
    0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
    0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
    0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
    0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
    0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
    0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391 };
static const u8 R[64] = { 7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22, 5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
    4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23, 6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21 };
static void md5blk(u32 *h, const u8 *p)
{
    u32 w[16], a = h[0], b = h[1], c = h[2], d = h[3], f, g, t;
    for (int i = 0; i < 16; i++) w[i] = p[4*i] | (p[4*i+1] << 8) | (p[4*i+2] << 16) | ((u32)p[4*i+3] << 24);
    for (int i = 0; i < 64; i++) {
        if (i < 16) { f = (b & c) | (~b & d); g = i; }
        else if (i < 32) { f = (d & b) | (~d & c); g = (5*i + 1) & 15; }
        else if (i < 48) { f = b ^ c ^ d; g = (3*i + 5) & 15; }
        else { f = c ^ (b | ~d); g = (7*i) & 15; }
        t = d; d = c; c = b; f += a + K[i] + w[g];
        b += (f << R[i]) | (f >> (32 - R[i])); a = t;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d;
}
typedef struct { u32 h[4]; u8 buf[64]; ul n, used; } Md5;
static void md5init(Md5 *c) { c->h[0] = 0x67452301; c->h[1] = 0xefcdab89; c->h[2] = 0x98badcfe; c->h[3] = 0x10325476; c->n = 0; c->used = 0; }
static void md5add(Md5 *c, const u8 *p, ul n)
{
    c->n += n;
    while (n) {
        ul take = 64 - c->used; if (take > n) take = n;
        for (ul i = 0; i < take; i++) c->buf[c->used + i] = p[i];
        c->used += take; p += take; n -= take;
        if (c->used == 64) { md5blk(c->h, c->buf); c->used = 0; }
    }
}
static void md5end(Md5 *c, char *o)
{
    unsigned long long bits = (unsigned long long)c->n * 8; u8 pad = 0x80, z = 0, len[8]; int i;
    for (i = 0; i < 8; i++) len[i] = (u8)(bits >> (8*i));
    md5add(c, &pad, 1);
    while (c->used != 56) md5add(c, &z, 1);
    md5add(c, len, 8);
    for (i = 0; i < 16; i++) { u8 v = (u8)(c->h[i/4] >> (8*(i%4))); o[2*i] = "0123456789abcdef"[v >> 4]; o[2*i+1] = "0123456789abcdef"[v & 15]; }
    o[32] = 0;
}
static void md5hex(const u8 *p, ul n, char *o) { Md5 c; md5init(&c); md5add(&c, p, n); md5end(&c, o); }

/* ---- files ---- */
static int loadfile(const char *path)
{
    long fd = xopen(path, O_RDONLY, 0), r;
    if (fd < 0) return -1;
    flen = 0;
    while ((r = xread(fd, fbuf + flen, MAXFILE - flen)) > 0) flen += r;
    xclose(fd);
    if (r < 0) return -1;
    if (flen == MAXFILE) die("file too large");
    return 0;
}
static int writefile(const char *path, const u8 *p, ul n)
{
    long fd = xopen(path, O_WR, 0644), r; ul t = 0;
    if (fd < 0) return -1;
    while (t < n) { r = xwrite(fd, p + t, n - t); if (r <= 0) { xclose(fd); return -1; } t += r; }
    xclose(fd);
    return 0;
}

/* ---- recipe ---- */
typedef struct { ul off, len; u8 *repl; } Patch;
typedef struct { ul off, len; char before[33], after[33]; } Verify;
static Patch P[MAXPATCH]; static int np;
static Verify V[MAXPATCH]; static int nv;
static char *rid = "?", *rfile = 0, *nat[MAXNATIVE], *natnote[MAXNATIVE]; static int nn;
static u8 hexpool[MAXRECIPE]; static ul hexused;

static int hx(char c) { if (c >= '0' && c <= '9') return c - '0'; if (c >= 'a' && c <= 'f') return c - 'a' + 10; if (c >= 'A' && c <= 'F') return c - 'A' + 10; return -1; }
static ul parsehexnum(const char *s) { ul v = 0; if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2; while (hx(*s) >= 0) v = v * 16 + hx(*s++); return v; }
static ul parsedec(const char *s) { ul v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0'); return v; }
static void copymd5(char *d, const char *s) { int i; for (i = 0; i < 32 && s[i]; i++) d[i] = s[i]; if (i != 32) die("bad md5 in recipe"); d[32] = 0; }
static u8 *parsebytes(const char *s, ul *n)
{
    ul l = slen(s); u8 *o = hexpool + hexused;
    if (l & 1) die("odd hex length in recipe");
    for (ul i = 0; i < l / 2; i++) { int a = hx(s[2*i]), b = hx(s[2*i+1]); if (a < 0 || b < 0) die("bad hex in recipe"); o[i] = a * 16 + b; }
    hexused += l / 2; *n = l / 2; return o;
}
static int split(char *line, char **tok, int max)
{
    int n = 0;
    while (*line && n < max) {
        while (*line == ' ' || *line == '\t') *line++ = 0;
        if (!*line || *line == '#') break;
        tok[n++] = line;
        while (*line && *line != ' ' && *line != '\t') line++;
    }
    return n;
}
static void loadrecipe(const char *path)
{
    long fd = xopen(path, O_RDONLY, 0), r; ul n = 0; char *line, *tok[5];
    if (fd < 0) die("cannot open recipe");
    while ((r = xread(fd, rbuf + n, MAXRECIPE - n)) > 0) n += r;
    xclose(fd); rbuf[n] = 0;
    line = rbuf;
    while (*line) {
        char *end = line; while (*end && *end != '\n') end++;
        int last = !*end; *end = 0;
        if (end > line && end[-1] == '\r') end[-1] = 0;
        int t = split(line, tok, 5);
        if (t >= 2 && !tok[0][2] && tok[0][0] == 'i' && tok[0][1] == 'd') rid = tok[1];
        else if (t >= 2 && slen(tok[0]) == 4 && tok[0][0] == 'f' && tok[0][1] == 'i') rfile = tok[1];
        else if (t >= 2 && slen(tok[0]) == 6 && tok[0][0] == 'n' && tok[0][1] == 'a') {
            if (nn >= MAXNATIVE) die("too many native lines");
            nat[nn] = tok[1]; natnote[nn] = t > 2 ? tok[2] : ""; nn++;
        } else if (t >= 5 && slen(tok[0]) == 6 && tok[0][0] == 'v' && tok[0][1] == 'e') {
            if (nv >= MAXPATCH) die("too many verify lines");
            V[nv].off = parsehexnum(tok[1]); V[nv].len = parsedec(tok[2]);
            copymd5(V[nv].before, tok[3]); copymd5(V[nv].after, tok[4]);
            if (!V[nv].len) die("verify length 0");
            nv++;
        } else if (t >= 3 && slen(tok[0]) == 5 && tok[0][0] == 'p' && tok[0][1] == 'a') {
            ul a;
            if (np >= MAXPATCH) die("too many patches");
            P[np].off = parsehexnum(tok[1]);
            P[np].repl = parsebytes(tok[2], &a);
            if (!a) die("empty patch");
            P[np].len = a; np++;
        }
        if (last) break;
        line = end + 1;
    }
    if (!np && !nn) die("recipe has no patch or native lines");
    if (np && !nv) die("recipe has patches but no verify lines");
    for (int i = 0; i < np; i++) {   /* every patched byte must sit inside a verified region */
        int ok = 0;
        for (int j = 0; j < nv; j++) if (P[i].off >= V[j].off && P[i].off + P[i].len <= V[j].off + V[j].len) ok = 1;
        if (!ok) die("patch outside every verify region");
    }
}

static int seq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static int seq2(const char *a, const char *b, ul n) { for (ul i = 0; i < n; i++) if (a[i] != b[i]) return 0; return 1; }

/* status of the loaded file: 0 PATCHABLE 1 APPLIED 2 NATIVE 3 MISMATCH */
static int classify(int verbose)
{
    char m[33]; int before = 0, after = 0;
    md5hex(fbuf, flen, m);
    for (int i = 0; i < nn; i++) if (seq(m, nat[i])) {
        if (verbose) { out("NATIVE_NOTE "); out(rid); out(" "); out(natnote[i]); out("\n"); }
        return 2;
    }
    for (int i = 0; i < nv; i++) {
        int inb = V[i].off + V[i].len <= flen; char h[33];
        h[0] = 0;
        if (inb) md5hex(fbuf + V[i].off, V[i].len, h);
        int b = inb && seq(h, V[i].before), a = inb && seq(h, V[i].after);
        before += b; after += a;
        if (verbose) {
            out("SPOT "); out(rid); out(" off="); outhex(V[i].off); out(" len="); outnum(V[i].len);
            out(b ? " status=original" : a ? " status=patched" : inb ? " status=other" : " status=outside");
            if (inb && !b && !a) { out(" md5="); out(h); }
            out("\n");
        }
    }
    if (nv && before == nv) return 0;
    if (nv && after == nv) return 1;
    return 3;
}

static const char *SNAME[] = { "PATCHABLE", "APPLIED", "NATIVE", "MISMATCH" };

static int cmd_check(const char *recipe, const char *file)
{
    char m[33]; int st;
    loadrecipe(recipe);
    if (!file) file = rfile;
    if (!file) die("no file given and recipe has no file line");
    if (loadfile(file) < 0) {
        out("RESULT "); out(rid); out(" status=MISSING file="); out(file); out("\n");
        return 1;
    }
    st = classify(1);
    md5hex(fbuf, flen, m);
    out("RESULT "); out(rid); out(" status="); out(SNAME[st]);
    out(" file="); out(file); out(" size="); outnum(flen); out(" md5="); out(m); out("\n");
    return st == 3 ? 1 : 0;
}

static int cmd_apply(const char *recipe, const char *src, const char *dst)
{
    int st;
    loadrecipe(recipe);
    if (loadfile(src) < 0) die("cannot read source");
    st = classify(0);
    if (st != 0) {
        err("lpatch: not applying, file is "); err(SNAME[st]); err("\n");
        return st == 3 ? 1 : 3;
    }
    for (int i = 0; i < np; i++)
        for (ul j = 0; j < P[i].len; j++) fbuf[P[i].off + j] = P[i].repl[j];
    if (classify(0) != 1) die("internal: patch did not verify");
    if (writefile(dst, fbuf, flen) < 0) die("cannot write destination");
    if (loadfile(dst) < 0 || classify(0) != 1) die("written file did not verify");
    out("applied "); out(rid); out(" -> "); out(dst); out("\n");
    return 0;
}


/* ---- raw DEFLATE decoder (RFC 1951) ---- */
typedef struct { const u8 *in; ul inlen, pos; u32 bitbuf; int bitcnt; u8 *out; ul outmax, outlen; } Inf;
typedef struct { unsigned short count[16], sym[288]; } Huff;
static int getbit(Inf *s)
{
    if (!s->bitcnt) { if (s->pos >= s->inlen) return -1; s->bitbuf = s->in[s->pos++]; s->bitcnt = 8; }
    int b = s->bitbuf & 1; s->bitbuf >>= 1; s->bitcnt--; return b;
}
static long getbits(Inf *s, int n)
{
    long v = 0;
    for (int i = 0; i < n; i++) { int b = getbit(s); if (b < 0) return -1; v |= (long)b << i; }
    return v;
}
static int build(Huff *h, const u8 *len, int n)
{
    unsigned short offs[16]; int i;
    for (i = 0; i < 16; i++) h->count[i] = 0;
    for (i = 0; i < n; i++) h->count[len[i]]++;
    h->count[0] = 0;
    offs[1] = 0;
    for (i = 1; i < 15; i++) offs[i + 1] = offs[i] + h->count[i];
    for (i = 0; i < n; i++) if (len[i]) h->sym[offs[len[i]]++] = i;
    return 0;
}
static int decsym(Inf *s, Huff *h)
{
    int code = 0, first = 0, index = 0;
    for (int len = 1; len < 16; len++) {
        int b = getbit(s); if (b < 0) return -1;
        code |= b;
        int count = h->count[len];
        if (code - count < first) return h->sym[index + (code - first)];
        index += count; first += count; first <<= 1; code <<= 1;
    }
    return -1;
}
static const unsigned short LBASE[29] = {3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258};
static const u8 LEXT[29] = {0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0};
static const unsigned short DBASE[30] = {1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577};
static const u8 DEXT[30] = {0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13};
static int codes(Inf *s, Huff *lh, Huff *dh)
{
    for (;;) {
        int sym = decsym(s, lh);
        if (sym < 0) return -1;
        if (sym < 256) { if (s->outlen >= s->outmax) return -1; s->out[s->outlen++] = sym; }
        else if (sym == 256) return 0;
        else {
            sym -= 257; if (sym >= 29) return -1;
            long len = getbits(s, LEXT[sym]); if (len < 0) return -1; len += LBASE[sym];
            int ds = decsym(s, dh); if (ds < 0 || ds >= 30) return -1;
            long dist = getbits(s, DEXT[ds]); if (dist < 0) return -1; dist += DBASE[ds];
            if ((ul)dist > s->outlen || s->outlen + len > s->outmax) return -1;
            for (long i = 0; i < len; i++) { s->out[s->outlen] = s->out[s->outlen - dist]; s->outlen++; }
        }
    }
}
static long inflate_raw(const u8 *in, ul inlen, u8 *out, ul outmax)
{
    static Huff lh, dh; static u8 lens[320];
    static const u8 ORD[19] = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};
    Inf s; s.in = in; s.inlen = inlen; s.pos = 0; s.bitbuf = 0; s.bitcnt = 0; s.out = out; s.outmax = outmax; s.outlen = 0;
    int last;
    do {
        last = getbit(&s); long type = getbits(&s, 2);
        if (last < 0 || type < 0) return -1;
        if (type == 0) {
            s.bitbuf = 0; s.bitcnt = 0;
            if (s.pos + 4 > s.inlen) return -1;
            ul len = s.in[s.pos] | (s.in[s.pos+1] << 8), nlen = s.in[s.pos+2] | (s.in[s.pos+3] << 8);
            s.pos += 4;
            if ((len ^ 0xffff) != nlen || s.pos + len > s.inlen || s.outlen + len > s.outmax) return -1;
            for (ul i = 0; i < len; i++) s.out[s.outlen++] = s.in[s.pos++];
        } else if (type == 1) {
            int i;
            for (i = 0; i < 144; i++) lens[i] = 8;
            for (; i < 256; i++) lens[i] = 9;
            for (; i < 280; i++) lens[i] = 7;
            for (; i < 288; i++) lens[i] = 8;
            build(&lh, lens, 288);
            for (i = 0; i < 30; i++) lens[i] = 5;
            build(&dh, lens, 30);
            if (codes(&s, &lh, &dh) < 0) return -1;
        } else if (type == 2) {
            long nlen = getbits(&s, 5), ndist = getbits(&s, 5), ncode = getbits(&s, 4);
            if (nlen < 0 || ndist < 0 || ncode < 0) return -1;
            nlen += 257; ndist += 1; ncode += 4;
            if (nlen > 286 || ndist > 30) return -1;
            int i;
            for (i = 0; i < 19; i++) lens[i] = 0;
            for (i = 0; i < ncode; i++) { long v = getbits(&s, 3); if (v < 0) return -1; lens[ORD[i]] = v; }
            build(&lh, lens, 19);
            i = 0;
            while (i < nlen + ndist) {
                int sym = decsym(&s, &lh); long rep; u8 val;
                if (sym < 0) return -1;
                if (sym < 16) { lens[i++] = sym; continue; }
                if (sym == 16) { if (!i) return -1; val = lens[i - 1]; rep = getbits(&s, 2); if (rep < 0) return -1; rep += 3; }
                else if (sym == 17) { val = 0; rep = getbits(&s, 3); if (rep < 0) return -1; rep += 3; }
                else { val = 0; rep = getbits(&s, 7); if (rep < 0) return -1; rep += 11; }
                if (i + rep > nlen + ndist) return -1;
                while (rep--) lens[i++] = val;
            }
            build(&lh, lens, nlen);
            build(&dh, lens + nlen, ndist);
            if (codes(&s, &lh, &dh) < 0) return -1;
        } else return -1;
    } while (!last);
    return (long)s.outlen;
}

/* ---- delta ---- */
static u32 rd32(const u8 *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((u32)p[3] << 24); }
static void wr32(u8 *p, u32 v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static void wr16(u8 *p, u32 v) { p[0] = v; p[1] = v >> 8; }
static void tohex(const u8 *raw, char *o) { for (int i = 0; i < 16; i++) { o[2*i] = "0123456789abcdef"[raw[i] >> 4]; o[2*i+1] = "0123456789abcdef"[raw[i] & 15]; } o[32] = 0; }
static ul loadinto(const char *path, u8 *buf, ul max)
{
    long fd = xopen(path, O_RDONLY, 0), r; ul n = 0;
    if (fd < 0) die("cannot open input");
    while ((r = xread(fd, buf + n, max - n)) > 0) n += r;
    xclose(fd);
    if (r < 0) die("read error");
    if (n == max) die("input too large");
    return n;
}
/* .lpd: "LPD1" src_len src_md5[16] out_len out_md5[16] comp payload_len payload
   payload (raw or deflate): nctrl, nctrl*(x,y,z) int32, diff_len, diff, extra_len, extra */
static int cmd_delta(const char *patch, const char *src, const char *dst)
{
    char want[33], got[33]; ul plen = loadinto(patch, dbuf, MAXDELTA); const u8 *pl; ul pll;
    if (plen < 52 || dbuf[0] != 'L' || dbuf[1] != 'P' || dbuf[2] != 'D' || dbuf[3] != '1') die("not an lpatch delta");
    u32 srclen = rd32(dbuf + 4), outlen = rd32(dbuf + 24), comp = rd32(dbuf + 44), paylen = rd32(dbuf + 48);
    if (52 + (ul)paylen != plen) die("delta truncated");
    if (outlen > MAXOUT) die("result too large");
    if (loadfile(src) < 0) { out("RESULT delta status=MISSING file="); out(src); out("\n"); return 1; }
    md5hex(fbuf, flen, got); tohex(dbuf + 8, want);
    if (flen != srclen || !seq(got, want)) {
        out("RESULT delta status=MISMATCH file="); out(src); out(" md5="); out(got); out(" want="); out(want); out("\n");
        return 1;
    }
    if (comp == 1) {
        long n = inflate_raw(dbuf + 52, paylen, ubuf, MAXRAW);
        if (n < 0) die("delta payload is corrupt");
        pl = ubuf; pll = n;
    } else if (comp == 0) { pl = dbuf + 52; pll = paylen; }
    else die("unknown delta compression");
    if (pll < 4) die("delta payload too short");
    u32 nctrl = rd32(pl); ul cpos = 4, need = 4 + (ul)nctrl * 12;
    if (need + 4 > pll) die("delta payload too short");
    u32 dlen = rd32(pl + need); const u8 *diff = pl + need + 4;
    if (need + 4 + dlen + 4 > pll) die("delta payload too short");
    u32 elen = rd32(diff + dlen); const u8 *extra = diff + dlen + 4;
    if (need + 8 + dlen + elen != pll) die("delta payload size mismatch");
    long oldpos = 0; ul newpos = 0, dpos = 0, epos = 0;
    for (u32 i = 0; i < nctrl; i++, cpos += 12) {
        long x = (int)rd32(pl + cpos), y = (int)rd32(pl + cpos + 4), z = (int)rd32(pl + cpos + 8);
        if (x < 0 || y < 0 || newpos + x + y > outlen || dpos + x > dlen || epos + y > elen) die("delta control is corrupt");
        for (long k = 0; k < x; k++) {
            u8 v = diff[dpos + k]; long o = oldpos + k;
            if (o >= 0 && (ul)o < flen) v += fbuf[o];
            obuf[newpos + k] = v;
        }
        newpos += x; oldpos += x; dpos += x;
        for (long k = 0; k < y; k++) obuf[newpos + k] = extra[epos + k];
        newpos += y; epos += y; oldpos += z;
    }
    if (newpos != outlen) die("delta did not produce the expected size");
    md5hex(obuf, outlen, got); tohex(dbuf + 28, want);
    if (!seq(got, want)) die("rebuilt file md5 mismatch - nothing written");
    if (writefile(dst, obuf, outlen) < 0) die("cannot write destination");
    if (loadfile(dst) < 0 || flen != outlen) die("written file did not verify");
    md5hex(fbuf, flen, got);
    if (!seq(got, want)) die("written file did not verify");
    out("RESULT delta status=BUILT file="); out(dst); out(" md5="); out(got); out("\n");
    return 0;
}

/* ---- zipadd: append classes.dex (stored) to an APK that has none ---- */
static u32 crc32(const u8 *p, ul n)
{
    u32 c = 0xffffffff;
    for (ul i = 0; i < n; i++) { c ^= p[i]; for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xedb88320 & (0 - (c & 1))); }
    return ~c;
}
static int cmd_zipadd(const char *apk, const char *dex, const char *dst)
{
    static const char NAME[] = "classes.dex"; const ul NL = 11;
    ul dlen = loadinto(dex, ubuf, MAXRAW);
    if (loadfile(apk) < 0) die("cannot read apk");
    if (flen < 22) die("apk too small");
    long e = -1;
    for (long i = flen - 22; i >= 0 && i >= (long)flen - 22 - 65535; i--)
        if (rd32(fbuf + i) == 0x06054b50) { e = i; break; }
    if (e < 0) die("apk: no end of central directory");
    u32 cnt = fbuf[e + 10] | (fbuf[e + 11] << 8), cdsize = rd32(fbuf + e + 12), cdoff = rd32(fbuf + e + 16);
    u32 clen = fbuf[e + 20] | (fbuf[e + 21] << 8);
    if ((ul)cdoff + cdsize != (ul)e || cnt >= 65535) die("apk: unsupported layout");
    for (ul p = cdoff, k = 0; k < cnt; k++) {   /* refuse if classes.dex already exists */
        if (rd32(fbuf + p) != 0x02014b50) die("apk: bad central directory");
        u32 n = fbuf[p + 28] | (fbuf[p + 29] << 8), x = fbuf[p + 30] | (fbuf[p + 31] << 8), c = fbuf[p + 32] | (fbuf[p + 33] << 8);
        if (n == NL && seq2((const char *)fbuf + p + 46, NAME, NL)) die("apk already contains classes.dex");
        p += 46 + n + x + c;
    }
    ul total = cdoff + 30 + NL + dlen + cdsize + 46 + NL + 22 + clen;
    if (total > MAXOUT) die("result too large");
    u32 crc = crc32(ubuf, dlen); const u32 TIME = 0, DATE = (46 << 9) | (10 << 5) | 18;   /* 2026-10-18 00:00 */
    u8 *o = obuf; ul q = 0;
    for (ul i = 0; i < cdoff; i++) o[q++] = fbuf[i];
    ul loc = q;
    wr32(o + q, 0x04034b50); wr16(o + q + 4, 10); wr16(o + q + 6, 0); wr16(o + q + 8, 0);
    wr16(o + q + 10, TIME); wr16(o + q + 12, DATE); wr32(o + q + 14, crc); wr32(o + q + 18, dlen); wr32(o + q + 22, dlen);
    wr16(o + q + 26, NL); wr16(o + q + 28, 0); q += 30;
    for (ul i = 0; i < NL; i++) o[q++] = NAME[i];
    for (ul i = 0; i < dlen; i++) o[q++] = ubuf[i];
    ul newcd = q;
    for (ul i = 0; i < cdsize; i++) o[q++] = fbuf[cdoff + i];
    wr32(o + q, 0x02014b50); wr16(o + q + 4, 10); wr16(o + q + 6, 10); wr16(o + q + 8, 0); wr16(o + q + 10, 0);
    wr16(o + q + 12, TIME); wr16(o + q + 14, DATE); wr32(o + q + 16, crc); wr32(o + q + 20, dlen); wr32(o + q + 24, dlen);
    wr16(o + q + 28, NL); wr16(o + q + 30, 0); wr16(o + q + 32, 0); wr16(o + q + 34, 0); wr16(o + q + 36, 0);
    wr32(o + q + 38, 0x81a40000); wr32(o + q + 42, loc); q += 46;
    for (ul i = 0; i < NL; i++) o[q++] = NAME[i];
    ul newcdsize = q - newcd;
    wr32(o + q, 0x06054b50); wr16(o + q + 4, 0); wr16(o + q + 6, 0); wr16(o + q + 8, cnt + 1); wr16(o + q + 10, cnt + 1);
    wr32(o + q + 12, newcdsize); wr32(o + q + 16, newcd); wr16(o + q + 20, clen); q += 22;
    for (ul i = 0; i < clen; i++) o[q++] = fbuf[e + 22 + i];
    if (q != total) die("internal: zip size");
    if (writefile(dst, obuf, q) < 0) die("cannot write destination");
    char m[33]; md5hex(obuf, q, m);
    out("RESULT zipadd status=BUILT file="); out(dst); out(" md5="); out(m); out("\n");
    return 0;
}

int main(int argc, char **argv)
{
    if (argc >= 5 && seq(argv[1], "delta")) return cmd_delta(argv[2], argv[3], argv[4]);
    if (argc >= 5 && seq(argv[1], "zipadd")) return cmd_zipadd(argv[2], argv[3], argv[4]);
    if (argc >= 3 && seq(argv[1], "check")) return cmd_check(argv[2], argc > 3 ? argv[3] : 0);
    if (argc >= 5 && seq(argv[1], "apply")) return cmd_apply(argv[2], argv[3], argv[4]);
    if (argc >= 3 && seq(argv[1], "md5")) {
        /* streamed, so files of any size work */
        char m[33]; Md5 c; long fd = xopen(argv[2], O_RDONLY, 0), r;
        if (fd < 0) die("cannot read file");
        md5init(&c);
        while ((r = xread(fd, fbuf, 1u << 20)) > 0) md5add(&c, fbuf, r);
        xclose(fd);
        if (r < 0) die("read error");
        md5end(&c, m); out(m); out("  "); out(argv[2]); out("\n");
        return 0;
    }
    err("usage: lpatch check <recipe> [file] | apply <recipe> <src> <dst> | md5 <file>\n"
        "       lpatch delta <patch.lpd> <src> <dst> | zipadd <src.apk> <classes.dex> <dst.apk>\n");
    return 2;
}

#ifndef HOST
__attribute__((naked, noreturn)) void _start(void)
{
    asm volatile("ldr r0,[sp]\n add r1,sp,#4\n bl main\n mov r7,#1\n svc 0\n");
}
#endif

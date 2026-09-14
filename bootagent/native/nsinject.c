/*
 * nsinject — write the Route A SD-card-emulation files into a SPECIFIC, already-
 * running process's own mount namespace (arm64 Android, freestanding, no libc).
 *
 * WHY: com.miko.launcher_app's own /storage is not the same tmpfs our root adb
 * shell sees — confirmed via /proc/<pid>/mounts, each zygote-specialized app gets
 * its own private mount of /storage. So mkdir/adb push against OUR /storage/sdcard1
 * is invisible to the launcher's own File.exists() check on the same nominal path.
 * Unlike neuterd (which targets init's namespace because /system/bin/reboot IS
 * shared there), this tool must enter the TARGET PROCESS's OWN namespace, because
 * /storage is deliberately NOT shared/inherited from init — it's freshly, privately
 * mounted per app.
 *
 * WHAT: reads a target pid (decimal ASCII) from /data/local/tmp/nsinject_pid,
 * setns()'s into /proc/<pid>/ns/mnt, then — now inside that process's own storage
 * view — mkdirs UPDATE_APP_INSTALL_DIR + miko3, and copies 1_miko3.l / payload.sh /
 * neuterd in from /data/local/tmp/ (a plain /data path, NOT namespace-isolated, so
 * it's visible identically from every namespace).
 *
 * Usage (from a root adb shell):
 *   pidof com.miko.launcher_app > /data/local/tmp/nsinject_pid
 *   ./nsinject
 *   # then trigger a FRESH launcher process (am force-stop) BEFORE this pid exits/
 *   # changes, or re-run against the new pid once you have it — this only helps
 *   # the exact pid it targets.
 *
 * Build (host): clang --target=aarch64-linux-android28 -O2 -nostdlib -static \
 *                 -ffreestanding -fuse-ld=/opt/homebrew/opt/lld/bin/ld.lld \
 *                 -Wl,-e,_start -o nsinject nsinject.c
 */

#define SYS_openat     56
#define SYS_close      57
#define SYS_read       63
#define SYS_write      64
#define SYS_setns      268
#define SYS_mkdirat    34
#define SYS_fchmodat   53
#define SYS_exit_group 94

#define AT_FDCWD    (-100)
#define O_RDONLY    0
#define O_WRONLY    1
#define O_CREAT     0100
#define O_TRUNC     01000
#define CLONE_NEWNS 0x00020000
#define MODE_0755   0x1ED

static long sys(long nr, long a, long b, long c, long d, long e) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory", "cc");
    return x0;
}

static unsigned long slen(const char *s) {
    unsigned long n = 0;
    while (s[n]) n++;
    return n;
}

static void scopy(char *dst, const char *src) {
    while ((*dst = *src)) { dst++; src++; }
}

static void sappend(char *dst, const char *src) {
    while (*dst) dst++;
    scopy(dst, src);
}

/* Read up to bufsz-1 bytes from path into buf, NUL-terminate, return bytes read (<0 on error). */
static long read_file(const char *path, char *buf, long bufsz) {
    long fd = sys(SYS_openat, AT_FDCWD, (long)path, O_RDONLY, 0, 0);
    if (fd < 0) return fd;
    long r = sys(SYS_read, fd, (long)buf, bufsz - 1, 0, 0);
    sys(SYS_close, fd, 0, 0, 0, 0);
    if (r < 0) return r;
    buf[r] = 0;
    return r;
}

/* Copy src (a plain, non-isolated /data path) to dst (inside the entered namespace). */
static void copy_file(const char *src, const char *dst) {
    long in = sys(SYS_openat, AT_FDCWD, (long)src, O_RDONLY, 0, 0);
    if (in < 0) return;
    long out = sys(SYS_openat, AT_FDCWD, (long)dst, O_WRONLY | O_CREAT | O_TRUNC, MODE_0755, 0);
    if (out < 0) { sys(SYS_close, in, 0, 0, 0, 0); return; }
    char buf[4096];
    for (;;) {
        long r = sys(SYS_read, in, (long)buf, sizeof(buf), 0, 0);
        if (r <= 0) break;
        long off = 0;
        while (off < r) {
            long w = sys(SYS_write, out, (long)(buf + off), r - off, 0, 0);
            if (w <= 0) break;
            off += w;
        }
    }
    sys(SYS_close, in, 0, 0, 0, 0);
    sys(SYS_close, out, 0, 0, 0, 0);
    sys(SYS_fchmodat, AT_FDCWD, (long)dst, MODE_0755, 0, 0);
}

void _start(void) {
    char pidbuf[32];
    long n = read_file("/data/local/tmp/nsinject_pid", pidbuf, sizeof(pidbuf));
    if (n <= 0) sys(SYS_exit_group, 1, 0, 0, 0, 0);

    /* parse decimal pid from pidbuf (stop at first non-digit, e.g. trailing newline) */
    char nsp[64];
    scopy(nsp, "/proc/");
    long i = 0;
    while (pidbuf[i] >= '0' && pidbuf[i] <= '9') i++;
    pidbuf[i] = 0;
    sappend(nsp, pidbuf);
    sappend(nsp, "/ns/mnt");

    long nsfd = sys(SYS_openat, AT_FDCWD, (long)nsp, O_RDONLY, 0, 0);
    if (nsfd < 0) sys(SYS_exit_group, 2, 0, 0, 0, 0);
    long rc = sys(SYS_setns, nsfd, CLONE_NEWNS, 0, 0, 0);
    sys(SYS_close, nsfd, 0, 0, 0, 0);
    if (rc < 0) sys(SYS_exit_group, 3, 0, 0, 0, 0);

    /* Now inside the TARGET process's own mount namespace. */
    sys(SYS_mkdirat, AT_FDCWD, (long)"/storage/sdcard1/UPDATE_APP_INSTALL_DIR", MODE_0755, 0, 0);
    sys(SYS_mkdirat, AT_FDCWD, (long)"/storage/sdcard1/miko3", MODE_0755, 0, 0);

    copy_file("/data/local/tmp/1_miko3.l",
              "/storage/sdcard1/UPDATE_APP_INSTALL_DIR/1_miko3.l");
    copy_file("/data/local/tmp/route_a_payload.sh",
              "/storage/sdcard1/miko3/payload.sh");
    copy_file("/data/local/tmp/route_a_neuterd",
              "/storage/sdcard1/miko3/neuterd");

    sys(SYS_exit_group, 0, 0, 0, 0, 0);
}

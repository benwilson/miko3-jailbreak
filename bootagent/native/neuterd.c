/*
 * neuterd — reboot-watchdog neuter daemon (arm64 Android, freestanding, no libc).
 *
 * VENDORED VERBATIM from ne3d-4-steve/miko3-adb-boot-agent (native/neuterd.c).
 * Reused unchanged under the plan's KTD1; only this attribution header was added.
 * See bootagent/README.md for provenance and the local build script build-neuterd.sh.
 */
/*
 * neuterd — OpenMiko reboot-watchdog neuter daemon (arm64 Android, freestanding, no libc).
 *
 * WHY: ServiceExam's SecurityMonitor greps `ps` for "adbd" every ~2s and, if found, execs
 * `su -> reboot`. To keep permanent adb alive we shadow /system/bin/reboot with a no-op. The
 * catch (empirically proven on this unit): a bind-mount done from an APP process lands in the
 * app's mount namespace, which ServiceExam (a zygote child) CANNOT see — so an app-side neuter
 * is useless against the watchdog. The mount MUST be made in init's GLOBAL mount namespace.
 *
 * WHAT: this tiny daemon (1) enters init's mount namespace via setns(/proc/1/ns/mnt), then
 * (2) loops forever: whenever /system/bin/reboot is the real ELF binary (i.e. the shadow is
 * missing — e.g. right after a boot, before we re-apply it), it writes a no-op script and
 * bind-mounts it over /system/bin/reboot. SELF-HEALING: if the mount is ever wiped it comes
 * back within a few seconds, in the namespace that actually matters.
 *
 * SELinux on this device is Permissive (ro.secure=0, selinux=disable), so setns+mount from a
 * root context are unrestricted. Run as root (the boot agent execs it via /system/bin/su).
 *
 * Build (host): clang --target=aarch64-linux-android28 -O2 -nostdlib -static -ffreestanding \
 *                 -fuse-ld=/opt/homebrew/opt/lld/bin/ld.lld -Wl,-e,_start -o neuterd neuterd.c
 * No NDK / libc required — only raw aarch64 Linux syscalls.
 */

/* aarch64 Linux syscall numbers */
#define SYS_openat     56
#define SYS_close      57
#define SYS_read       63
#define SYS_write      64
#define SYS_mount      40
#define SYS_setns      268
#define SYS_fchmodat   53
#define SYS_nanosleep  101
#define SYS_exit_group 94

#define AT_FDCWD    (-100)
#define O_RDONLY    0
#define O_WRONLY    1
#define O_CREAT     0100
#define O_TRUNC     01000
#define CLONE_NEWNS 0x00020000
#define MS_BIND     4096

/* One raw syscall: x8=nr, x0..x4=args, result in x0. Every call passes all five arg slots. */
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

static const char NR[]   = "/data/local/tmp/nr";
static const char REB[]  = "/system/bin/reboot";
static const char NSP[]  = "/proc/1/ns/mnt";
static const char NOOP[] = "#!/system/bin/sh\nexit 0\n";

/* File mode 0755 (rwxr-xr-x) as hex, to satisfy the repo's no-octal-literal rule. */
#define MODE_0755 0x1ED

/* Write the no-op reboot script and make it executable. */
static void write_noop(void) {
    long fd = sys(SYS_openat, AT_FDCWD, (long)NR, O_WRONLY | O_CREAT | O_TRUNC, MODE_0755, 0);
    if (fd < 0) return;
    sys(SYS_write, fd, (long)NOOP, (long)slen(NOOP), 0, 0);
    sys(SYS_close, fd, 0, 0, 0, 0);
    sys(SYS_fchmodat, AT_FDCWD, (long)NR, MODE_0755, 0, 0);
}

/* True if /system/bin/reboot is currently the REAL binary (ELF magic) rather than our no-op. */
static int reboot_is_real(void) {
    long fd = sys(SYS_openat, AT_FDCWD, (long)REB, O_RDONLY, 0, 0);
    if (fd < 0) return 1;  /* can't read -> assume it needs neutering (fail safe) */
    char buf[4] = {0, 0, 0, 0};
    long r = sys(SYS_read, fd, (long)buf, 4, 0, 0);
    sys(SYS_close, fd, 0, 0, 0, 0);
    if (r < 4) return 1;
    /* ELF: 0x7f 'E' 'L' 'F'. Our no-op starts with '#'. */
    return (unsigned char)buf[0] == 0x7f;
}

void _start(void) {
    /* 1) enter init's (global) mount namespace, once. */
    long nsfd = sys(SYS_openat, AT_FDCWD, (long)NSP, O_RDONLY, 0, 0);
    if (nsfd >= 0) {
        sys(SYS_setns, nsfd, CLONE_NEWNS, 0, 0, 0);
        sys(SYS_close, nsfd, 0, 0, 0, 0);
    }

    /* 2) self-heal loop: keep /system/bin/reboot shadowed by the no-op, forever. */
    struct { long sec; long nsec; } ts = {5, 0};
    for (;;) {
        if (reboot_is_real()) {
            write_noop();
            /* bind-mount the no-op over the real reboot, in the GLOBAL ns we joined above.
             * Guarded by reboot_is_real() so we never stack duplicate mounts. */
            sys(SYS_mount, (long)NR, (long)REB, 0 /*fstype*/, MS_BIND, 0 /*data*/);
        }
        sys(SYS_nanosleep, (long)&ts, 0, 0, 0, 0);
    }

    sys(SYS_exit_group, 0, 0, 0, 0, 0);
}

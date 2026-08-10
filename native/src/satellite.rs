use std::ffi::OsStr;
use std::os::unix::net::{SocketAddr, UnixListener};
#[cfg(target_os = "linux")]
use std::os::linux::net::SocketAddrExt;
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
use std::os::fd::{OwnedFd, RawFd, AsRawFd};
use std::process::{Command, Child, Stdio};
use rustix::io::{Errno, write, fcntl_setfd, FdFlags};
use rustix::fs::{Access, OFlags, access, lstat, mkdir, open, unlink};
use rustix::process::{Pid, getuid, getpid, test_kill_process};
use thiserror::Error;

/* Small parts of this code have been adapted from niri's implementation of
 * xwayland-satellite integration, as well as from Mutter's XWayland code
 * (which niri also borrowed from).
 *
 * niri (https://github.com/niri-wm/niri) is GPLv3 software.
 * Mutter (https://gitlab.gnome.org/GNOME/mutter) is GPLv2-or-later software.
 */

pub struct SatelliteState {
    display: i32,
    _handle: Child,
    _lock_guard: TmpFileGuard,
    _unix_guard: TmpFileGuard,
}

impl SatelliteState {
    pub fn get_display(&self) -> String {
        format!(":{0}", self.display)
    }
}

#[derive(Error, Debug)]
pub enum SatelliteError {
    #[error("Failed to execute xwayland-satellite command: {0}")]
    FailExecute(std::io::Error),
    #[error("xwayland-satellite was unexpectedly terminated by a signal")]
    Terminated,
    #[error("xwayland-satellite does not support -listenfd. Exit status: {0}")]
    NoListenFD(i32),
    #[error("Failed to create X11 directory. Error: {0}")]
    X11DirCreate(Errno),
    #[error("Failed checking tmp directory permissions. Error: {0}")]
    FailTmpDirPermCheck(Errno),
    #[error("Failed checking X11 directory permissions. Error: {0}")]
    FailX11DirPermCheck(Errno),
    #[error("X11 unix directory has the wrong permissions: {0}")]
    X11DirInvalidPerms(&'static str),
    #[error("Failed to write X11 lock file: {0}")]
    FailWriteLockFile(Errno),
    #[error("Failed to bind to the X11 unix socket: {0}")]
    FailBindUnixSocket(std::io::Error),
    #[error("Failed to bind to the X11 abstract socket: {0}")]
    FailBindAbstractSocket(std::io::Error),
    #[error("Failed to clone X11 socket: {0}")]
    FailCloneSocket(std::io::Error),
    #[error("Failed to set socket flags via fcntl")]
    FailSetFdFlags(Errno),
    #[error("Failed to create X11 display socket")]
    NoDisplay,
}

// Guard for a temporary file
// Deletes the file when dropped
struct TmpFileGuard(String);
impl Drop for TmpFileGuard {
    fn drop(&mut self) {
        let _ = unlink(&self.0);
    }
}

// 优先使用 Java 侧从 jar 解压出来的内嵌二进制；未设置时 fallback 到系统 PATH。
static XWS_BIN_PATH: std::sync::OnceLock<String> = std::sync::OnceLock::new();
const XWS_BINARY: &str = "xwayland-satellite";
const TMP_UNIX_DIR: &str = "/tmp";
const X11_TMP_UNIX_DIR: &str = "/tmp/.X11-unix";

/// 由 JNI 调用，设置内嵌 xwayland-satellite 二进制的绝对路径
pub fn set_binary_path(path: String) {
    let _ = XWS_BIN_PATH.set(path);
}

fn xws_binary() -> &'static str {
    XWS_BIN_PATH
        .get()
        .map(|s| s.as_str())
        .unwrap_or(XWS_BINARY)
}

fn test_satellite() -> Result<(), SatelliteError> {
    let mut command = Command::new(xws_binary());
    command
        .arg("--test-listenfd-support")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .env_remove("DISPLAY")
        .env_remove("WAYLAND_DISPLAY")
        .env_remove("LD_LIBRARY_PATH");

    let status = command.status().map_err(SatelliteError::FailExecute)?;
    if status.success() {
        Ok(())
    } else if let Some(code) = status.code() {
        Err(SatelliteError::NoListenFD(code))
    } else {
        Err(SatelliteError::Terminated)
    }
}

// From Mutter (src/wayland/meta-xwayland.c, commit 36ca36b4).
fn ensure_x11_unix_dir() -> Result<(), SatelliteError> {
    match mkdir(X11_TMP_UNIX_DIR, 0o1777.into()) {
        Ok(()) => Ok(()),
        Err(Errno::EXIST) => {
            check_x11_unix_perms()?;
            Ok(())
        }
        Err(err) => Err(SatelliteError::X11DirCreate(err)),
    }
}

// From Mutter (src/wayland/meta-xwayland.c, commit 36ca36b4).
fn check_x11_unix_perms() -> Result<(), SatelliteError> {
    // Query status of the /tmp and /tmp/.X11-unix directories
    let x11_tmp =
        lstat(X11_TMP_UNIX_DIR).map_err(SatelliteError::FailX11DirPermCheck)?;
    let tmp =
        lstat(TMP_UNIX_DIR).map_err(SatelliteError::FailTmpDirPermCheck)?;

    // The owner of the .X11-unix dir should either be the owner of the tmp dir
    // or the current user for security reasons.
    if x11_tmp.st_uid != tmp.st_uid && x11_tmp.st_uid != getuid().as_raw() {
        return Err(SatelliteError::X11DirInvalidPerms("wrong ownership"));
    }

    // The .X11-unix dir has to be writable
    access(X11_TMP_UNIX_DIR, Access::WRITE_OK)
        .map_err(|_| SatelliteError::X11DirInvalidPerms("not writeable"))?;

    // And it should have the sticky bit set
    if (x11_tmp.st_mode & 0o1000) != 0o1000 {
        return Err(SatelliteError::X11DirInvalidPerms("no sticky bit"));
    }

    Ok(())
}

fn maybe_cleanup_lockfile(path: &str) -> Result<(), ()> {
    let data = std::fs::read_to_string(path).map_err(|_| ())?;
    let pid = data.trim().parse::<u32>().map_err(|_| ())?;
    let pid = i32::try_from(pid).map_err(|_| ())?;
    let pid = Pid::from_raw(pid).ok_or(())?;

    if matches!(test_kill_process(pid), Err(Errno::SRCH)) {
        // No process matches the pid in the lockfile, delete it
        let _ = unlink(path);
        return Ok(());
    }

    Ok(())
}

// Attempts to acquire lock file for display number.
// Returns Ok(None) when the lock could not be acquired
// Returns Ok(Some(...)) when the lock was acquired successfully
// Returns Err(...) when an error occurred during writing
fn try_lock_display(dpy: i32) -> Result<Option<TmpFileGuard>, SatelliteError> {
    let lock_path = format!("{TMP_UNIX_DIR}/.X{dpy}-lock");

    // Cleanup lockfile if it exists but isn't used anymore
    let _ = maybe_cleanup_lockfile(&lock_path);

    // Create display lock
    let flags =
        OFlags::WRONLY | OFlags::CLOEXEC | OFlags::CREATE | OFlags::EXCL;
    let lock_fd = match open(&lock_path, flags, 0o444.into()) {
        Ok(fd) => fd,
        Err(_) => {
            // Lock could not be acquired
            return Ok(None)
        }
    };
    // Create guard immediately after open(...) so the lockfile is deleted when
    // the guard is dropped.
    let guard = TmpFileGuard(lock_path);

    let data = format!("{:>10}\n", getpid().as_raw_nonzero());
    write(&lock_fd, data.as_bytes())
        .map_err(SatelliteError::FailWriteLockFile)?;
    drop(lock_fd);

    Ok(Some(guard))
}

struct X11Sockets {
    unix_fd: OwnedFd,
    unix_guard: TmpFileGuard,
    abstract_fd: OwnedFd,
}

fn try_open_sockets(dpy: i32) -> Result<X11Sockets, SatelliteError> {
    let socket_path = format!("{X11_TMP_UNIX_DIR}/X{dpy}");

    /* Create abstract socket */
    let abstract_addr = SocketAddr::from_abstract_name(&socket_path).unwrap();
    let abstract_socket = UnixListener::bind_addr(&abstract_addr)
        .map_err(SatelliteError::FailBindAbstractSocket)?;
    let abstract_fd = OwnedFd::from(abstract_socket);

    /* Create unix socket */
    let _ = unlink(&socket_path); // Delete potential existing socket
    let unix_addr = SocketAddr::from_pathname(&socket_path).unwrap();
    let unix_socket = UnixListener::bind_addr(&unix_addr)
        .map_err(SatelliteError::FailBindUnixSocket)?;
    // Create temp file guard now that the socket was created so it now
    // automatically gets deleted when dropped
    let unix_guard = TmpFileGuard(socket_path);
    let unix_fd = OwnedFd::from(unix_socket);

    Ok(X11Sockets {
        unix_fd,
        unix_guard,
        abstract_fd,
    })
}

fn try_invoke_xws(
    wayland_display: &OsStr,
    display: i32,
    listenfds: &[RawFd]
) -> Result<Child, SatelliteError> {
    let mut command = Command::new(xws_binary());
    command
        .stdin(Stdio::null())
        // stderr/stdout 不再丢进 null：xwayland-satellite 连接 compositor 失败时
        // 会打印错误（如 "failed to connect to wayland"），透传到日志方便诊断。
        .stdout(Stdio::from(std::fs::OpenOptions::new()
            .create(true).append(true)
            .open("/tmp/waylandcraft-satellite.log").unwrap_or_else(|_| {
                // 打开失败时退化成 /dev/null，绝不让 spawn 失败
                std::fs::File::open("/dev/null").expect("cannot open /dev/null")
            })))
        .stderr(Stdio::from(std::fs::OpenOptions::new()
            .create(true).append(true)
            .open("/tmp/waylandcraft-satellite.log").unwrap_or_else(|_| {
                std::fs::File::open("/dev/null").expect("cannot open /dev/null")
            })))
        .env("WAYLAND_DISPLAY", wayland_display)
        .env_remove("DISPLAY")
        .env_remove("LD_LIBRARY_PATH");

    command.arg(format!(":{display}"));
    for fd in listenfds {
        command.arg("-listenfd").arg(fd.to_string());
    }

    command.spawn().map_err(SatelliteError::FailExecute)
}

// Copy an owned file descriptor, clear any flags (notably CLOEXEC!) and return
// the copied file descriptor.
//
// Clearing the flags is important because otherwise CLOEXEC will be set and the
// file descriptor will not be correctly passed to the child process
// (meaning xwayland-satellite)
fn copy_listenfd(
    listenfd: &OwnedFd
) -> Result<(OwnedFd, RawFd), SatelliteError> {
    let listenfd_copy = listenfd.try_clone()
        .map_err(SatelliteError::FailCloneSocket)?;
    fcntl_setfd(&listenfd_copy, FdFlags::empty())
        .map_err(SatelliteError::FailSetFdFlags)?;
    let raw = listenfd_copy.as_raw_fd();
    Ok((listenfd_copy, raw))
}

pub fn start_satellite(
    wayland_display: &OsStr,
) -> Result<SatelliteState, SatelliteError> {
    ensure_x11_unix_dir()?;
    test_satellite()?;

    for dpy in 1..=32 {
        let lock_guard = match try_lock_display(dpy)? {
            Some(g) => g,
            None => continue
        };

        let sockets = try_open_sockets(dpy)?;
        let (unix_fd_copy, unix_fd_raw) = copy_listenfd(&sockets.unix_fd)?;
        let (abs_fd_copy, abs_fd_raw) = copy_listenfd(&sockets.abstract_fd)?;

        let mut handle = try_invoke_xws(wayland_display, dpy, &[
            unix_fd_raw,
            abs_fd_raw,
        ])?;

        // 存活检查：xwayland-satellite 要连上 compositor（WAYLAND_DISPLAY=wayland-1）
        // 才会真正监听 X socket。如果它连不上（socket 不存在 / compositor 没起来），
        // 会立刻退出——此时 try_invoke_xws 的 spawn 仍然"成功"，导致调用方误以为
        // 拿到了 DISPLAY=:2，实际 X server 不存在（crashreporter 打不开 :2）。
        // 这里等一小会儿，确认子进程还活着才返回成功。
        let mut alive = false;
        for _ in 0..20 {
            match handle.try_wait() {
                Ok(Some(status)) => {
                    eprintln!("[waylandcraft] xwayland-satellite exited early: {status}");
                    break;
                }
                Ok(None) => {
                    alive = true;
                    break;
                }
                Err(_) => break,
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        if !alive {
            // 子进程死了，这个 display 不可用，继续试下一个
            let _ = handle.kill();
            let _ = handle.wait();
            continue;
        }

        // Only drop file descriptor after passing it to xwayland-satellite
        drop(unix_fd_copy);
        drop(abs_fd_copy);

        return Ok(SatelliteState {
            display: dpy,
            _handle: handle,
            _lock_guard: lock_guard,
            _unix_guard: sockets.unix_guard,
        });
    }

    Err(SatelliteError::NoDisplay)
}

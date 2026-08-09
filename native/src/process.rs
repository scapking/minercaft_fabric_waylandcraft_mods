use std::ffi::OsString;
use std::process::{Command, Stdio};
use std::io::Write;

fn log(msg: &str) {
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true).append(true)
        .open("/tmp/waylandcraft-launch.log")
    {
        let _ = writeln!(f, "{}", msg);
    }
}

/// 检测应用类型
fn detect_app_type(cmd: &str, args: &[String]) -> &'static str {
    let cmd_lower = cmd.to_lowercase();
    
    if cmd_lower.ends_with("/flatpak") || cmd_lower == "flatpak" {
        return "flatpak";
    }
    if cmd_lower.contains("wine") || cmd_lower.contains("proton") {
        return "wine";
    }
    if cmd_lower.contains("electron") || cmd_lower.contains("code") 
        || cmd_lower.contains("discord") || cmd_lower.contains("slack")
        || cmd_lower.contains("clash-verge") || cmd_lower.contains("clash-nyanpasu") {
        return "electron";
    }
    if cmd_lower.contains("gnome-") || cmd_lower.contains("nautilus") 
        || cmd_lower.contains("totem") || cmd_lower.contains("evince")
        || cmd_lower.contains("gedit") || cmd_lower.contains("eog")
        || cmd_lower.contains("thunderbird") || cmd_lower.contains("transmission") {
        return "gtk";
    }
    if cmd_lower.contains("dolphin") || cmd_lower.contains("kate") 
        || cmd_lower.contains("okular") || cmd_lower.contains("konsole")
        || cmd_lower.contains("vlc") || cmd_lower.contains("obs")
        || cmd_lower.contains("qbittorrent") || cmd_lower.contains("kdenlive") {
        return "qt";
    }
    if cmd_lower.contains("firefox") || cmd_lower.contains("chromium") {
        return "browser";
    }
    "native"
}

/// 根据类型构建环境变量列表
fn build_env_list(app_type: &str, wayland_display: &str, display: &str) -> Vec<(String, String)> {
    let mut env_list = vec![
        ("WAYLAND_DISPLAY".to_string(), wayland_display.to_string()),
        ("DISPLAY".to_string(), display.to_string()),
    ];
    
    match app_type {
        "flatpak" => {
            env_list.clear();
        }
        "wine" => {}
        "electron" => {
            env_list.push(("ELECTRON_OZONE_PLATFORM_HINT".to_string(), "auto".to_string()));
            env_list.push(("OZONE_PLATFORM".to_string(), "wayland".to_string()));
            env_list.push(("GDK_BACKEND".to_string(), "wayland".to_string()));
        }
        "gtk" => {
            env_list.push(("GDK_BACKEND".to_string(), "wayland".to_string()));
        }
        "qt" => {
            env_list.push(("QT_QPA_PLATFORM".to_string(), "wayland".to_string()));
        }
        _ => {
            env_list.push(("GDK_BACKEND".to_string(), "wayland".to_string()));
            env_list.push(("QT_QPA_PLATFORM".to_string(), "wayland".to_string()));
            env_list.push(("ELECTRON_OZONE_PLATFORM_HINT".to_string(), "auto".to_string()));
        }
    }
    
    env_list
}

/// 为 flatpak 注入 --env= 和 --filesystem= 参数
///
/// 修复说明：
/// 1. 之前注入 `--filesystem={runtime_dir}/{wayland_display}`（单个 socket 文件的宿主绝对路径），
///    但 flatpak 沙箱内有自己的 /run/user/<uid>，宿主 XDG_RUNTIME_DIR 默认不可见，
///    所以该路径在沙箱内不可达 → 注入无效。正确做法是用 flatpak 的特殊值
///    `--filesystem=xdg-run` 暴露整个宿主 XDG_RUNTIME_DIR。
/// 2. 之前找不到 app_id 时 insert_pos 保持 0，会把选项插到最前面（甚至插到 `run` 之前），
///    导致 flatpak 报错。现在找不到就追加到末尾。
fn inject_flatpak_env(args: &mut Vec<String>, wayland_display: &str, display: &str) {
    // 找到 app_id 的位置（run 之后第一个非选项参数）
    let mut insert_pos = None;
    let mut found_run = false;
    for (i, arg) in args.iter().enumerate() {
        if arg == "run" {
            found_run = true;
            continue;
        }
        if found_run && !arg.starts_with('-') {
            insert_pos = Some(i);
            break;
        }
    }
    let insert_pos = insert_pos.unwrap_or(args.len());

    // flatpak run 的选项必须放在 run 之后、app_id 之前
    let mut opts = vec![
        // 暴露宿主 XDG_RUNTIME_DIR，让沙箱内能看到 wayland socket
        "--filesystem=xdg-run".to_string(),
        format!("--env=WAYLAND_DISPLAY={}", wayland_display),
        "--env=GDK_BACKEND=wayland".to_string(),
        "--env=QT_QPA_PLATFORM=wayland".to_string(),
        "--env=ELECTRON_OZONE_PLATFORM_HINT=auto".to_string(),
    ];
    // X11-only flatpak apps need DISPLAY from xwayland-satellite
    if !display.is_empty() {
        opts.push(format!("--env=DISPLAY={}", display));
    }

    for (offset, opt) in opts.iter().enumerate() {
        log(&format!("[flatpak] injecting: {}", opt));
        args.insert(insert_pos + offset, opt.clone());
    }
}

/// 写一个调试脚本，启动前先 dump 环境变量到文件
fn write_env_dump_script(cmd: &str, args: &[String], env_list: &[(String, String)]) -> String {
    let dump_file = format!("/tmp/wlc-env-{}.log", std::process::id());
    let mut script = String::new();
    script.push_str("#!/bin/bash\n");
    script.push_str(&format!("echo '=== ENV DUMP for {}' > {}\n", cmd, dump_file));
    script.push_str(&format!("echo 'WAYLAND_DISPLAY='$WAYLAND_DISPLAY >> {}\n", dump_file));
    script.push_str(&format!("echo 'DISPLAY='$DISPLAY >> {}\n", dump_file));
    script.push_str(&format!("echo 'GDK_BACKEND='$GDK_BACKEND >> {}\n", dump_file));
    script.push_str(&format!("echo 'QT_QPA_PLATFORM='$QT_QPA_PLATFORM >> {}\n", dump_file));
    script.push_str(&format!("echo 'OZONE_PLATFORM='$OZONE_PLATFORM >> {}\n", dump_file));
    script.push_str(&format!("echo 'ELECTRON_OZONE_PLATFORM_HINT='$ELECTRON_OZONE_PLATFORM_HINT >> {}\n", dump_file));
    script.push_str(&format!("echo 'XDG_RUNTIME_DIR='$XDG_RUNTIME_DIR >> {}\n", dump_file));
    script.push_str(&format!("env | sort >> {}\n", dump_file));
    script.push_str(&format!("echo '=== END ENV DUMP' >> {}\n", dump_file));
    // 然后 exec 真正的程序
    script.push_str(&format!("exec {} {}\n", shell_quote(cmd), args.iter().map(|a| shell_quote(a)).collect::<Vec<_>>().join(" ")));
    
    let script_path = "/tmp/wlc-spawn-wrapper.sh".to_string();
    if let Ok(mut f) = std::fs::File::create(&script_path) {
        let _ = f.write_all(script.as_bytes());
    }
    let _ = std::fs::set_permissions(&script_path, std::os::unix::fs::PermissionsExt::from_mode(0o755));
    script_path
}

fn shell_quote(s: &str) -> String {
    if s.is_empty() {
        return "''".to_string();
    }
    if s.chars().all(|c| c.is_alphanumeric() || c == '-' || c == '_' || c == '.' || c == '/' || c == '=') {
        return s.to_string();
    }
    format!("'{}'", s.replace('\'', "'\\''"))
}

pub fn spawn(
    cmd: String,
    args: Vec<String>,
    env: Vec<(OsString, OsString)>,
    wayland_display: String,
    _runtime_dir: String,
) -> Result<(), ()> {
    log("========================================");
    log(&format!("[spawn] cmd={}", cmd));
    log(&format!("[spawn] args={:?}", args));
    log(&format!("[spawn] WAYLAND_DISPLAY target={}", wayland_display));
    log(&format!("[spawn] current env: WAYLAND_DISPLAY={:?}, DISPLAY={:?}", 
        std::env::var("WAYLAND_DISPLAY").unwrap_or_default(),
        std::env::var("DISPLAY").unwrap_or_default()));

    // 从 bridge 传入的 env 中提取 DISPLAY（xwayland-satellite 提供时）
    let display = env.iter()
        .find(|(k, _)| k == "DISPLAY")
        .map(|(_, v)| v.to_string_lossy().to_string())
        .unwrap_or_default();
    log(&format!("[spawn] DISPLAY from bridge={:?}", display));

    let app_type = detect_app_type(&cmd, &args);
    log(&format!("[detect] type={}", app_type));
    
    let runtime_dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/run/user/1000".to_string());
    
    let (final_cmd, final_args) = if app_type == "flatpak" {
        let mut flatpak_args = args.clone();
        inject_flatpak_env(&mut flatpak_args, &wayland_display, &display);
        log(&format!("[flatpak] final args={:?}", flatpak_args));
        (cmd.clone(), flatpak_args)
    } else {
        // 非 flatpak: 用 bash -c 包装，强制设置环境变量后 exec
        let env_list = build_env_list(app_type, &wayland_display, &display);
        log(&format!("[env] will set: {:?}", env_list));
        
        // 构建 bash 命令: export VAR=val; exec cmd args...
        let mut bash_cmd = String::new();
        for (k, v) in &env_list {
            bash_cmd.push_str(&format!("export {}={}; ", k, shell_quote(v)));
        }
        // XDG_RUNTIME_DIR 已在外部获取
        bash_cmd.push_str(&format!("export XDG_RUNTIME_DIR={}; ", shell_quote(&runtime_dir)));
        
        bash_cmd.push_str("env > /tmp/wlc-env-dump.log; ");
        bash_cmd.push_str(&format!("exec {} {}", shell_quote(&cmd), args.iter().map(|a| shell_quote(a)).collect::<Vec<_>>().join(" ")));
        
        log(&format!("[bash] cmd: {}", bash_cmd));
        ("/bin/bash".to_string(), vec!["-c".to_string(), bash_cmd])
    };
    
    log(&format!("[spawn] final_cmd={}, final_args={:?}", final_cmd, final_args));
    
    let mut command = Command::new(&final_cmd);
    command
        .args(&final_args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        // 统一给子进程设置正确的 Wayland 环境
        // （flatpak 运行器需要宿主 XDG_RUNTIME_DIR 才能用 --filesystem=xdg-run 构建沙箱）
        .env("WAYLAND_DISPLAY", &wayland_display)
        .env("XDG_RUNTIME_DIR", &runtime_dir)
        .env("DISPLAY", &display);

    match unsafe { libc::fork() } {
        0 => {
            unsafe { libc::setsid(); }
            match command.spawn() {
                Ok(child) => log(&format!("[spawn] child pid={}", child.id())),
                Err(e) => log(&format!("[spawn] FAILED: '{}': {}", final_cmd, e)),
            }
            unsafe { libc::_exit(0); }
        }
        -1 => {
            log("[spawn] fork() failed!");
            return Err(());
        }
        _ => {}
    }

    unsafe { libc::wait(std::ptr::null_mut()); }
    log(&format!("[spawn] done for cmd={}", cmd));
    Ok(())
}

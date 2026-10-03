//! Windows uses an AppContainer with no capabilities and a kill-on-close Job.
//! Only the disposable task workspace receives this unique AppContainer SID.
use crate::Request;
use serde_json::{json, Value};
use std::{
    ffi::c_void,
    fs::File,
    mem::{size_of, zeroed},
    os::windows::{
        ffi::OsStrExt,
        io::{FromRawHandle, RawHandle},
    },
    path::{Path, PathBuf},
    ptr::{null, null_mut},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

type Handle = *mut c_void;
type Sid = *mut c_void;
#[repr(C)]
struct SecurityAttributes {
    length: u32,
    descriptor: *mut c_void,
    inherit: i32,
}
#[repr(C)]
struct StartupInfo {
    cb: u32,
    reserved: *mut u16,
    desktop: *mut u16,
    title: *mut u16,
    x: u32,
    y: u32,
    x_size: u32,
    y_size: u32,
    x_chars: u32,
    y_chars: u32,
    fill: u32,
    flags: u32,
    show: u16,
    reserved_bytes: u16,
    reserved_ptr: *mut u8,
    input: Handle,
    output: Handle,
    error: Handle,
}
#[repr(C)]
struct StartupInfoEx {
    info: StartupInfo,
    attributes: *mut c_void,
}
#[repr(C)]
struct ProcessInformation {
    process: Handle,
    thread: Handle,
    pid: u32,
    tid: u32,
}
#[repr(C)]
struct SecurityCapabilities {
    sid: Sid,
    capabilities: *mut c_void,
    count: u32,
    reserved: u32,
}
#[repr(C)]
struct Trustee {
    multiple: *mut c_void,
    operation: u32,
    form: u32,
    kind: u32,
    name: *mut u16,
}
#[repr(C)]
struct ExplicitAccess {
    permissions: u32,
    mode: u32,
    inheritance: u32,
    trustee: Trustee,
}
#[repr(C)]
struct BasicLimits {
    process_time: i64,
    job_time: i64,
    flags: u32,
    min_working: usize,
    max_working: usize,
    active_processes: u32,
    affinity: usize,
    priority: u32,
    scheduling: u32,
}
#[repr(C)]
struct IoCounters {
    read_operations: u64,
    write_operations: u64,
    other_operations: u64,
    read_bytes: u64,
    write_bytes: u64,
    other_bytes: u64,
}
#[repr(C)]
struct ExtendedLimits {
    basic: BasicLimits,
    io: IoCounters,
    process_memory: usize,
    job_memory: usize,
    peak_process: usize,
    peak_job: usize,
}

#[link(name = "kernel32")]
extern "system" {
    fn CloseHandle(handle: Handle) -> i32;
    fn CreateFileW(
        name: *const u16,
        access: u32,
        share: u32,
        attributes: *const c_void,
        creation: u32,
        flags: u32,
        template: Handle,
    ) -> Handle;
    fn LocalFree(memory: *mut c_void) -> *mut c_void;
    fn GetLastError() -> u32;
    fn CreatePipe(
        read: *mut Handle,
        write: *mut Handle,
        attributes: *const SecurityAttributes,
        size: u32,
    ) -> i32;
    fn SetHandleInformation(handle: Handle, mask: u32, flags: u32) -> i32;
    fn InitializeProcThreadAttributeList(
        list: *mut c_void,
        count: u32,
        flags: u32,
        size: *mut usize,
    ) -> i32;
    fn UpdateProcThreadAttribute(
        list: *mut c_void,
        flags: u32,
        attribute: usize,
        value: *const c_void,
        size: usize,
        previous: *mut c_void,
        returned: *mut usize,
    ) -> i32;
    fn DeleteProcThreadAttributeList(list: *mut c_void);
    fn CreateProcessW(
        application: *const u16,
        command: *mut u16,
        process_attributes: *const c_void,
        thread_attributes: *const c_void,
        inherit: i32,
        flags: u32,
        environment: *const c_void,
        directory: *const u16,
        startup: *const StartupInfo,
        information: *mut ProcessInformation,
    ) -> i32;
    fn CreateJobObjectW(attributes: *const c_void, name: *const u16) -> Handle;
    fn SetInformationJobObject(job: Handle, class: u32, info: *const c_void, size: u32) -> i32;
    fn AssignProcessToJobObject(job: Handle, process: Handle) -> i32;
    fn TerminateJobObject(job: Handle, code: u32) -> i32;
    fn TerminateProcess(process: Handle, code: u32) -> i32;
    fn ResumeThread(thread: Handle) -> u32;
    fn WaitForSingleObject(handle: Handle, milliseconds: u32) -> u32;
    fn GetExitCodeProcess(process: Handle, code: *mut u32) -> i32;
    fn QueryInformationJobObject(
        job: Handle,
        class: u32,
        info: *mut c_void,
        size: u32,
        returned: *mut u32,
    ) -> i32;
    fn GetWindowsDirectoryW(buffer: *mut u16, size: u32) -> u32;
}
#[link(name = "userenv")]
extern "system" {
    fn CreateAppContainerProfile(
        name: *const u16,
        display: *const u16,
        description: *const u16,
        capabilities: *const c_void,
        count: u32,
        sid: *mut Sid,
    ) -> i32;
    fn DeleteAppContainerProfile(name: *const u16) -> i32;
}
#[link(name = "advapi32")]
extern "system" {
    fn FreeSid(sid: Sid) -> *mut c_void;
    fn GetSecurityInfo(
        handle: Handle,
        object_type: u32,
        security_info: u32,
        owner: *mut Sid,
        group: *mut Sid,
        dacl: *mut *mut c_void,
        sacl: *mut *mut c_void,
        descriptor: *mut *mut c_void,
    ) -> u32;
    fn SetSecurityInfo(
        handle: Handle,
        object_type: u32,
        security_info: u32,
        owner: Sid,
        group: Sid,
        dacl: *mut c_void,
        sacl: *mut c_void,
    ) -> u32;
    fn SetEntriesInAclW(
        count: u32,
        entries: *const ExplicitAccess,
        old: *const c_void,
        new: *mut *mut c_void,
    ) -> u32;
    fn ConvertStringSecurityDescriptorToSecurityDescriptorW(
        text: *const u16,
        revision: u32,
        descriptor: *mut *mut c_void,
        size: *mut u32,
    ) -> i32;
    fn GetSecurityDescriptorSacl(
        descriptor: *const c_void,
        present: *mut i32,
        sacl: *mut *mut c_void,
        defaulted: *mut i32,
    ) -> i32;
}

fn wide(value: impl AsRef<std::ffi::OsStr>) -> Vec<u16> {
    value.as_ref().encode_wide().chain(Some(0)).collect()
}
fn error(context: &str) -> String {
    format!("{context} (Windows error {})", unsafe { GetLastError() })
}
struct OwnedHandle(Handle);
impl Drop for OwnedHandle {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
}
impl OwnedHandle {
    fn new(raw: Handle) -> Result<Self, String> {
        if raw.is_null() || raw as isize == -1 {
            Err(error("Invalid native handle"))
        } else {
            Ok(Self(raw))
        }
    }
    fn into_file(mut self) -> File {
        let handle = self.0;
        self.0 = null_mut();
        unsafe { File::from_raw_handle(handle as RawHandle) }
    }
}

struct Profile {
    name: Vec<u16>,
    sid: Sid,
    deleted: bool,
}
impl Profile {
    fn new() -> Result<Self, String> {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let name = wide(format!("Dongran.Sandbox.{}.{}", std::process::id(), nonce));
        let mut sid = null_mut();
        let result = unsafe {
            CreateAppContainerProfile(
                name.as_ptr(),
                name.as_ptr(),
                name.as_ptr(),
                null(),
                0,
                &mut sid,
            )
        };
        if result < 0 {
            return Err(format!(
                "AppContainer profile creation failed (HRESULT 0x{:08x}); isolation is unavailable",
                result as u32
            ));
        }
        Ok(Self {
            name,
            sid,
            deleted: false,
        })
    }
    fn delete(&mut self) -> Result<(), String> {
        if self.deleted {
            return Ok(());
        }
        let result = unsafe { DeleteAppContainerProfile(self.name.as_ptr()) };
        if result < 0 {
            return Err(format!(
                "AppContainer cleanup failed (HRESULT 0x{:08x})",
                result as u32
            ));
        }
        self.deleted = true;
        Ok(())
    }
}
impl Drop for Profile {
    fn drop(&mut self) {
        if let Err(error) = self.delete() {
            eprintln!("{error}");
        }
        unsafe {
            FreeSid(self.sid);
        }
    }
}

struct SavedAcl {
    handle: OwnedHandle,
    descriptor: *mut c_void,
    dacl: *mut c_void,
    sacl: *mut c_void,
    restored: bool,
}
impl SavedAcl {
    fn restore(&mut self) -> Result<(), String> {
        if self.restored {
            return Ok(());
        }
        let result = unsafe {
            SetSecurityInfo(
                self.handle.0,
                1,
                0x14,
                null_mut(),
                null_mut(),
                self.dacl,
                self.sacl,
            )
        };
        if result != 0 {
            return Err(format!(
                "Restore disposable workspace ACL: Windows error {result}"
            ));
        }
        self.restored = true;
        Ok(())
    }
}
impl Drop for SavedAcl {
    fn drop(&mut self) {
        if let Err(error) = self.restore() {
            eprintln!("{error}");
        }
        unsafe {
            LocalFree(self.descriptor);
        }
    }
}
fn grant_workspace(root: &Path, sid: Sid) -> Result<Vec<SavedAcl>, String> {
    let mut paths = Vec::new();
    fn collect(path: &Path, paths: &mut Vec<PathBuf>) -> Result<(), String> {
        if crate::is_link(path)? {
            return Err("Reparse points are forbidden in the task workspace".into());
        }
        paths.push(path.into());
        if path.is_dir() {
            for entry in std::fs::read_dir(path).map_err(|e| e.to_string())? {
                collect(&entry.map_err(|e| e.to_string())?.path(), paths)?;
            }
        }
        Ok(())
    }
    collect(root, &mut paths)?;
    let mut low_descriptor = null_mut();
    if unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            wide("S:(ML;OICI;NW;;;LW)").as_ptr(),
            1,
            &mut low_descriptor,
            null_mut(),
        )
    } == 0
    {
        return Err(error("Create low-integrity label"));
    }
    let mut low_sacl = null_mut();
    let mut present = 0;
    let mut defaulted = 0;
    if unsafe {
        GetSecurityDescriptorSacl(low_descriptor, &mut present, &mut low_sacl, &mut defaulted)
    } == 0
    {
        unsafe {
            LocalFree(low_descriptor);
        }
        return Err(error("Read low-integrity label"));
    }
    let result = (|| {
        // Snapshot all descriptors before propagating inheritable changes from any parent.
        let mut saved = Vec::new();
        for path in &paths {
            // Retain object handles (including SHARE_DELETE) so cleanup can never follow a
            // link or junction created by the untrusted command in place of this path.
            let handle = OwnedHandle::new(unsafe {
                CreateFileW(
                    wide(path).as_ptr(),
                    0x000E0000,
                    7,
                    null(),
                    3,
                    0x02200000,
                    null_mut(),
                )
            })?;
            let mut descriptor = null_mut();
            let mut dacl = null_mut();
            let mut sacl = null_mut();
            let code = unsafe {
                GetSecurityInfo(
                    handle.0,
                    1,
                    0x14,
                    null_mut(),
                    null_mut(),
                    &mut dacl,
                    &mut sacl,
                    &mut descriptor,
                )
            };
            if code != 0 {
                return Err(format!(
                    "Read workspace security descriptor: Windows error {code}"
                ));
            }
            saved.push(SavedAcl {
                handle,
                descriptor,
                dacl,
                sacl,
                restored: false,
            });
        }
        for (path, original) in paths.iter().zip(saved.iter_mut()) {
            let entry = ExplicitAccess {
                permissions: 0x001301bf,
                mode: 1,
                inheritance: if path.is_dir() { 3 } else { 0 },
                trustee: Trustee {
                    multiple: null_mut(),
                    operation: 0,
                    form: 0,
                    kind: 5,
                    name: sid as *mut u16,
                },
            };
            let mut updated = null_mut();
            let code = unsafe { SetEntriesInAclW(1, &entry, original.dacl, &mut updated) };
            if code != 0 {
                return Err(format!("Grant task workspace access: Windows error {code}"));
            }
            let code = unsafe {
                SetSecurityInfo(
                    original.handle.0,
                    1,
                    0x14,
                    null_mut(),
                    null_mut(),
                    updated,
                    low_sacl,
                )
            };
            unsafe {
                LocalFree(updated);
            }
            if code != 0 {
                return Err(format!(
                    "Apply isolated task permissions: Windows error {code}"
                ));
            }
        }
        Ok(saved)
    })();
    unsafe {
        LocalFree(low_descriptor);
    }
    result
}

fn pipe() -> Result<(OwnedHandle, OwnedHandle), String> {
    let attributes = SecurityAttributes {
        length: size_of::<SecurityAttributes>() as u32,
        descriptor: null_mut(),
        inherit: 1,
    };
    let (mut read, mut write) = (null_mut(), null_mut());
    if unsafe { CreatePipe(&mut read, &mut write, &attributes, 0) } == 0 {
        return Err(error("Create output pipe"));
    }
    let read = OwnedHandle::new(read)?;
    let write = OwnedHandle::new(write)?;
    if unsafe { SetHandleInformation(read.0, 1, 0) } == 0 {
        return Err(error("Protect output pipe"));
    }
    Ok((read, write))
}
fn quote(arg: &str) -> String {
    if !arg.is_empty() && !arg.chars().any(|c| c.is_whitespace() || c == '"') {
        return arg.into();
    }
    let mut result = String::from("\"");
    let mut slashes = 0;
    for c in arg.chars() {
        if c == '\\' {
            slashes += 1;
            continue;
        }
        if c == '"' {
            result.extend(std::iter::repeat('\\').take(slashes * 2 + 1));
        } else {
            result.extend(std::iter::repeat('\\').take(slashes));
        }
        slashes = 0;
        result.push(c);
    }
    result.extend(std::iter::repeat('\\').take(slashes * 2));
    result.push('"');
    result
}
fn local_path(path: &Path) -> String {
    let value = path.to_string_lossy();
    value.strip_prefix(r"\\?\").unwrap_or(&value).to_owned()
}
fn windows_directory() -> Result<PathBuf, String> {
    let mut buffer = [0u16; 32768];
    let len = unsafe { GetWindowsDirectoryW(buffer.as_mut_ptr(), buffer.len() as u32) } as usize;
    if len == 0 || len >= buffer.len() {
        return Err(error("Find Windows directory"));
    }
    Ok(PathBuf::from(String::from_utf16_lossy(&buffer[..len])))
}
fn environment(request: &Request) -> Result<Vec<u16>, String> {
    let system = windows_directory()?;
    let temp = request.workspace.join(".sandbox-tmp");
    std::fs::create_dir_all(&temp).map_err(|e| e.to_string())?;
    let mut paths = vec![
        system.join("System32"),
        system.clone(),
        system.join("System32/WindowsPowerShell/v1.0"),
    ];
    for root in &request.read_roots {
        paths.push(root.join("bin"));
        paths.push(root.clone());
    }
    let path = paths
        .iter()
        .map(|p| local_path(p).replace('/', "\\"))
        .collect::<Vec<_>>()
        .join(";");
    let mut env = vec![
        (
            "DONGRAN_SANDBOX_WORKSPACE".to_owned(),
            local_path(&request.workspace),
        ),
        ("PATH".to_owned(), path),
        // Restrict discovery to Windows PowerShell's own modules. Runner profiles
        // and missing per-user registry entries must not change cmdlet availability.
        (
            "PSModulePath".to_owned(),
            local_path(&system.join("System32/WindowsPowerShell/v1.0/Modules")),
        ),
        (
            "SystemRoot".to_owned(),
            system.to_string_lossy().into_owned(),
        ),
        ("WINDIR".to_owned(), system.to_string_lossy().into_owned()),
        ("TEMP".to_owned(), local_path(&temp)),
        ("TMP".to_owned(), local_path(&temp)),
        ("USERPROFILE".to_owned(), local_path(&temp)),
        ("HOME".to_owned(), local_path(&temp)),
        ("APPDATA".to_owned(), local_path(&temp)),
        ("LOCALAPPDATA".to_owned(), local_path(&temp)),
        (
            "COMSPEC".to_owned(),
            system
                .join("System32/cmd.exe")
                .to_string_lossy()
                .into_owned(),
        ),
    ];
    for root in &request.read_roots {
        if root.join("bin/java.exe").is_file() {
            env.push(("JAVA_HOME".to_owned(), local_path(root)));
            break;
        }
    }
    env.sort_by_key(|(k, _)| k.to_uppercase());
    let mut block = Vec::new();
    for (k, v) in env {
        block.extend(wide(format!("{k}={v}")));
    }
    block.push(0);
    Ok(block)
}

pub struct Process {
    process: OwnedHandle,
    job: OwnedHandle,
    pid: u32,
    stdout: Option<File>,
    stderr: Option<File>,
    acls: Vec<SavedAcl>,
    profile: Profile,
}
impl Process {
    pub fn backend(&self) -> &'static str {
        "windows-appcontainer"
    }
    pub fn id(&self) -> u32 {
        self.pid
    }
    pub fn take_output(&mut self) -> (File, File) {
        (self.stdout.take().unwrap(), self.stderr.take().unwrap())
    }
    pub fn try_wait(&mut self) -> Result<Option<i32>, String> {
        let result = unsafe { WaitForSingleObject(self.process.0, 0) };
        if result == 258 {
            return Ok(None);
        }
        if result != 0 {
            return Err(error("Wait for isolated process"));
        }
        let mut code = 0;
        if unsafe { GetExitCodeProcess(self.process.0, &mut code) } == 0 {
            return Err(error("Read isolated process status"));
        }
        Ok(Some(code as i32))
    }
    pub fn cleanup(&mut self) -> Result<(), String> {
        self.kill()?;
        while let Some(mut acl) = self.acls.pop() {
            acl.restore()?;
        }
        self.profile.delete()
    }
    pub fn kill(&mut self) -> Result<(), String> {
        if unsafe { TerminateJobObject(self.job.0, 130) } == 0 {
            return Err(error("Terminate process job"));
        }
        let start = Instant::now();
        loop {
            let mut accounting = [0u64; 6];
            if unsafe {
                QueryInformationJobObject(
                    self.job.0,
                    1,
                    accounting.as_mut_ptr() as *mut c_void,
                    48,
                    null_mut(),
                )
            } == 0
            {
                return Err(error("Verify process job cleanup"));
            }
            let active = (accounting[5] & 0xffffffff) as u32;
            if active == 0 {
                return Ok(());
            }
            if start.elapsed() >= Duration::from_secs(3) {
                return Err(
                    "Isolated process tree did not terminate; workspace must not be synchronized"
                        .into(),
                );
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }
}
impl Drop for Process {
    fn drop(&mut self) {
        if let Err(error) = self.cleanup() {
            eprintln!("{error}");
        }
        while self.acls.pop().is_some() {}
    }
}

pub fn spawn(request: &Request) -> Result<Process, String> {
    let environment = environment(request)?;
    let profile = Profile::new()?;
    let acls = grant_workspace(&request.workspace, profile.sid)?;
    let job = OwnedHandle::new(unsafe { CreateJobObjectW(null(), null()) })?;
    let mut limits: ExtendedLimits = unsafe { zeroed() };
    limits.basic.flags = 0x2000 | 0x200 | 0x8 | 0x400; // kill-on-close, job memory, active process, die-on-unhandled-exception
    limits.basic.active_processes = request.max_processes;
    limits.job_memory = request.memory_mb as usize * 1024 * 1024;
    if unsafe {
        SetInformationJobObject(
            job.0,
            9,
            &limits as *const _ as *const c_void,
            size_of::<ExtendedLimits>() as u32,
        )
    } == 0
    {
        return Err(error("Configure job limits"));
    }
    let (out_read, out_write) = pipe()?;
    let (err_read, err_write) = pipe()?;
    // A pipe with its write end closed gives command stdin immediate EOF.
    let (in_read, in_write) = pipe()?;
    if unsafe { SetHandleInformation(in_read.0, 1, 1) } == 0 {
        return Err(error("Configure command input"));
    }
    drop(in_write);
    let mut bytes = 0;
    unsafe {
        InitializeProcThreadAttributeList(null_mut(), 2, 0, &mut bytes);
    }
    let mut attributes = vec![0usize; (bytes + size_of::<usize>() - 1) / size_of::<usize>()];
    let list = attributes.as_mut_ptr() as *mut c_void;
    if unsafe { InitializeProcThreadAttributeList(list, 2, 0, &mut bytes) } == 0 {
        return Err(error("Initialize process attributes"));
    }
    struct Attributes(*mut c_void);
    impl Drop for Attributes {
        fn drop(&mut self) {
            unsafe {
                DeleteProcThreadAttributeList(self.0);
            }
        }
    }
    let _attributes = Attributes(list);
    let security = SecurityCapabilities {
        sid: profile.sid,
        capabilities: null_mut(),
        count: 0,
        reserved: 0,
    };
    if unsafe {
        UpdateProcThreadAttribute(
            list,
            0,
            0x00020009,
            &security as *const _ as *const c_void,
            size_of::<SecurityCapabilities>(),
            null_mut(),
            null_mut(),
        )
    } == 0
    {
        return Err(error("Apply AppContainer security capabilities"));
    }
    let handles = [in_read.0, out_write.0, err_write.0];
    if unsafe {
        UpdateProcThreadAttribute(
            list,
            0,
            0x00020002,
            handles.as_ptr() as *const c_void,
            size_of_val(&handles),
            null_mut(),
            null_mut(),
        )
    } == 0
    {
        return Err(error("Restrict inherited handles"));
    }
    let mut startup: StartupInfoEx = unsafe { zeroed() };
    startup.info.cb = size_of::<StartupInfoEx>() as u32;
    startup.info.flags = 0x100 | 1;
    startup.info.show = 0;
    startup.info.input = in_read.0;
    startup.info.output = out_write.0;
    startup.info.error = err_write.0;
    startup.attributes = list;
    let mut information: ProcessInformation = unsafe { zeroed() };
    let mut args = request.argv.clone();
    args[0] = args[0].replace('/', "\\");
    let command_text = if Path::new(&args[0])
        .file_name()
        .map(|s| s.to_string_lossy().eq_ignore_ascii_case("cmd.exe"))
        .unwrap_or(false)
        && args.len() >= 3
        && args[args.len() - 2].eq_ignore_ascii_case("/c")
    {
        // cmd.exe parses its own command tail rather than the MSVC argv grammar.
        // /s removes exactly the enclosing quotes; quotes inside the script remain literal.
        format!(
            "{} /s {} \"{}\"",
            quote(&args[0]),
            args[1..args.len() - 1]
                .iter()
                .map(|s| quote(s))
                .collect::<Vec<_>>()
                .join(" "),
            args.last().unwrap()
        )
    } else {
        args.iter().map(|s| quote(s)).collect::<Vec<_>>().join(" ")
    };
    let mut command = wide(command_text);
    let success = unsafe {
        CreateProcessW(
            wide(&args[0]).as_ptr(),
            command.as_mut_ptr(),
            null(),
            null(),
            1,
            0x00080000 | 0x00000400 | 0x00000004 | 0x08000000,
            environment.as_ptr() as *const c_void,
            wide(local_path(&request.workspace)).as_ptr(),
            &startup.info,
            &mut information,
        )
    };
    if success == 0 {
        return Err(format!("{}. The executable and its runtime must be readable by AppContainer; no host fallback was attempted",error("Start AppContainer process")));
    }
    let process = OwnedHandle::new(information.process)?;
    let thread = OwnedHandle::new(information.thread)?;
    if unsafe { AssignProcessToJobObject(job.0, process.0) } == 0 {
        unsafe {
            TerminateProcess(process.0, 125);
        }
        return Err(error("Attach process to job"));
    }
    if unsafe { ResumeThread(thread.0) } == u32::MAX {
        unsafe {
            TerminateJobObject(job.0, 125);
        }
        return Err(error("Resume isolated process"));
    }
    drop(out_write);
    drop(err_write);
    drop(in_read);
    Ok(Process {
        process,
        job,
        pid: information.pid,
        stdout: Some(out_read.into_file()),
        stderr: Some(err_read.into_file()),
        acls,
        profile,
    })
}

pub fn probe() -> Value {
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let workspace = std::env::temp_dir().join(format!(
        "dongran-sandbox-probe-{}-{nonce}",
        std::process::id()
    ));
    let result = (|| {
        std::fs::create_dir_all(&workspace).map_err(|e| e.to_string())?;
        let request = Request {
            protocol_version: 1,
            execution_id: "probe".into(),
            workspace: workspace.clone(),
            argv: vec![
                windows_directory()?
                    .join("System32")
                    .join("cmd.exe")
                    .to_string_lossy()
                    .into_owned(),
                "/d".into(),
                "/c".into(),
                "exit /b 0".into(),
            ],
            timeout_seconds: 5,
            memory_mb: 128,
            max_processes: 4,
            network: "deny".into(),
            read_roots: vec![],
        };
        let mut process = spawn(&request)?;
        let start = Instant::now();
        loop {
            if let Some(code) = process.try_wait()? {
                if code == 0 {
                    process.cleanup()?;
                    return Ok(());
                }
                return Err(format!("AppContainer startup self-test exited with {code}"));
            }
            if start.elapsed() > Duration::from_secs(5) {
                return Err("AppContainer startup self-test timed out".into());
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    })();
    let _ = std::fs::remove_dir_all(&workspace);
    match result {
        Ok(()) => {
            json!({"protocolVersion":1,"available":true,"platform":"windows","backend":"windows-appcontainer","reason":"AppContainer startup verified; zero network capabilities; per-task workspace ACL; Job Object process/memory limits","networkIsolation":true})
        }
        Err(reason) => {
            json!({"protocolVersion":1,"available":false,"platform":"windows","backend":"windows-appcontainer","reason":reason,"networkIsolation":false})
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn quotes_windows_arguments() {
        assert_eq!(quote(""), "\"\"");
        assert_eq!(quote("plain"), "plain");
        assert_eq!(quote("a b"), "\"a b\"");
        assert_eq!(quote("a\"b"), "\"a\\\"b\"");
        assert_eq!(quote("a b\\"), "\"a b\\\\\"");
    }
}

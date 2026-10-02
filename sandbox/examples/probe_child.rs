//! OS integration-test payload. It is copied into a disposable task workspace.
use std::{
    io::Write,
    net::{SocketAddr, TcpStream},
    path::PathBuf,
    process::Command,
    thread,
    time::Duration,
};
fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(|s| s.as_str()) {
        Some("boundaries") => {
            let outside = PathBuf::from(&args[2]);
            assert!(
                std::fs::read_to_string(&outside).is_err(),
                "outside private file was readable"
            );
            assert!(
                std::fs::write(&outside, b"tampered").is_err(),
                "outside file was writable"
            );
            assert!(
                std::env::var("DONGRAN_PRIVATE_SENTINEL").is_err(),
                "host environment secret leaked"
            );
            assert_eq!(
                std::fs::read_to_string("input.txt").unwrap(),
                "workspace-readable"
            );
            std::fs::write("output.txt", b"workspace-written").unwrap();
            std::fs::remove_file("input.txt").unwrap();
            let address: SocketAddr = args[3].parse().unwrap();
            assert!(
                TcpStream::connect_timeout(&address, Duration::from_secs(1)).is_err(),
                "network was reachable"
            );
            println!("BOUNDARIES_OK");
        }
        Some("background") => {
            let marker = args[2].clone();
            let child = Command::new(std::env::current_exe().unwrap())
                .args(["delayed", &marker])
                .spawn()
                .unwrap();
            println!("CHILD_PID={}", child.id());
            if args.get(3).map(|s| s == "wait").unwrap_or(false) {
                thread::sleep(Duration::from_secs(20));
            }
        }
        Some("delayed") => {
            thread::sleep(Duration::from_secs(3));
            std::fs::write(&args[2], b"descendant escaped").unwrap();
        }
        Some("flood") => {
            let bytes = [b'x'; 8192];
            loop {
                if std::io::stdout().write_all(&bytes).is_err() {
                    break;
                }
            }
        }
        Some("sleep") => thread::sleep(Duration::from_secs(20)),
        _ => std::process::exit(2),
    }
}

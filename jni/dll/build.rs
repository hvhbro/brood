// build.rs: проверка, что сгенерированный payload на месте.
// (См. комментарий ниже про pack_agent.py и build_dll.bat.)

fn main() {
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=src/logger.rs");
    println!("cargo:rerun-if-changed=src/memory_utils.rs");
    println!("cargo:rerun-if-changed=src/offsets.rs");
    println!("cargo:rerun-if-changed=src/jvm_offsets.rs");
    println!("cargo:rerun-if-changed=src/jni_types.rs");
    println!("cargo:rerun-if-changed=build.rs");

    let payload = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src/agent_payload.rs");
    if !payload.is_file() {
        panic!(
            "missing {} — сначала build_dll.bat (он вызывает tools/pack_agent.py)",
            payload.display()
        );
    }
}

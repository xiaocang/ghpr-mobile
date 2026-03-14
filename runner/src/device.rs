use std::fs;
use std::path::PathBuf;

fn data_dir() -> Result<PathBuf, String> {
    let home = dirs::home_dir().ok_or("cannot determine home directory")?;
    let dir = home.join(".ghpr-runner");
    fs::create_dir_all(&dir).map_err(|e| format!("cannot create data dir: {e}"))?;
    Ok(dir)
}

fn read_or_create(filename: &str) -> Result<String, String> {
    let path = data_dir()?.join(filename);
    if path.exists() {
        fs::read_to_string(&path).map_err(|e| format!("cannot read {filename}: {e}"))
    } else {
        let value = uuid::Uuid::new_v4().to_string();
        fs::write(&path, &value).map_err(|e| format!("cannot write {filename}: {e}"))?;
        Ok(value)
    }
}

pub fn device_id() -> Result<String, String> {
    read_or_create("device_id")
}

pub fn pairing_token() -> Result<String, String> {
    read_or_create("pairing_token")
}

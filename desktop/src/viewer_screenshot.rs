use anyhow::{Context, Result, anyhow, bail};
use image::{ColorType, ImageFormat};
use std::{
    env, fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

pub fn save_rgba(width: u32, height: u32, rgba: &[u8]) -> Result<PathBuf> {
    if width == 0 || height == 0 {
        bail!("viewport has no drawable size");
    }
    let expected = width as usize * height as usize * 4;
    if rgba.len() != expected {
        bail!("viewport RGBA buffer has {} bytes; expected {expected}", rgba.len());
    }

    let directory = pictures_directory()?.join("Minesport");
    fs::create_dir_all(&directory)
        .with_context(|| format!("create screenshot directory {}", directory.display()))?;

    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| anyhow!("system clock is before Unix epoch: {error}"))?
        .as_secs();
    let mut path = directory.join(format!("Minesport-3D-{seconds}.png"));
    for suffix in 2u32.. {
        if !path.exists() {
            break;
        }
        path = directory.join(format!("Minesport-3D-{seconds}-{suffix}.png"));
    }

    image::save_buffer_with_format(
        &path,
        rgba,
        width,
        height,
        ColorType::Rgba8.into(),
        ImageFormat::Png,
    )
    .with_context(|| format!("save 3D screenshot {}", path.display()))?;
    Ok(path)
}

pub fn pictures_directory() -> Result<PathBuf> {
    #[cfg(windows)]
    {
        let home = env::var_os("USERPROFILE")
            .or_else(|| env::var_os("HOME"))
            .ok_or_else(|| anyhow!("could not resolve the Windows user profile directory"))?;
        return Ok(PathBuf::from(home).join("Pictures"));
    }

    #[cfg(not(windows))]
    {
        let home = env::var_os("HOME")
            .ok_or_else(|| anyhow!("could not resolve the user home directory"))?;
        Ok(PathBuf::from(home).join("Pictures"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_rgba_length_before_touching_disk() {
        let error = save_rgba(2, 2, &[0; 15]).unwrap_err().to_string();
        assert!(error.contains("expected 16"));
    }

    #[test]
    fn pictures_path_ends_in_pictures() {
        if let Ok(path) = pictures_directory() {
            assert_eq!(path.file_name().and_then(|value| value.to_str()), Some("Pictures"));
        }
    }
}

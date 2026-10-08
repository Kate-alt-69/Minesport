//! Render deterministic UI fixtures without launching Java, Minecraft, or reading saves.
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{path::PathBuf, rc::Rc, time::Duration};

slint::include_modules!();

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let output = PathBuf::from(std::env::args_os().nth(1).unwrap_or_else(|| "ux-snapshots".into()));
    std::fs::create_dir_all(&output)?;
    let ui = MainWindow::new()?;
    ui.window().set_size(slint::PhysicalSize::new(1280, 800));
    ui.show()?;
    let mut stage = 0;
    let timer = slint::Timer::default();
    let weak = ui.as_weak();
    let picker = LauncherWorldPicker::new()?;
    let picker_for_timer = picker.clone_strong();
    let snapshot_output = output.clone();
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(350), move || {
        let ui = weak.upgrade().unwrap();
        let name = match stage {
            0 => "01-welcome",
            1 => "02-selection",
            2 => "03-export",
            3 => "04-export-error",
            4 => "05-export-saved",
            5 => "06-settings",
            6 => "07-small-window",
            7 => "08-world-picker",
            8 => "09-picker-empty",
            _ => { slint::quit_event_loop().unwrap(); return; }
        };
        let window = if stage >= 7 { picker_for_timer.window() } else { ui.window() };
        let pixels = window.take_snapshot().expect("UI screenshot must render");
        image::save_buffer(snapshot_output.join(format!("{name}.png")), pixels.as_bytes(), pixels.width(), pixels.height(), image::ColorType::Rgba8)
            .expect("UI screenshot must save");
        match stage {
            0 => {
                ui.set_world_path("/Minecraft/saves/Pine Valley".into());
                ui.set_world_name("Pine Valley".into());
                ui.set_minecraft_version("1.21.10".into());
                ui.set_loader_type("Fabric".into());
                ui.set_engine_ready(true);
                ui.set_engine_status("READY".into());
                ui.set_min_x(0); ui.set_max_x(127);
                ui.set_min_y(0); ui.set_max_y(95);
                ui.set_min_z(0); ui.set_max_z(127);
                ui.set_runtime_cache_status("Model data ready".into());
                let mut map = slint::SharedPixelBuffer::<slint::Rgb8Pixel>::new(256, 256);
                for (index, pixel) in map.make_mut_slice().iter_mut().enumerate() {
                    let x = index % 256;
                    let z = index / 256;
                    let river = x.abs_diff(110 + z / 4) < 14;
                    let shade = ((x / 8 + z / 8) % 4 * 12) as u8;
                    *pixel = if river { slint::Rgb8Pixel::new(49, 100, 136) }
                        else { slint::Rgb8Pixel::new(61 + shade, 104 + shade, 58 + shade) };
                }
                ui.set_map_image(slint::Image::from_rgb8(map));
                ui.set_map_available(true);
            }
            1 => ui.set_active_activity(1),
            2 => {
                ui.set_task_title("EXPORT FAILED".into());
                ui.set_task_detail("The output folder is unavailable. Choose another folder and try again.".into());
            }
            3 => {
                ui.set_task_title("EXPORT COMPLETE".into());
                ui.set_task_completion_detail("/Minesport_Exports/Pine_Valley.gltf · 12,480 blocks · 31,206 faces".into());
            }
            4 => ui.set_settings_visible(true),
            5 => {
                ui.window().dispatch_event(slint::platform::WindowEvent::KeyPressed {
                    text: slint::platform::Key::Escape.into(),
                });
                assert!(!ui.get_settings_visible(), "Escape must close settings");
                ui.set_active_activity(0);
                ui.window().set_size(slint::PhysicalSize::new(960, 640));
            }
            6 => {
                picker_for_timer.set_breadcrumb("Prism Launcher › Instance › World".into());
                picker_for_timer.set_rows(ModelRc::from(Rc::new(VecModel::from(vec![
                    PickerRow { icon_kind: 2, title: "Pine Valley".into(), subtitle: "Fabric · Minecraft 1.21.10 · Saved today".into() },
                    PickerRow { icon_kind: 2, title: "Creative Workshop".into(), subtitle: "Fabric · Minecraft 1.21.10 · Saved yesterday".into() },
                ]))));
                picker_for_timer.set_selected_index(0);
                picker_for_timer.set_can_use(true);
                picker_for_timer.set_can_back(true);
                picker_for_timer.show().unwrap();
                ui.hide().unwrap();
            }
            7 => {
                picker_for_timer.set_rows(ModelRc::default());
                picker_for_timer.set_can_use(false);
                picker_for_timer.set_empty_message("No world matches this search. Clear the search or browse to a world folder.".into());
            }
            _ => {}
        }
        stage += 1;
    });
    slint::run_event_loop()?;
    assert_eq!(std::fs::read_dir(output)?.filter_map(Result::ok)
        .filter(|entry| entry.path().extension().is_some_and(|extension| extension == "png"))
        .count(), 9, "Every UX state must produce a screenshot");
    Ok(())
}

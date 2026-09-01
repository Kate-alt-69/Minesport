use crate::{
    launcher,
    world_context::{self, WorldContext, WorldDiscovery},
};
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};
use std::{
    cell::RefCell,
    rc::Rc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

slint::slint! {
    import { Button, LineEdit, ListView } from "std-widgets.slint";

    export struct PickerRow {
        icon-kind: int,
        title: string,
        subtitle: string,
    }

    export component LauncherWorldPicker inherits Window {
        title: "Select Minecraft World";
        preferred-width: 760px;
        preferred-height: 560px;
        min-width: 640px;
        min-height: 460px;
        background: #11171d;

        in property <[PickerRow]> rows;
        in-out property <string> breadcrumb: "Launcher";
        in-out property <string> search-text: "";
        in-out property <string> empty-message: "Nothing found here.";
        in-out property <int> selected-index: -1;
        in property <bool> can-back: false;
        in property <bool> can-use: false;
        in property <bool> browse-fallback: false;
        callback activate(int);
        callback go-back();
        callback use-selected();
        callback browse-folder();
        callback cancel();
        callback search-edited(string);

        VerticalLayout {
            padding: 14px;
            spacing: 8px;

            Text {
                text: root.breadcrumb;
                color: #dce7ef;
                font-size: 14px;
                font-weight: 700;
            }
            LineEdit {
                text <=> root.search-text;
                placeholder-text: "Search launcher, instance or world…";
                edited(value) => { root.search-edited(value); }
            }

            Rectangle {
                vertical-stretch: 1;
                background: #0c1116;
                border-color: #25313b;
                border-width: 1px;
                border-radius: 5px;
                clip: true;

                if root.rows.length == 0: Text {
                    x: 28px;
                    width: parent.width - 56px;
                    text: root.empty-message;
                    color: #71808d;
                    horizontal-alignment: center;
                    vertical-alignment: center;
                    wrap: word-wrap;
                }

                ListView {
                    x: 4px;
                    y: 4px;
                    width: parent.width - 8px;
                    height: parent.height - 8px;
                    for row[index] in root.rows: Rectangle {
                        height: 62px;
                        width: parent.width;
                        background: root.selected-index == index ? #243748 : hover.has-hover ? #18232c : transparent;
                        border-radius: 4px;

                        if row.icon-kind == 0: Image {
                            x: 13px;
                            y: (parent.height - 24px) / 2;
                            width: 24px;
                            height: 24px;
                            source: @image-url("../assets/fyne-theme-icons/computer.svg");
                            image-fit: contain;
                            colorize: root.selected-index == index ? #dff1fc : #8fa2b4;
                            accessible-role: none;
                        }
                        if row.icon-kind == 1: Image {
                            x: 13px;
                            y: (parent.height - 24px) / 2;
                            width: 24px;
                            height: 24px;
                            source: @image-url("../assets/fyne-theme-icons/settings.svg");
                            image-fit: contain;
                            colorize: root.selected-index == index ? #dff1fc : #8fa2b4;
                            accessible-role: none;
                        }
                        if row.icon-kind == 2: Image {
                            x: 13px;
                            y: (parent.height - 24px) / 2;
                            width: 24px;
                            height: 24px;
                            source: @image-url("../assets/fyne-theme-icons/file.svg");
                            image-fit: contain;
                            colorize: root.selected-index == index ? #dff1fc : #8fa2b4;
                            accessible-role: none;
                        }
                        Text {
                            x: 50px;
                            y: 8px;
                            width: parent.width - 62px;
                            height: 22px;
                            text: row.title;
                            color: root.selected-index == index ? #eef7fd : #d4e0e8;
                            font-size: 11px;
                            font-weight: 700;
                            vertical-alignment: center;
                            overflow: elide;
                        }
                        Text {
                            x: 50px;
                            y: 31px;
                            width: parent.width - 62px;
                            height: 21px;
                            text: row.subtitle;
                            color: root.selected-index == index ? #b9cbd7 : #8798a5;
                            font-size: 10px;
                            font-italic: true;
                            vertical-alignment: center;
                            overflow: elide;
                        }
                        hover := TouchArea {
                            mouse-cursor: pointer;
                            clicked => {
                                root.selected-index = index;
                                root.activate(index);
                            }
                        }
                    }
                }
            }

            HorizontalLayout {
                spacing: 7px;
                Button {
                    text: "Back";
                    enabled: root.can-back;
                    clicked => { root.go-back(); }
                }
                if root.browse-fallback: Button {
                    text: "Browse folder…";
                    clicked => { root.browse-folder(); }
                }
                Rectangle { horizontal-stretch: 1; }
                Button {
                    text: "Use world";
                    primary: true;
                    enabled: root.can-use;
                    clicked => { root.use-selected(); }
                }
                Button {
                    text: "Cancel";
                    clicked => { root.cancel(); }
                }
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Step {
    Launcher,
    Instance,
    World,
}

#[derive(Debug, Clone, Copy)]
enum Choice {
    Launcher(usize),
    Instance(usize),
    World(usize),
}

struct PickerState {
    launchers: Vec<launcher::CatalogEntry>,
    step: Step,
    launcher_index: Option<usize>,
    instance_index: Option<usize>,
    query: String,
    visible: Vec<Choice>,
    selected_world: Option<usize>,
}

thread_local! {
    static ACTIVE_PICKER: RefCell<Option<LauncherWorldPicker>> = const { RefCell::new(None) };
}

pub fn show<F, B>(discovery: WorldDiscovery, on_select: F, on_browse: B)
where
    F: Fn(WorldContext) + 'static,
    B: Fn() + 'static,
{
    close_active();
    let on_select: Rc<dyn Fn(WorldContext)> = Rc::new(on_select);
    let on_browse: Rc<dyn Fn()> = Rc::new(on_browse);
    let picker = match LauncherWorldPicker::new() {
        Ok(value) => value,
        Err(_) => {
            on_browse();
            return;
        }
    };

    let launchers = discovery.into_catalog();
    picker.set_browse_fallback(launchers.is_empty());
    let state = Rc::new(RefCell::new(PickerState {
        launchers,
        step: Step::Launcher,
        launcher_index: None,
        instance_index: None,
        query: String::new(),
        visible: Vec::new(),
        selected_world: None,
    }));

    refresh(&picker, &state);

    let weak = picker.as_weak();
    let activate_state = state.clone();
    picker.on_activate(move |index| {
        let Some(picker) = weak.upgrade() else {
            return;
        };
        let choice = activate_state
            .borrow()
            .visible
            .get(index.max(0) as usize)
            .copied();
        let Some(choice) = choice else {
            return;
        };

        let mut navigated = false;
        {
            let mut state = activate_state.borrow_mut();
            match choice {
                Choice::Launcher(launcher_index) => {
                    state.launcher_index = Some(launcher_index);
                    state.instance_index = None;
                    state.selected_world = None;
                    state.step = Step::Instance;
                    state.query.clear();
                    navigated = true;
                }
                Choice::Instance(instance_index) => {
                    state.instance_index = Some(instance_index);
                    state.selected_world = None;
                    state.step = Step::World;
                    state.query.clear();
                    navigated = true;
                }
                Choice::World(world_index) => {
                    state.selected_world = Some(world_index);
                }
            }
        }
        if navigated {
            picker.set_search_text(SharedString::default());
        }
        refresh(&picker, &activate_state);
    });

    let weak = picker.as_weak();
    let search_state = state.clone();
    picker.on_search_edited(move |value| {
        let Some(picker) = weak.upgrade() else {
            return;
        };
        search_state.borrow_mut().query = value.to_string();
        refresh(&picker, &search_state);
    });

    let weak = picker.as_weak();
    let back_state = state.clone();
    picker.on_go_back(move || {
        let Some(picker) = weak.upgrade() else {
            return;
        };
        {
            let mut state = back_state.borrow_mut();
            match state.step {
                Step::Launcher => return,
                Step::Instance => {
                    state.step = Step::Launcher;
                    state.launcher_index = None;
                }
                Step::World => {
                    state.step = Step::Instance;
                    state.instance_index = None;
                }
            }
            state.selected_world = None;
            state.query.clear();
        }
        picker.set_search_text(SharedString::default());
        refresh(&picker, &back_state);
    });

    let select_state = state.clone();
    let select_callback = on_select.clone();
    picker.on_use_selected(move || {
        if let Some(context) = selected_context(&select_state) {
            close_active();
            select_callback(context);
        }
    });

    let browse_callback = on_browse.clone();
    picker.on_browse_folder(move || {
        close_active();
        browse_callback();
    });

    picker.on_cancel(close_active);

    if picker.show().is_ok() {
        ACTIVE_PICKER.with(|slot| *slot.borrow_mut() = Some(picker));
    } else {
        on_browse();
    }
}

fn refresh(picker: &LauncherWorldPicker, state: &Rc<RefCell<PickerState>>) {
    let mut state = state.borrow_mut();
    normalize_navigation(&mut state);
    let query = state.query.trim().to_ascii_lowercase();
    let mut rows = Vec::new();
    let mut visible = Vec::new();
    let empty_message: String;

    match state.step {
        Step::Launcher => {
            for (launcher_index, entry) in state.launchers.iter().enumerate() {
                let searchable =
                    format!("{} {}", entry.launcher.name, entry.launcher.root.display());
                if !matches_query(&searchable, &query) {
                    continue;
                }
                rows.push(PickerRow {
                    icon_kind: 0,
                    title: entry.launcher.name.clone().into(),
                    subtitle: entry.launcher.root.display().to_string().into(),
                });
                visible.push(Choice::Launcher(launcher_index));
            }
            picker.set_breadcrumb("Launcher".into());
            empty_message = if state.launchers.is_empty() {
                "No Minecraft launchers were detected. Browse directly to a world folder to continue."
                    .to_string()
            } else {
                "No launcher matches this search.".to_string()
            };
        }
        Step::Instance => {
            if let Some(entry) = active_launcher(&state) {
                for (instance_index, instance) in entry.instances.iter().enumerate() {
                    let searchable = format!(
                        "{} {} {} {}",
                        instance.name,
                        instance.version,
                        instance.loader.label(),
                        instance.minecraft_dir.display()
                    );
                    if !matches_query(&searchable, &query) {
                        continue;
                    }
                    rows.push(PickerRow {
                        icon_kind: 1,
                        title: instance.name.clone().into(),
                        subtitle: format!(
                            "MC {} · {} · {} world(s){}",
                            instance.version,
                            instance.loader.label(),
                            instance.worlds.len(),
                            if instance.has_polymer {
                                " · Polymer"
                            } else {
                                ""
                            }
                        )
                        .into(),
                    });
                    visible.push(Choice::Instance(instance_index));
                }
                picker.set_breadcrumb(format!("{}  ›  Instance", entry.launcher.name).into());
                empty_message = if entry.instances.is_empty() {
                    "This launcher was found, but Minesport did not discover any instances in it."
                        .to_string()
                } else {
                    "No instance matches this search.".to_string()
                };
            } else {
                picker.set_breadcrumb("Launcher".into());
                empty_message = "Launcher selection changed. Select it again.".to_string();
            }
        }
        Step::World => {
            if let Some((entry, instance)) = active_instance(&state) {
                for (world_index, world) in instance.worlds.iter().enumerate() {
                    let searchable = format!("{} {}", world.name, world.path.display());
                    if !matches_query(&searchable, &query) {
                        continue;
                    }
                    rows.push(PickerRow {
                        icon_kind: 2,
                        title: world.name.clone().into(),
                        subtitle: format!(
                            "{} · {}",
                            relative_time(world.last_played),
                            world.path.display()
                        )
                        .into(),
                    });
                    visible.push(Choice::World(world_index));
                }
                picker.set_breadcrumb(
                    format!("{}  ›  {}  ›  World", entry.launcher.name, instance.name).into(),
                );
                empty_message = if instance.worlds.is_empty() {
                    "This instance has no discovered saves. Go back to choose another instance."
                        .to_string()
                } else {
                    "No world matches this search.".to_string()
                };
            } else {
                picker.set_breadcrumb("Launcher  ›  Instance".into());
                empty_message = "Instance selection changed. Select it again.".to_string();
            }
        }
    }

    state.visible = visible;
    let selected_row = match state.selected_world {
        Some(world_index) => state
            .visible
            .iter()
            .position(|choice| matches!(choice, Choice::World(index) if *index == world_index))
            .map(|index| index as i32)
            .unwrap_or(-1),
        None => -1,
    };
    let can_use = state.step == Step::World && selected_row >= 0;
    picker.set_rows(ModelRc::new(VecModel::from(rows)));
    picker.set_selected_index(selected_row);
    picker.set_can_back(state.step != Step::Launcher);
    picker.set_can_use(can_use);
    picker.set_empty_message(empty_message.into());
}

fn normalize_navigation(state: &mut PickerState) {
    if state.step == Step::Launcher {
        state.launcher_index = None;
        state.instance_index = None;
        state.selected_world = None;
        return;
    }

    let Some(launcher_index) = state.launcher_index else {
        reset_to_launcher(state);
        return;
    };
    if launcher_index >= state.launchers.len() {
        reset_to_launcher(state);
        return;
    }

    if state.step == Step::Instance {
        state.instance_index = None;
        state.selected_world = None;
        return;
    }

    let Some(instance_index) = state.instance_index else {
        reset_to_instance(state);
        return;
    };
    if instance_index >= state.launchers[launcher_index].instances.len() {
        reset_to_instance(state);
    }
}

fn reset_to_launcher(state: &mut PickerState) {
    state.step = Step::Launcher;
    state.launcher_index = None;
    state.instance_index = None;
    state.selected_world = None;
    state.query.clear();
}

fn reset_to_instance(state: &mut PickerState) {
    state.step = Step::Instance;
    state.instance_index = None;
    state.selected_world = None;
    state.query.clear();
}

fn active_launcher(state: &PickerState) -> Option<&launcher::CatalogEntry> {
    state
        .launcher_index
        .and_then(|index| state.launchers.get(index))
}

fn active_instance(state: &PickerState) -> Option<(&launcher::CatalogEntry, &launcher::Instance)> {
    let entry = active_launcher(state)?;
    let instance = state
        .instance_index
        .and_then(|index| entry.instances.get(index))?;
    Some((entry, instance))
}

fn matches_query(value: &str, query: &str) -> bool {
    query.is_empty() || value.to_ascii_lowercase().contains(query)
}

fn selected_context(state: &Rc<RefCell<PickerState>>) -> Option<WorldContext> {
    let state = state.borrow();
    let (entry, instance) = active_instance(&state)?;
    let world_index = state.selected_world?;
    let world = instance.worlds.get(world_index)?;
    Some(world_context::context_from_parts(
        &entry.launcher,
        instance,
        world,
    ))
}

fn relative_time(value: Option<SystemTime>) -> String {
    let Some(value) = value else {
        return "unknown".to_string();
    };
    let elapsed = SystemTime::now()
        .duration_since(value)
        .unwrap_or(Duration::ZERO);
    if elapsed < Duration::from_secs(60) {
        return "just now".to_string();
    }
    if elapsed < Duration::from_secs(60 * 60) {
        return format!("{}m ago", elapsed.as_secs() / 60);
    }
    if elapsed < Duration::from_secs(24 * 60 * 60) {
        return format!("{}h ago", elapsed.as_secs() / (60 * 60));
    }
    if elapsed < Duration::from_secs(30 * 24 * 60 * 60) {
        return format!("{}d ago", elapsed.as_secs() / (24 * 60 * 60));
    }
    calendar_date(value)
}

fn calendar_date(value: SystemTime) -> String {
    let Ok(since_epoch) = value.duration_since(UNIX_EPOCH) else {
        return "unknown".to_string();
    };
    let days = (since_epoch.as_secs() / 86_400) as i64;
    let (year, month, day) = civil_from_days(days);
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    let month_name = MONTHS
        .get(month.saturating_sub(1) as usize)
        .copied()
        .unwrap_or("?");
    format!("{month_name} {day}, {year}")
}

fn civil_from_days(days_since_epoch: i64) -> (i32, u32, u32) {
    let z = days_since_epoch + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let mut year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_prime + 2) / 5 + 1;
    let month = month_prime + if month_prime < 10 { 3 } else { -9 };
    if month <= 2 {
        year += 1;
    }
    (year as i32, month as u32, day as u32)
}

fn close_active() {
    ACTIVE_PICKER.with(|slot| {
        if let Some(picker) = slot.borrow_mut().take() {
            let _ = picker.hide();
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_is_case_insensitive() {
        assert!(matches_query("Prism Launcher", "prism"));
        assert!(matches_query("Minecraft 1.21.10", "1.21"));
        assert!(!matches_query("ATLauncher", "prism"));
    }

    #[test]
    fn unix_epoch_formats_like_fyne_date() {
        assert_eq!(calendar_date(UNIX_EPOCH), "Jan 1, 1970");
    }

    #[test]
    fn missing_timestamp_is_unknown() {
        assert_eq!(relative_time(None), "unknown");
    }
}

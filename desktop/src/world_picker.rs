use crate::world_context::WorldContext;
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};
use std::{cell::RefCell, collections::BTreeSet, rc::Rc};

slint::slint! {
    import { Button, LineEdit, ListView } from "std-widgets.slint";

    export component LauncherWorldPicker inherits Window {
        title: "Select Minecraft World";
        width: 760px;
        height: 560px;
        background: #11171d;

        in property <[string]> rows;
        in-out property <string> breadcrumb: "Launcher";
        in-out property <string> search-text: "";
        in-out property <int> selected-index: -1;
        in property <bool> can-back: false;
        in property <bool> can-use: false;
        callback activate(int);
        callback go-back();
        callback use-selected();
        callback browse-folder();
        callback search-edited(string);

        VerticalLayout {
            padding: 14px;
            spacing: 8px;

            Text {
                text: "MINECRAFT WORLD";
                color: #dce7ef;
                font-size: 16px;
                font-weight: 700;
            }
            Text {
                text: root.breadcrumb;
                color: #8fa2b4;
                font-size: 11px;
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

                if root.rows.length == 0: Text {
                    text: "Nothing found here. You can still browse directly to a world folder.";
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
                        height: 52px;
                        width: parent.width;
                        background: root.selected-index == index ? #243748 : hover.has-hover ? #18232c : transparent;
                        border-radius: 4px;

                        Text {
                            x: 10px;
                            width: parent.width - 20px;
                            height: parent.height;
                            text: row;
                            color: root.selected-index == index ? #e7f3fb : #bac8d2;
                            font-size: 11px;
                            vertical-alignment: center;
                            overflow: elide;
                        }
                        hover := TouchArea {
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
                    text: "BACK";
                    enabled: root.can-back;
                    clicked => { root.go-back(); }
                }
                Button {
                    text: "BROWSE FOLDER…";
                    clicked => { root.browse-folder(); }
                }
                Rectangle { horizontal-stretch: 1; }
                Button {
                    text: "USE WORLD";
                    enabled: root.can-use;
                    clicked => { root.use-selected(); }
                }
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Step {
    Launcher,
    Instance,
    World,
}

#[derive(Debug, Clone)]
enum Choice {
    Launcher(String),
    Instance(String),
    World(usize),
}

struct PickerState {
    all: Vec<WorldContext>,
    step: Step,
    launcher: Option<String>,
    instance: Option<String>,
    query: String,
    visible: Vec<Choice>,
    selected_world: Option<usize>,
}

thread_local! {
    static ACTIVE_PICKER: RefCell<Option<LauncherWorldPicker>> = const { RefCell::new(None) };
}

pub fn show<F, B>(worlds: Vec<WorldContext>, on_select: F, on_browse: B)
where
    F: Fn(WorldContext) + 'static,
    B: Fn() + 'static,
{
    close_active();
    let picker = match LauncherWorldPicker::new() {
        Ok(value) => value,
        Err(_) => {
            on_browse();
            return;
        }
    };

    let state = Rc::new(RefCell::new(PickerState {
        all: worlds,
        step: Step::Launcher,
        launcher: None,
        instance: None,
        query: String::new(),
        visible: Vec::new(),
        selected_world: None,
    }));

    refresh(&picker, &state);

    let weak = picker.as_weak();
    let activate_state = state.clone();
    picker.on_activate(move |index| {
        let Some(picker) = weak.upgrade() else { return; };
        let choice = activate_state
            .borrow()
            .visible
            .get(index.max(0) as usize)
            .cloned();
        let Some(choice) = choice else { return; };
        {
            let mut state = activate_state.borrow_mut();
            match choice {
                Choice::Launcher(value) => {
                    state.launcher = Some(value);
                    state.instance = None;
                    state.selected_world = None;
                    state.step = Step::Instance;
                    state.query.clear();
                }
                Choice::Instance(value) => {
                    state.instance = Some(value);
                    state.selected_world = None;
                    state.step = Step::World;
                    state.query.clear();
                }
                Choice::World(world_index) => {
                    state.selected_world = Some(world_index);
                }
            }
        }
        picker.set_search_text(SharedString::new());
        refresh(&picker, &activate_state);
    });

    let weak = picker.as_weak();
    let search_state = state.clone();
    picker.on_search_edited(move |value| {
        let Some(picker) = weak.upgrade() else { return; };
        search_state.borrow_mut().query = value.to_string();
        refresh(&picker, &search_state);
    });

    let weak = picker.as_weak();
    let back_state = state.clone();
    picker.on_go_back(move || {
        let Some(picker) = weak.upgrade() else { return; };
        {
            let mut state = back_state.borrow_mut();
            match state.step {
                Step::Launcher => return,
                Step::Instance => {
                    state.step = Step::Launcher;
                    state.launcher = None;
                }
                Step::World => {
                    state.step = Step::Instance;
                    state.instance = None;
                }
            }
            state.selected_world = None;
            state.query.clear();
        }
        picker.set_search_text(SharedString::new());
        refresh(&picker, &back_state);
    });

    let select_state = state.clone();
    picker.on_use_selected(move || {
        let selected = select_state
            .borrow()
            .selected_world
            .and_then(|index| select_state.borrow().all.get(index).cloned());
        if let Some(context) = selected {
            close_active();
            on_select(context);
        }
    });

    picker.on_browse_folder(move || {
        close_active();
        on_browse();
    });

    if picker.show().is_ok() {
        ACTIVE_PICKER.with(|slot| *slot.borrow_mut() = Some(picker));
    } else {
        on_browse();
    }
}

fn refresh(picker: &LauncherWorldPicker, state: &Rc<RefCell<PickerState>>) {
    let mut state = state.borrow_mut();
    let query = state.query.trim().to_ascii_lowercase();
    let matches = |value: &str| query.is_empty() || value.to_ascii_lowercase().contains(&query);
    let mut rows = Vec::new();
    let mut visible = Vec::new();

    match state.step {
        Step::Launcher => {
            let launchers = state
                .all
                .iter()
                .map(|context| context.launcher.clone())
                .collect::<BTreeSet<_>>();
            for launcher in launchers {
                if !matches(&launcher) { continue; }
                let worlds = state.all.iter().filter(|context| context.launcher == launcher).count();
                rows.push(SharedString::from(format!("{launcher}\n{worlds} discovered world(s)")));
                visible.push(Choice::Launcher(launcher));
            }
            picker.set_breadcrumb("Launcher".into());
        }
        Step::Instance => {
            let launcher = state.launcher.clone().unwrap_or_default();
            let instances = state
                .all
                .iter()
                .filter(|context| context.launcher == launcher)
                .map(|context| context.instance.clone())
                .collect::<BTreeSet<_>>();
            for instance in instances {
                let sample = state.all.iter().find(|context| context.launcher == launcher && context.instance == instance);
                let Some(sample) = sample else { continue; };
                let searchable = format!("{} {} {}", instance, sample.version, sample.loader);
                if !matches(&searchable) { continue; }
                let worlds = state.all.iter().filter(|context| context.launcher == launcher && context.instance == instance).count();
                rows.push(SharedString::from(format!(
                    "{}\nMC {} · {} · {} world(s){}",
                    instance,
                    sample.version,
                    sample.loader,
                    worlds,
                    if sample.has_polymer { " · Polymer" } else { "" }
                )));
                visible.push(Choice::Instance(instance));
            }
            picker.set_breadcrumb(format!("{launcher}  ›  Instance").into());
        }
        Step::World => {
            let launcher = state.launcher.clone().unwrap_or_default();
            let instance = state.instance.clone().unwrap_or_default();
            for (world_index, context) in state.all.iter().enumerate() {
                if context.launcher != launcher || context.instance != instance { continue; }
                let searchable = format!("{} {}", context.world_name, context.world_path.display());
                if !matches(&searchable) { continue; }
                rows.push(SharedString::from(format!("{}\n{}", context.world_name, context.world_path.display())));
                visible.push(Choice::World(world_index));
            }
            picker.set_breadcrumb(format!("{launcher}  ›  {instance}  ›  World").into());
        }
    }

    state.visible = visible;
    let selected_row = match state.selected_world {
        Some(world_index) => state.visible.iter().position(|choice| matches!(choice, Choice::World(index) if *index == world_index)).map(|index| index as i32).unwrap_or(-1),
        None => -1,
    };
    picker.set_rows(ModelRc::new(VecModel::from(rows)));
    picker.set_selected_index(selected_row);
    picker.set_can_back(state.step != Step::Launcher);
    picker.set_can_use(state.step == Step::World && state.selected_world.is_some());
}

fn close_active() {
    ACTIVE_PICKER.with(|slot| {
        if let Some(picker) = slot.borrow_mut().take() {
            let _ = picker.hide();
        }
    });
}

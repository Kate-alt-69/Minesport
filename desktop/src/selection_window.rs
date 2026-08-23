use crate::{preview, selection};
use anyhow::Result;
use slint::ComponentHandle;
use std::rc::Rc;

slint::slint! {
    import { Button, ComboBox, SpinBox } from "std-widgets.slint";

    export component SelectionWindow inherits Window {
        title: "Minesport — Selection";
        preferred-width: 480px;
        preferred-height: 380px;
        min-width: 440px;
        min-height: 340px;
        background: #0b1015;

        in property <int> seed-x;
        in property <int> seed-y;
        in property <int> seed-z;
        in-out property <int> selection-mode: 0;
        in-out property <int> power: 64;
        in-out property <int> reach-x: 16;
        in-out property <int> reach-y: 16;
        in-out property <int> reach-z: 16;
        in-out property <string> joined-status: "Click PREVIEW to compute the selection.";

        callback preview-joined(int);
        callback use-joined(int);
        callback use-area(int, int, int);
        callback cancel();

        VerticalLayout {
            padding: 16px;
            spacing: 12px;

            Text {
                text: "3D SELECTION";
                color: #eef3f6;
                font-size: 15px;
                font-weight: 700;
            }
            Text {
                text: "Starting from block (" + root.seed-x + ", " + root.seed-y + ", " + root.seed-z + ")";
                color: #8193a2;
                font-size: 10px;
            }

            ComboBox {
                model: ["Joined Blocks", "Area Selection"];
                current-index <=> root.selection-mode;
            }

            if root.selection-mode == 0: VerticalLayout {
                spacing: 10px;
                Text {
                    text: "Power — max blocks the fill can reach. Never crosses air; any touching block type can join.";
                    color: #93a2b0;
                    font-size: 10px;
                    wrap: word-wrap;
                }
                HorizontalLayout {
                    spacing: 8px;
                    Text { text: "POWER"; width: 74px; color: #7f909f; font-size: 10px; vertical-alignment: center; }
                    SpinBox { horizontal-stretch: 1; minimum: 1; maximum: 5000000; value <=> root.power; }
                    Button { text: "PREVIEW"; clicked => { root.preview-joined(root.power); } }
                }
                Rectangle {
                    height: 64px;
                    background: #111920;
                    border-width: 1px;
                    border-color: #263641;
                    border-radius: 5px;
                    Text {
                        x: 10px; y: 8px; width: parent.width - 20px; height: parent.height - 16px;
                        text: root.joined-status;
                        color: #9cb1a5;
                        font-size: 10px;
                        wrap: word-wrap;
                        vertical-alignment: center;
                    }
                }
                Rectangle { vertical-stretch: 1; background: transparent; }
                HorizontalLayout {
                    spacing: 8px;
                    Rectangle { horizontal-stretch: 1; background: transparent; }
                    Button { text: "CANCEL"; clicked => { root.cancel(); } }
                    Button { text: "USE THIS SELECTION"; primary: true; clicked => { root.use-joined(root.power); } }
                }
            }

            if root.selection-mode == 1: VerticalLayout {
                spacing: 10px;
                Text {
                    text: "Cube — how far outward from the clicked block on each axis. More shapes can be added later.";
                    color: #93a2b0;
                    font-size: 10px;
                    wrap: word-wrap;
                }
                HorizontalLayout {
                    spacing: 8px;
                    VerticalLayout {
                        spacing: 4px;
                        Text { text: "X REACH"; color: #7f909f; font-size: 9px; }
                        SpinBox { minimum: 0; maximum: 30000000; value <=> root.reach-x; }
                    }
                    VerticalLayout {
                        spacing: 4px;
                        Text { text: "Y REACH"; color: #7f909f; font-size: 9px; }
                        SpinBox { minimum: 0; maximum: 30000000; value <=> root.reach-y; }
                    }
                    VerticalLayout {
                        spacing: 4px;
                        Text { text: "Z REACH"; color: #7f909f; font-size: 9px; }
                        SpinBox { minimum: 0; maximum: 30000000; value <=> root.reach-z; }
                    }
                }
                Rectangle {
                    height: 64px;
                    background: #111920;
                    border-width: 1px;
                    border-color: #263641;
                    border-radius: 5px;
                    Text {
                        x: 10px; y: 8px; width: parent.width - 20px; height: parent.height - 16px;
                        text: "Box: X " + (root.seed-x - root.reach-x) + ".." + (root.seed-x + root.reach-x)
                            + "  ·  Y " + (root.seed-y - root.reach-y) + ".." + (root.seed-y + root.reach-y)
                            + "  ·  Z " + (root.seed-z - root.reach-z) + ".." + (root.seed-z + root.reach-z);
                        color: #9cb1a5;
                        font-size: 10px;
                        wrap: word-wrap;
                        vertical-alignment: center;
                    }
                }
                Rectangle { vertical-stretch: 1; background: transparent; }
                HorizontalLayout {
                    spacing: 8px;
                    Rectangle { horizontal-stretch: 1; background: transparent; }
                    Button { text: "CANCEL"; clicked => { root.cancel(); } }
                    Button { text: "USE THIS SELECTION"; primary: true; clicked => { root.use-area(root.reach-x, root.reach-y, root.reach-z); } }
                }
            }
        }
    }
}

#[derive(Debug, Clone)]
pub enum SelectionAction {
    Exact(selection::ExactSelection),
    Area { min: [i32; 3], max: [i32; 3], label: String },
}

pub fn show<F>(picked: preview::PreviewPick, pick_map: preview::PreviewPickMap, on_apply: F) -> Result<()>
where
    F: Fn(SelectionAction) + 'static,
{
    let window = SelectionWindow::new()?;
    window.set_seed_x(picked.x);
    window.set_seed_y(picked.y);
    window.set_seed_z(picked.z);
    window.set_selection_mode(0);
    window.set_power(64);
    window.set_reach_x(16);
    window.set_reach_y(16);
    window.set_reach_z(16);

    let seed = [picked.x, picked.y, picked.z];
    let apply = Rc::new(on_apply);

    let preview_map = pick_map.clone();
    let preview_weak = window.as_weak();
    window.on_preview_joined(move |power| {
        let limit = power.clamp(1, 5_000_000) as usize;
        let count = preview_map.joined_blocks(seed, limit).len();
        if let Some(window) = preview_weak.upgrade() {
            window.set_joined_status(format!("{count} blocks selected\nConnected solids only · air stops the fill").into());
        }
    });

    let joined_map = pick_map;
    let joined_weak = window.as_weak();
    let joined_apply = apply.clone();
    let joined_label = picked.id.clone();
    window.on_use_joined(move |power| {
        let limit = power.clamp(1, 5_000_000) as usize;
        let coordinates = joined_map.joined_blocks(seed, limit);
        let Some(exact) = selection::ExactSelection::from_coordinates(
            coordinates,
            format!("Joined Blocks from {},{},{} · {joined_label}", seed[0], seed[1], seed[2]),
        ) else {
            if let Some(window) = joined_weak.upgrade() {
                window.set_joined_status("Empty selection · no connected solid blocks found".into());
            }
            return;
        };
        joined_apply(SelectionAction::Exact(exact));
        if let Some(window) = joined_weak.upgrade() { let _ = window.hide(); }
    });

    let area_weak = window.as_weak();
    let area_apply = apply;
    window.on_use_area(move |rx, ry, rz| {
        let rx = rx.clamp(0, 30_000_000);
        let ry = ry.clamp(0, 30_000_000);
        let rz = rz.clamp(0, 30_000_000);
        let min = [seed[0].saturating_sub(rx), seed[1].saturating_sub(ry), seed[2].saturating_sub(rz)];
        let max = [seed[0].saturating_add(rx), seed[1].saturating_add(ry), seed[2].saturating_add(rz)];
        area_apply(SelectionAction::Area {
            min,
            max,
            label: format!("Area Selection ({},{},{}) ± ({rx},{ry},{rz})", seed[0], seed[1], seed[2]),
        });
        if let Some(window) = area_weak.upgrade() { let _ = window.hide(); }
    });

    let cancel_weak = window.as_weak();
    window.on_cancel(move || {
        if let Some(window) = cancel_weak.upgrade() { let _ = window.hide(); }
    });

    window.show()?;
    Ok(())
}

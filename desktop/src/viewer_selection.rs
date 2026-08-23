use std::sync::{Mutex, OnceLock};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BoxSelection {
    pub point_a: [i32; 3],
    pub point_b: [i32; 3],
    pub min: [i32; 3],
    pub max: [i32; 3],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PrimaryAction {
    PickPointA,
    Confirm(BoxSelection),
}

#[derive(Debug, Default)]
struct State {
    point_a: Option<[i32; 3]>,
    point_b: Option<[i32; 3]>,
}

fn state() -> &'static Mutex<State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE.get_or_init(|| Mutex::new(State::default()))
}

pub fn reset() {
    if let Ok(mut state) = state().lock() {
        *state = State::default();
    }
}

/// Old viewer semantics: when both points exist, the next LMB confirms the
/// current cuboid without performing another raycast. Otherwise LMB chooses A.
pub fn primary_action() -> PrimaryAction {
    let Ok(state) = state().lock() else { return PrimaryAction::PickPointA; };
    match (state.point_a, state.point_b) {
        (Some(point_a), Some(point_b)) => PrimaryAction::Confirm(selection(point_a, point_b)),
        _ => PrimaryAction::PickPointA,
    }
}

pub fn set_point_a(point: [i32; 3]) {
    if let Ok(mut state) = state().lock() {
        state.point_a = Some(point);
        state.point_b = None;
    }
}

/// RMB only has meaning after point A exists, matching the retired viewer.
pub fn set_point_b(point: [i32; 3]) -> Option<BoxSelection> {
    let Ok(mut state) = state().lock() else { return None; };
    let point_a = state.point_a?;
    state.point_b = Some(point);
    Some(selection(point_a, point))
}

pub fn current() -> Option<BoxSelection> {
    let Ok(state) = state().lock() else { return None; };
    Some(selection(state.point_a?, state.point_b?))
}

pub fn point_a() -> Option<[i32; 3]> {
    state().lock().ok().and_then(|state| state.point_a)
}

/// Grow/shrink point B by one block along the dominant look axis. The sign of
/// the look vector matters exactly like the retired OpenGL viewer: looking west
/// grows toward -X, looking down grows toward -Y, etc.
pub fn resize_point_b(direction: [f32; 3], delta: i32) -> Option<BoxSelection> {
    if delta == 0 { return current(); }
    let Ok(mut state) = state().lock() else { return None; };
    let point_a = state.point_a?;
    let mut point_b = state.point_b?;

    let axis = dominant_axis(direction);
    let signed_delta = if direction[axis] >= 0.0 { delta } else { delta.saturating_neg() };
    point_b[axis] = point_b[axis].saturating_add(signed_delta);
    state.point_b = Some(point_b);
    Some(selection(point_a, point_b))
}

fn selection(point_a: [i32; 3], point_b: [i32; 3]) -> BoxSelection {
    BoxSelection {
        point_a,
        point_b,
        min: [
            point_a[0].min(point_b[0]),
            point_a[1].min(point_b[1]),
            point_a[2].min(point_b[2]),
        ],
        max: [
            point_a[0].max(point_b[0]),
            point_a[1].max(point_b[1]),
            point_a[2].max(point_b[2]),
        ],
    }
}

fn dominant_axis(direction: [f32; 3]) -> usize {
    let x = direction[0].abs();
    let y = direction[1].abs();
    let z = direction[2].abs();
    if x >= y && x >= z { 0 } else if y >= z { 1 } else { 2 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lmb_rmb_lmb_matches_retired_box_workflow() {
        reset();
        assert_eq!(primary_action(), PrimaryAction::PickPointA);
        set_point_a([5, 70, -4]);
        assert_eq!(primary_action(), PrimaryAction::PickPointA);
        let box_selection = set_point_b([2, 64, 8]).unwrap();
        assert_eq!(box_selection.min, [2, 64, -4]);
        assert_eq!(box_selection.max, [5, 70, 8]);
        assert_eq!(primary_action(), PrimaryAction::Confirm(box_selection));
    }

    #[test]
    fn choosing_new_point_a_clears_point_b() {
        reset();
        set_point_a([0, 0, 0]);
        set_point_b([4, 4, 4]).unwrap();
        set_point_a([9, 9, 9]);
        assert_eq!(point_a(), Some([9, 9, 9]));
        assert!(current().is_none());
    }

    #[test]
    fn e_wheel_resize_uses_dominant_axis_and_look_sign() {
        reset();
        set_point_a([0, 0, 0]);
        set_point_b([3, 4, 5]).unwrap();

        let down = resize_point_b([0.2, -0.9, 0.3], 2).unwrap();
        assert_eq!(down.point_b, [3, 2, 5]);

        let west = resize_point_b([-0.95, 0.1, 0.2], 1).unwrap();
        assert_eq!(west.point_b, [2, 2, 5]);

        let south_shrink = resize_point_b([0.1, 0.2, 0.98], -2).unwrap();
        assert_eq!(south_shrink.point_b, [2, 2, 3]);
    }
}

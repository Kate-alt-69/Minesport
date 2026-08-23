use std::collections::HashSet;

/// Amanatides & Woo voxel traversal, ported from the retired Go/OpenGL viewer.
/// Returns the first occupied voxel reached by the normalized ray before
/// `max_distance` world units have been travelled.
pub fn raycast_occupied(
    occupied: &HashSet<[i32; 3]>,
    origin: [f32; 3],
    direction: [f32; 3],
    max_distance: f32,
) -> Option<[i32; 3]> {
    if occupied.is_empty() || !max_distance.is_finite() || max_distance <= 0.0 {
        return None;
    }

    let direction = normalize(direction)?;
    let mut x = origin[0].floor() as i32;
    let mut y = origin[1].floor() as i32;
    let mut z = origin[2].floor() as i32;

    let (step_x, delta_x, mut max_x) = dda_axis(origin[0], direction[0]);
    let (step_y, delta_y, mut max_y) = dda_axis(origin[1], direction[1]);
    let (step_z, delta_z, mut max_z) = dda_axis(origin[2], direction[2]);

    let mut travelled = 0.0f32;
    while travelled < max_distance {
        let position = [x, y, z];
        if occupied.contains(&position) {
            return Some(position);
        }

        // Keep the retired viewer's tie-breaking rules byte-for-byte in spirit:
        // X only wins when strictly less than Y/Z, then Y when strictly less
        // than Z, otherwise Z wins.
        if max_x < max_y && max_x < max_z {
            x = x.checked_add(step_x)?;
            travelled = max_x;
            max_x += delta_x;
        } else if max_y < max_z {
            y = y.checked_add(step_y)?;
            travelled = max_y;
            max_y += delta_y;
        } else {
            z = z.checked_add(step_z)?;
            travelled = max_z;
            max_z += delta_z;
        }
    }
    None
}

fn normalize(value: [f32; 3]) -> Option<[f32; 3]> {
    if value.iter().any(|component| !component.is_finite()) {
        return None;
    }
    let length = (value[0] * value[0] + value[1] * value[1] + value[2] * value[2]).sqrt();
    if length < 1.0e-6 {
        return None;
    }
    Some([value[0] / length, value[1] / length, value[2] / length])
}

/// Direction, distance to cross one full voxel on this axis, and distance to
/// the first voxel boundary from the ray's real starting position.
fn dda_axis(origin: f32, direction: f32) -> (i32, f32, f32) {
    if direction > 0.0 {
        let delta = 1.0 / direction;
        let boundary = origin.floor() + 1.0;
        (1, delta, (boundary - origin) * delta)
    } else if direction < 0.0 {
        let delta = 1.0 / -direction;
        let boundary = origin.floor();
        (-1, delta, (origin - boundary) * delta)
    } else {
        (0, f32::INFINITY, f32::INFINITY)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raycast_hits_first_solid_voxel() {
        let occupied = HashSet::from([[0, 0, 0], [2, 0, 0]]);
        assert_eq!(
            raycast_occupied(&occupied, [-2.5, 0.5, 0.5], [1.0, 0.0, 0.0], 20.0),
            Some([0, 0, 0])
        );
    }

    #[test]
    fn raycast_walks_negative_axes() {
        let occupied = HashSet::from([[-3, 4, 7]]);
        assert_eq!(
            raycast_occupied(&occupied, [2.25, 4.5, 7.5], [-1.0, 0.0, 0.0], 20.0),
            Some([-3, 4, 7])
        );
    }

    #[test]
    fn raycast_miss_and_invalid_direction_are_safe() {
        let occupied = HashSet::from([[0, 0, 0]]);
        assert_eq!(raycast_occupied(&occupied, [5.5, 5.5, 5.5], [1.0, 0.0, 0.0], 3.0), None);
        assert_eq!(raycast_occupied(&occupied, [0.5, 0.5, 0.5], [0.0, 0.0, 0.0], 3.0), None);
    }

    #[test]
    fn dda_axis_matches_retired_viewer_boundary_math() {
        let (step, delta, first) = dda_axis(2.25, 2.0);
        assert_eq!(step, 1);
        assert!((delta - 0.5).abs() < f32::EPSILON);
        assert!((first - 0.375).abs() < f32::EPSILON);

        let (step, delta, first) = dda_axis(2.25, -2.0);
        assert_eq!(step, -1);
        assert!((delta - 0.5).abs() < f32::EPSILON);
        assert!((first - 0.125).abs() < f32::EPSILON);
    }
}

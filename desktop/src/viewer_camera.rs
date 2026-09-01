#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct FlightInput {
    pub forward: bool,
    pub back: bool,
    pub left: bool,
    pub right: bool,
    pub up: bool,
    pub down: bool,
    pub sprint: bool,
}

pub const DEFAULT_MOVE_SPEED: f32 = 12.0;
pub const SPRINT_MULTIPLIER: f32 = 3.0;
pub const MIN_MOVE_SPEED: f32 = 1.0;
pub const MAX_MOVE_SPEED: f32 = 1200.0;

/// Match the retired GLFW viewer's Minecraft-creative-style movement.
/// WASD is horizontal relative to yaw only; Space/Shift is world vertical.
pub fn movement_delta(yaw: f32, move_speed: f32, input: FlightInput, dt: f32) -> [f32; 3] {
    if !yaw.is_finite() || !move_speed.is_finite() || !dt.is_finite() || dt <= 0.0 {
        return [0.0; 3];
    }

    let forward = [yaw.sin(), 0.0, -yaw.cos()];
    let right = [-forward[2], 0.0, forward[0]];
    let mut movement = [0.0_f32; 3];

    if input.forward {
        add(&mut movement, forward);
    }
    if input.back {
        add(&mut movement, scale(forward, -1.0));
    }
    if input.right {
        add(&mut movement, right);
    }
    if input.left {
        add(&mut movement, scale(right, -1.0));
    }
    if input.up {
        movement[1] += 1.0;
    }
    if input.down {
        movement[1] -= 1.0;
    }

    let length =
        (movement[0] * movement[0] + movement[1] * movement[1] + movement[2] * movement[2]).sqrt();
    if length <= 1.0e-6 {
        return [0.0; 3];
    }

    let speed = move_speed.clamp(MIN_MOVE_SPEED, MAX_MOVE_SPEED)
        * if input.sprint { SPRINT_MULTIPLIER } else { 1.0 }
        * dt;
    [
        movement[0] / length * speed,
        movement[1] / length * speed,
        movement[2] / length * speed,
    ]
}

/// Match the retired viewer: mouse wheel while flying changes speed by 10%
/// per wheel step and clamps to a useful 1..1200 blocks/second range.
pub fn adjusted_speed(current: f32, wheel_steps: f32) -> f32 {
    let current = if current.is_finite() {
        current.clamp(MIN_MOVE_SPEED, MAX_MOVE_SPEED)
    } else {
        DEFAULT_MOVE_SPEED
    };
    if !wheel_steps.is_finite() || wheel_steps == 0.0 {
        return current;
    }
    (current * 1.1_f32.powf(wheel_steps)).clamp(MIN_MOVE_SPEED, MAX_MOVE_SPEED)
}

fn add(target: &mut [f32; 3], value: [f32; 3]) {
    target[0] += value[0];
    target[1] += value[1];
    target[2] += value[2];
}

fn scale(value: [f32; 3], scalar: f32) -> [f32; 3] {
    [value[0] * scalar, value[1] * scalar, value[2] * scalar]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::FRAC_PI_2;

    fn close(a: f32, b: f32) {
        assert!((a - b).abs() < 0.0001, "{a} != {b}");
    }

    #[test]
    fn yaw_zero_forward_matches_old_negative_z_flight() {
        let delta = movement_delta(
            0.0,
            DEFAULT_MOVE_SPEED,
            FlightInput {
                forward: true,
                ..FlightInput::default()
            },
            1.0,
        );
        close(delta[0], 0.0);
        close(delta[1], 0.0);
        close(delta[2], -12.0);
    }

    #[test]
    fn yaw_quarter_turn_forward_moves_positive_x() {
        let delta = movement_delta(
            FRAC_PI_2,
            DEFAULT_MOVE_SPEED,
            FlightInput {
                forward: true,
                ..FlightInput::default()
            },
            1.0,
        );
        close(delta[0], 12.0);
        close(delta[2], 0.0);
    }

    #[test]
    fn diagonal_and_vertical_input_is_normalized() {
        let delta = movement_delta(
            0.0,
            DEFAULT_MOVE_SPEED,
            FlightInput {
                forward: true,
                right: true,
                up: true,
                ..FlightInput::default()
            },
            1.0,
        );
        let length = (delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]).sqrt();
        close(length, DEFAULT_MOVE_SPEED);
    }

    #[test]
    fn sprint_matches_old_three_x_multiplier() {
        let normal = movement_delta(
            0.0,
            10.0,
            FlightInput {
                forward: true,
                ..FlightInput::default()
            },
            0.5,
        );
        let sprint = movement_delta(
            0.0,
            10.0,
            FlightInput {
                forward: true,
                sprint: true,
                ..FlightInput::default()
            },
            0.5,
        );
        close(sprint[2], normal[2] * SPRINT_MULTIPLIER);
    }

    #[test]
    fn wheel_speed_matches_old_ten_percent_steps_and_limits() {
        close(adjusted_speed(12.0, 1.0), 13.2);
        close(adjusted_speed(12.0, -1.0), 12.0 / 1.1);
        close(adjusted_speed(1199.0, 50.0), MAX_MOVE_SPEED);
        close(adjusted_speed(1.01, -50.0), MIN_MOVE_SPEED);
    }
}

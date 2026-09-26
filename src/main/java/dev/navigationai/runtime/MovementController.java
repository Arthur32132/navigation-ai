package dev.navigationai.runtime;

import dev.navigationai.navigation.GridTransition;

/** Feedback controller. Vanilla travel remains responsible for acceleration, gravity and collisions. */
final class MovementController {
    enum Phase { APPROACH, TAKEOFF, FLIGHT, LANDING }
    record State(double x, double y, double z, double vx, double vy, double vz,
                 boolean grounded, boolean sprintAvailable) { }
    record Input(float strafe, float forward, boolean jump, boolean sprint, boolean sneak, Phase phase) { }
    private GridTransition active;
    private Phase phase = Phase.APPROACH;
    private float previousForward;

    void reset() { active = null; phase = Phase.APPROACH; previousForward = 0; }

    Input update(GridTransition transition, State state, boolean continueStraight) {
        if (!transition.equals(active)) {
            active = transition;
            phase = Phase.APPROACH;
        }
        double dx = transition.to().x() + .5 - state.x(), dz = transition.to().z() + .5 - state.z();
        double distance = Math.hypot(dx, dz);
        double ux = distance > 1e-6 ? dx / distance : 0, uz = distance > 1e-6 ? dz / distance : 0;
        double alongSpeed = state.vx() * ux + state.vz() * uz;
        double sidewaysSpeed = state.vx() * uz - state.vz() * ux;
        boolean crouch = transition.pose() == GridTransition.RequiredPose.CROUCHING;
        boolean climbing = transition.type() == GridTransition.Type.CLIMB;
        if (climbing && transition.toSupportHeight() < state.y()) crouch = true;
        boolean swimming = transition.type() == GridTransition.Type.SWIM;
        boolean jumping = transition.type() == GridTransition.Type.JUMP;
        if (jumping && !state.grounded()) phase = Phase.FLIGHT;
        else if (phase == Phase.FLIGHT && state.grounded()) phase = Phase.LANDING;
        boolean sprint = state.sprintAvailable() && !crouch && !climbing && !swimming && phase != Phase.LANDING;
        double desiredSpeed = crouch ? .075 : climbing || swimming ? .12 : sprint ? .27 : .19;
        boolean stopping = phase == Phase.LANDING || !continueStraight && !jumping;
        if (stopping || phase == Phase.FLIGHT && distance < .9) {
            // Stopping distance v²/(2a): begin braking while there is still usable platform ahead.
            double room = Math.max(0, distance - .10);
            desiredSpeed = Math.min(desiredSpeed, Math.sqrt(2 * .055 * room));
            if (phase == Phase.LANDING) desiredSpeed = Math.min(desiredSpeed, .12);
        }
        boolean jump = climbing && transition.toSupportHeight() > state.y()
                || swimming && transition.toSupportHeight() > state.y();
        if (jumping && state.grounded() && phase != Phase.LANDING) {
            double travel = transition.horizontalDistance();
            double progress = travel < 1e-6 ? 0 : ((state.x() - transition.from().x() - .5)
                    * (transition.to().x() - transition.from().x()) + (state.z() - transition.from().z() - .5)
                    * (transition.to().z() - transition.from().z())) / travel;
            boolean ready = progress >= transition.takeoff().edgeProgress()
                    && alongSpeed >= transition.takeoff().minimumSpeed() && Math.abs(sidewaysSpeed) < .12;
            // A lost sprint must not turn an unready takeoff into walking off the platform.
            if (!ready && progress >= .46 && transition.gapJump()) {
                desiredSpeed = 0;
                crouch = true;
                sprint = false;
            } else if (ready) {
                jump = true;
                phase = Phase.TAKEOFF;
            }
        }
        float forward = (float) clamp((desiredSpeed - alongSpeed) * 9, -1, 1);
        // The sprint takeoff adds horizontal momentum. Preserve it across the gap;
        // ground-speed feedback would brake that impulse before reaching a distant platform.
        if (phase == Phase.FLIGHT && (distance >= .9 || state.vy() > 0)) forward = 1;
        // Inputs ramp up; braking and external impulses require an immediate response.
        if (forward > previousForward) forward = Math.min(forward, previousForward + .35f);
        if (climbing || swimming) forward = 1;
        previousForward = forward;
        return new Input((float) clamp(-sidewaysSpeed * 6, -.6, .6), forward, jump, sprint, crouch, phase);
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}

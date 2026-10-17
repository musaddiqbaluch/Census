package com.musaddiqbaluch.census;

import android.util.ArrayMap;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.VelocityTracker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Physics-based press + drag interaction for the droid character.
 *
 * Behaviour
 * ---------
 *   1. Press:       scale -> 0.92
 *   2. Finger drag: horizontal finger movement tilts the droid.
 *   3. Tilt limit:  max +/- 18 degrees from the touch-start rotation.
 *   4. Release:     returns to the rotation held at ACTION_DOWN,
 *                   carrying the finger's angular velocity into the
 *                   return spring so the recoil feels physical.
 *   5. Cancel:      returns to the touch-start rotation at zero
 *                   velocity (no reliable velocity after cancel).
 *
 * Important: the host View must be clickable
 * ------------------------------------------
 *   The touch listener returns false from every action, which is the
 *   correct way to let normal click handling continue. For that to also
 *   keep the View as the framework's touch target for ACTION_MOVE and
 *   ACTION_UP, the View itself must be clickable (so View.onTouchEvent
 *   returns true). If it is not, set `view.setClickable(true)` before
 *   calling attach(). This class intentionally does not change the
 *   View's clickable state, to avoid surprising callers.
 *
 * Robustness
 * ----------
 *   - The instance registry is thread-safe.
 *   - Every touch branch, animation call, and static method is
 *     wrapped; a throw inside a touch dispatcher would otherwise
 *     crash the process.
 *   - VelocityTracker.obtain() may return null; all tracker
 *     interactions are null-safe.
 *   - computeCurrentVelocity() with insufficient samples can leave
 *     getXVelocity() returning NaN; NaN is normalised to 0f.
 *   - detach() is idempotent and safe to call from any lifecycle
 *     point, including immediately after a failed attach.
 */
public final class DroidPressBounce {

    private static final String TAG = "DroidPressBounce";

    // ---------------------------------------------------------
    // PRESS SCALE
    // ---------------------------------------------------------

    private static final float PRESSED_SCALE = 0.92f;
    private static final float NORMAL_SCALE = 1.0f;

    private static final float SCALE_STIFFNESS = 700f;
    private static final float SCALE_DAMPING = 0.65f;

    // ---------------------------------------------------------
    // DROID TILT
    // ---------------------------------------------------------

    private static final float MAX_TILT = 18f;
    private static final float TILT_DISTANCE_FACTOR = 0.22f;

    private static final float ROTATION_STIFFNESS = 700f;
    private static final float ROTATION_DAMPING = 0.78f;

    private static final float MAX_RELEASE_VELOCITY = 480f;

    // ---------------------------------------------------------
    // REGISTRY
    // ---------------------------------------------------------

    private static final ArrayMap<View, Controller> CONTROLLERS =
            new ArrayMap<>();

    /** Guards every access to CONTROLLERS. */
    private static final Object LOCK = new Object();

    private DroidPressBounce() {
        // Utility class.
    }

    // =========================================================
    // STATIC API
    // =========================================================

    public static void attach(@NonNull View view) {

        try {
            // Remove any existing controller to avoid duplicate
            // listeners / animations.
            detach(view);

            Controller controller = new Controller(view);

            synchronized (LOCK) {
                CONTROLLERS.put(view, controller);
            }

            controller.attach();

        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    public static void detach(@NonNull View view) {

        try {
            Controller controller;
            synchronized (LOCK) {
                controller = CONTROLLERS.remove(view);
            }

            if (controller != null) {
                controller.detach();
            }

        } catch (Throwable t) {
            Log.e(TAG, "detach failed", t);
        }
    }

    public static void detachAll() {

        try {
            Controller[] controllers;
            synchronized (LOCK) {
                controllers = CONTROLLERS.values().toArray(
                        new Controller[CONTROLLERS.size()]);
                CONTROLLERS.clear();
            }

            for (Controller controller : controllers) {
                if (controller == null) continue;
                try {
                    controller.detach();
                } catch (Throwable t) {
                    Log.e(TAG, "detachAll: controller.detach failed", t);
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "detachAll failed", t);
        }
    }

    // =========================================================
    // CONTROLLER
    // =========================================================

    private static final class Controller {

        private final View view;
        private final float initialRotation;

        // These may be null if SpringAnimation construction fails on
        // some device; every use is null-checked.
        @Nullable private final SpringAnimation scaleX;
        @Nullable private final SpringAnimation scaleY;
        @Nullable private final SpringAnimation rotation;

        @Nullable private VelocityTracker velocityTracker;

        private float touchStartX;
        private float touchStartRotation;

        private boolean attached;

        Controller(@NonNull View view) {

            this.view = view;

            float startRotation = 0f;
            try {
                startRotation = view.getRotation();
            } catch (Throwable t) {
                Log.e(TAG, "view.getRotation failed", t);
            }
            this.initialRotation = startRotation;

            this.scaleX = safeCreateScaleSpring(
                    view, DynamicAnimation.SCALE_X);
            this.scaleY = safeCreateScaleSpring(
                    view, DynamicAnimation.SCALE_Y);

            this.rotation = safeCreateRotationSpring(view, initialRotation);
        }

        // -------------------------------------------------
        // Attach / detach
        // -------------------------------------------------

        void attach() {

            if (attached) return;

            try {
                view.setOnTouchListener(
                        new View.OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                try {
                                    return handleTouch(v, event);
                                } catch (Throwable t) {
                                    Log.e(TAG, "onTouch failed", t);
                                    return false;
                                }
                            }
                        });
                attached = true;
            } catch (Throwable t) {
                Log.e(TAG, "Controller.attach failed", t);
            }
        }

        void detach() {

            try {
                // Idempotent: always clean up animations and tracker,
                // even on the partial-construction path.
                safeCancel(scaleX);
                safeCancel(scaleY);
                safeCancel(rotation);

                recycleVelocityTracker();

                if (!attached) return;
                attached = false;

                try {
                    view.setOnTouchListener(null);
                } catch (Throwable t) {
                    Log.e(TAG, "setOnTouchListener(null) failed", t);
                }

            } catch (Throwable t) {
                Log.e(TAG, "Controller.detach failed", t);
            }
        }

        // -------------------------------------------------
        // Touch handling
        // -------------------------------------------------

        private boolean handleTouch(
                @NonNull View v,
                @Nullable MotionEvent event) {

            if (!attached || event == null) return false;

            try {
                switch (event.getActionMasked()) {

                    case MotionEvent.ACTION_DOWN:
                        return onDown(v, event);

                    case MotionEvent.ACTION_MOVE:
                        return onMove(v, event);

                    case MotionEvent.ACTION_UP:
                        return onUp(v, event);

                    case MotionEvent.ACTION_CANCEL:
                        return onCancel(v);

                    default:
                        // ACTION_POINTER_DOWN / ACTION_POINTER_UP and
                        // anything else: ignore. Single-pointer drag
                        // only, which matches the original behaviour.
                        return false;
                }
            } catch (Throwable t) {
                Log.e(TAG, "handleTouch failed", t);
                return false;
            }
        }

        private boolean onDown(
                @NonNull View v,
                @NonNull MotionEvent event) {

            try {
                touchStartX = event.getX();
                touchStartRotation = v.getRotation();
            } catch (Throwable t) {
                Log.e(TAG, "onDown read failed", t);
                return false;
            }

            // Stop any in-flight return / press animation.
            safeCancel(rotation);
            safeCancel(scaleX);
            safeCancel(scaleY);

            // Fresh tracker for this gesture.
            recycleVelocityTracker();
            velocityTracker = safeObtainTracker(event);

            // Compress the droid.
            safeAnimateTo(scaleX, PRESSED_SCALE);
            safeAnimateTo(scaleY, PRESSED_SCALE);

            // Return false so normal click handling continues.
            return false;
        }

        private boolean onMove(
                @NonNull View v,
                @NonNull MotionEvent event) {

            addMovementSafe(event);

            float distance;
            float width;
            try {
                distance = event.getX() - touchStartX;
                width = Math.max(v.getWidth(), 1);
            } catch (Throwable t) {
                Log.e(TAG, "onMove read failed", t);
                return false;
            }

            float resistanceDistance = width * TILT_DISTANCE_FACTOR;
            float normalized = distance / resistanceDistance;

            float tilt;
            try {
                tilt = (float) Math.tanh(normalized) * MAX_TILT;
            } catch (Throwable t) {
                tilt = 0f;
            }

            float targetRotation = touchStartRotation + tilt;

            // Spring follows the finger.
            safeAnimateTo(rotation, targetRotation);

            return false;
        }

        private boolean onUp(
                @NonNull View v,
                @NonNull MotionEvent event) {

            float releaseVelocity = 0f;

            try {
                addMovementSafe(event);
                releaseVelocity = computeReleaseVelocity(v, event);
                releaseVelocity = clamp(
                        releaseVelocity,
                        -MAX_RELEASE_VELOCITY,
                        MAX_RELEASE_VELOCITY);
            } catch (Throwable t) {
                Log.e(TAG, "onUp velocity read failed", t);
                releaseVelocity = 0f;
            }

            // Return to the rotation that existed at ACTION_DOWN.
            float currentRotation;
            try {
                currentRotation = v.getRotation();
            } catch (Throwable t) {
                currentRotation = touchStartRotation;
            }

            safeCancel(rotation);
            safeSetStartValue(rotation, currentRotation);
            safeSetStartVelocity(rotation, releaseVelocity);
            safeAnimateTo(rotation, touchStartRotation);

            recycleVelocityTracker();

            // Release scale.
            safeAnimateTo(scaleX, NORMAL_SCALE);
            safeAnimateTo(scaleY, NORMAL_SCALE);

            return false;
        }

        private boolean onCancel(@NonNull View v) {

            recycleVelocityTracker();

            float currentRotation;
            try {
                currentRotation = v.getRotation();
            } catch (Throwable t) {
                currentRotation = touchStartRotation;
            }

            safeCancel(rotation);
            safeSetStartValue(rotation, currentRotation);
            safeSetStartVelocity(rotation, 0f);
            safeAnimateTo(rotation, touchStartRotation);

            safeAnimateTo(scaleX, NORMAL_SCALE);
            safeAnimateTo(scaleY, NORMAL_SCALE);

            return false;
        }

        // -------------------------------------------------
        // Velocity helpers
        // -------------------------------------------------

        /**
         * Adds the current event to the tracker. Returns silently if
         * the tracker is null (obtain() failed under memory pressure).
         */
        private void addMovementSafe(@NonNull MotionEvent event) {
            try {
                VelocityTracker tracker = velocityTracker;
                if (tracker != null) {
                    tracker.addMovement(event);
                }
            } catch (Throwable t) {
                Log.e(TAG, "addMovement failed", t);
            }
        }

        /**
         * Obtains a fresh VelocityTracker. Returns null on failure
         * (obtain() can return null, though rarely).
         */
        @Nullable
        private VelocityTracker safeObtainTracker(@NonNull MotionEvent event) {
            try {
                VelocityTracker tracker = VelocityTracker.obtain();
                if (tracker != null) {
                    tracker.addMovement(event);
                }
                return tracker;
            } catch (Throwable t) {
                Log.e(TAG, "obtain VelocityTracker failed", t);
                return null;
            }
        }

        /**
         * Computes the angular release velocity, guarding against
         * missing tracker data and NaN results.
         */
        private float computeReleaseVelocity(
                @NonNull View v,
                @NonNull MotionEvent event) {

            VelocityTracker tracker = velocityTracker;
            if (tracker == null) return 0f;

            try {
                tracker.computeCurrentVelocity(1000);
            } catch (Throwable t) {
                Log.e(TAG, "computeCurrentVelocity failed", t);
                return 0f;
            }

            float xVelocity;
            try {
                xVelocity = tracker.getXVelocity();
            } catch (Throwable t) {
                Log.e(TAG, "getXVelocity failed", t);
                return 0f;
            }

            if (Float.isNaN(xVelocity) || Float.isInfinite(xVelocity)) {
                return 0f;
            }

            return calculateAngularVelocity(
                    v,
                    touchStartX,
                    event.getX(),
                    xVelocity);
        }

        private float calculateAngularVelocity(
                @NonNull View view,
                float startX,
                float currentX,
                float xVelocity) {

            try {
                float width = Math.max(view.getWidth(), 1);
                float resistanceDistance = width * TILT_DISTANCE_FACTOR;

                float distance = currentX - startX;
                float normalized = distance / resistanceDistance;
                float tanh = (float) Math.tanh(normalized);

                // Derivative of tanh(x) is 1 - tanh(x)^2.
                float response = 1f - (tanh * tanh);
                float degreesPerPixel = MAX_TILT / resistanceDistance;

                float result = xVelocity * degreesPerPixel * response;

                if (Float.isNaN(result) || Float.isInfinite(result)) {
                    return 0f;
                }
                return result;

            } catch (Throwable t) {
                Log.e(TAG, "calculateAngularVelocity failed", t);
                return 0f;
            }
        }

        // -------------------------------------------------
        // Safe spring helpers
        // -------------------------------------------------

        @Nullable
        private SpringAnimation safeCreateScaleSpring(
                @NonNull View view,
                @NonNull DynamicAnimation.ViewProperty property) {

            try {
                SpringAnimation animation = new SpringAnimation(view, property);
                SpringForce spring = new SpringForce(NORMAL_SCALE);
                spring.setStiffness(SCALE_STIFFNESS);
                spring.setDampingRatio(SCALE_DAMPING);
                animation.setSpring(spring);
                return animation;
            } catch (Throwable t) {
                Log.e(TAG, "createScaleSpring failed", t);
                return null;
            }
        }

        @Nullable
        private SpringAnimation safeCreateRotationSpring(
                @NonNull View view,
                float initialRotation) {

            try {
                SpringAnimation animation =
                        new SpringAnimation(view, DynamicAnimation.ROTATION);

                SpringForce spring = new SpringForce(initialRotation);
                spring.setStiffness(ROTATION_STIFFNESS);
                spring.setDampingRatio(ROTATION_DAMPING);
                animation.setSpring(spring);

                animation.setMinValue(initialRotation - MAX_TILT);
                animation.setMaxValue(initialRotation + MAX_TILT);

                return animation;
            } catch (Throwable t) {
                Log.e(TAG, "createRotationSpring failed", t);
                return null;
            }
        }

        private void safeCancel(@Nullable SpringAnimation animation) {
            if (animation == null) return;
            try {
                animation.cancel();
            } catch (Throwable t) {
                Log.e(TAG, "animation.cancel failed", t);
            }
        }

        private void safeAnimateTo(
                @Nullable SpringAnimation animation,
                float target) {

            if (animation == null) return;
            try {
                animation.animateToFinalPosition(target);
            } catch (Throwable t) {
                Log.e(TAG, "animateToFinalPosition failed", t);
            }
        }

        private void safeSetStartValue(
                @Nullable SpringAnimation animation,
                float value) {

            if (animation == null) return;
            try {
                animation.setStartValue(value);
            } catch (Throwable t) {
                Log.e(TAG, "setStartValue failed", t);
            }
        }

        private void safeSetStartVelocity(
                @Nullable SpringAnimation animation,
                float velocity) {

            if (animation == null) return;
            try {
                animation.setStartVelocity(velocity);
            } catch (Throwable t) {
                Log.e(TAG, "setStartVelocity failed", t);
            }
        }

        // -------------------------------------------------
        // Misc helpers
        // -------------------------------------------------

        private float clamp(float value, float min, float max) {
            if (value < min) return min;
            if (value > max) return max;
            return value;
        }

        private void recycleVelocityTracker() {
            VelocityTracker tracker = velocityTracker;
            if (tracker == null) return;
            try {
                tracker.recycle();
            } catch (Throwable t) {
                Log.e(TAG, "VelocityTracker.recycle failed", t);
            } finally {
                velocityTracker = null;
            }
        }
    }
}
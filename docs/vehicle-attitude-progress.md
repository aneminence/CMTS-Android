# Vehicle attitude experimental implementation (2026-11-11)

Not release acceptance. No vehicle reference measurements yet.

## Audit

CaptureService.onSensorChanged derives vehicle.attitude from TYPE_ROTATION_VECTOR;
phone.attitude uses Android getOrientation. Accelerometer m/s², gyro rad/s,
attitude degrees. vehicleAxes is FLU: device Y, -X, Z. Screen rotation does not
change capture axes. vehicleAttitude corresponds to ZYX Euler, positive pitch
nose down. captureMountZero snapshots gyro and attitude and creates MountBasis;
this is retained for legacy display only, not reused as estimator calibration.
No proven conversion defect was fixed in this increment.

Phone GNSS onLocationChanged records elapsedRealtimeNanos, speed, bearing,
horizontal/vertical accuracy and speed accuracy. Actual update frequency must be
measured from logs; requested rates are not measured rates. NmeaParser combines
HPR heading and RMC course into headingDeg: this is unsuitable as an unqualified
velocity direction. External GNSS is excluded from new motion assistance.
No verified synchronized 3D velocity, antenna lever arm or body/navigation
heading relationship is available; quantitative compensation is disabled.

## Implementation and use

Dashboard experimental comparison switch shows the independent estimate. Existing
display and zero buttons retain their behavior. Installation currently requires
screen up/top forward. Confirm parked and tap calibration with reliable phone
GNSS speed <0.2m/s, speed accuracy <=0.3m/s. Three seconds of stable IMU initialize
tilt and residual gyro bias. Gravity is retained; yaw begins arbitrary. Bias is
frozen afterwards. Restart calibration after sensor gap/saturation.

Core quaternion is wxyz, active vehicle-to-navigation. Navigation is local level
with arbitrary heading, not geographically aligned ENU. Gain 1/s; acceleration
rejection scales: norm 0.3m/s², innovation 3 degrees, jerk 5m/s³; one-second hold,
one-second exponential recovery. No elapsed-time forced acceptance. Maximum
integration gap 0.2s; accel age 0.1s. GNSS age 1.5s; motion threshold 0.15m/s²;
low-speed course excluded below 2m/s; yaw-rate risk 0.03rad/s assumes fixed FLU
installation. Thresholds are engineering heuristics, not accuracy confidence.

estimate.* CSV channels save attitude, quaternion, diagnostics, initial quaternion
and bias without modifying phone.* or vehicle.*. Replay loads derived channels.
Legacy output protocol remains unchanged; no new remote fields yet. Old files
without raw IMU cannot be recomputed. A supported offline replay/recompute command
and complete parameter manifest are still pending.

## Validation and remaining work

Three new JUnit tests passed: 5/20s longitudinal 0.3g and lateral 0.4g with
consistent yaw rate; slow real 3-degree tilt and hold; duplicate/gap handling.
This is only a subset of required tests. Combined motion/tilt, noise/bias drift,
GNSS failure/source switching, calibration lifecycle, repeatability, protocol
and UI acceptance still require fuller regression. No phone accuracy claim.

Known limitations: small slowly growing acceleration can remain ambiguous;
six-axis yaw drifts; arbitrary mounting unsupported; external GNSS gate disabled;
phone bearing accuracy is not yet consumed; calibration currently requires GNSS;
no estimator display-zero control; no offline recompute tool or remote extension.
Do not label this task complete on the basis of this experimental increment.

## Follow-up regression

Added causal AttitudeRecompute driver and recorded estimate.context per raw event
(request generation, selected GNSS source, algorithm version, fixed FLU mapping).
133 unit tests pass, including 7 estimator/pipeline tests. Combined 0.3g/3-degree
pitch passes <=1 degree at every sample for 5s and 20s. Deterministic recomputation,
raw input preservation, stationary calibration bias, and stable moving rejection
are tested. GNSS speed quality now checks both endpoints; phone bearing accuracy
is saved at GPS index 8 and required for course gating. Source switch clears aid;
duplicate gyro cannot accumulate calibration. Gap/saturation publishes null angles.

Still pending: CSV-to-event reader/user recompute entry, full config manifest,
combined lateral/roll trajectory, GNSS wrap/delay/dropout automated scenarios,
noise/drift quantitative report, UI/physical phone acceptance and remote extensions.

## CSV recompute and coupled-turn tests

Records screen now offers 重算 for closed sessions. Strict reader verifies per-event
context/version/timestamp and preserves arrival order; derived output goes to
attitude-recomputed.csv, never samples.csv. CSV round-trip equality test passes.
Files missing calibration history still explicitly fail; mid-run state restore is
not implemented. Reader currently loads events in memory, so large-file bounded
streaming remains a limitation.

Coupled Rz(yaw)*Rx(roll) test with 0.4g lateral acceleration and true 3-degree roll:
5s maximum roll error 9.4107e-7 deg; 20s maximum 9.8507e-7 deg.
60s scenario losing gravity reference after 2s, residual gyro bias 0.001rad/s:
roll residual 3.37931 deg, trusted-reference age 57.91s. This documents drift;
it is not an accuracy guarantee. Build and lint passed; all tests rerun successfully.
GNSS scenario matrix, streaming recompute, remote extension and phone acceptance
remain incomplete. No device installation performed in this increment.

## GNSS and streaming follow-up

File export now streams both input and derived output with bounded event memory;
failed exports remove the temporary file and preserve prior outputs. In-memory
fromCsv remains a test convenience. Added independent estimate.attitude/status
remote payload fields with measurement timestamp and quality; legacy fields retain
existing meanings. Receiver UI support has not been verified.

GNSS tests cover 359-to-1 degree wrap, low-speed direction jump, poor speed quality,
age expiry and source switch. These are gating unit tests, not complete vehicle
trajectory/reference validation. Recalibration now publishes null angles instead
of leaving the old pose visible; discontinuity invalidates quaternion as well.
Session metadata includes frame, version, thresholds and explicit disabled
quantitative compensation and unknown lever arm. 139 tests pass.

Remaining: slow-growing acceleration/noise quantitative scenarios; complete
pause/resume and landscape phone UI acceptance; arbitrary installation calibration
(not currently supported, fixed screen-up/top-forward remains required); external
GNSS typed course integration; mid-run checkpoint restoration for recompute.
Real vehicle reference validation and receiver integration remain unverified.

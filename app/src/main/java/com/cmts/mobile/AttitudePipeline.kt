package com.cmts.mobile

import kotlin.math.*

/** All methods run on capture thread. Commands are delivered as a volatile generation. */
internal class AttitudePipeline {
    private var generation = 0
    private var core: VehicleAttitudeEstimator? = null
    private var acc: Sample? = null
    private var gps: Sample? = null
    private var previousGps: Sample? = null
    private var motion = false
    private var lastPhoneSource: Boolean? = null
    private var lastGyro: Long? = null
    private var initializedAt: Long? = null
    private val calibration = mutableListOf<Pair<List<Double>, List<Double>>>()
    private var calibrationStart = 0L
    private var bias = listOf(0.0,0.0,0.0)
    private var initial = listOf(1.0,0.0,0.0,0.0)
    fun observe(s: Sample, request: Int, phoneGnss: Boolean): List<Sample> {
        if (lastPhoneSource != phoneGnss) {
            gps=null; previousGps=null; motion=false; calibration.clear(); calibrationStart=0L
            lastPhoneSource=phoneGnss
        }
        if (request != generation) {
            generation = request; core = null; initializedAt=null; calibration.clear(); calibrationStart = 0L
        }
        if (s.source == "phone.gps") {
            if (gps != null && s.nanos <= gps!!.nanos) return emptyList()
            previousGps = gps; gps = if (phoneGnss && s.quality == "provider_reported") s else null
            motion = false
            val old = previousGps; val now = gps
            if (old != null && now != null && now.nanos > old.nanos) {
                val dt = (now.nanos-old.nanos)*1e-9
                val v = now.values.getOrNull(3); val u = old.values.getOrNull(3)
                val b = now.values.getOrNull(4); val a = old.values.getOrNull(4)
                val validPair = listOf(old, now).all { p ->
                    p.values.getOrNull(7)?.let { it in 0.0..0.3 } == true
                }
                if (validPair && dt in 0.1..2.0 && v != null && u != null && v >= 0 && u >= 0) {
                    motion = abs(v-u)/dt > 0.15
                    // Course is motion direction, never installation heading. Ignore low-speed course.
                    val courseReliable = listOf(old,now).all { p -> p.values.getOrNull(8)?.let { it in 0.0..10.0 } == true }
                    if (courseReliable && v > 2 && u > 2 && a != null && b != null)
                        motion = motion || abs(wrapSignedDegrees(b-a))*PI/180/dt*min(v,u) > 0.15
                }
            }
            return emptyList()
        }
        if (s.source == "vehicle.accel") {
            if (acc == null || s.nanos > acc!!.nanos) acc = s
            s.values.map { it ?: return emptyList() }.let { core?.accelerometer(s.nanos,it) }
            return emptyList()
        }
        if (s.source != "vehicle.gyro") return emptyList()
        val w = s.values.map { it ?: return emptyList() }
        if (w.size != 3) return emptyList()
        val previousGyro=lastGyro
        if (previousGyro != null && s.nanos <= previousGyro) return emptyList()
        if (previousGyro != null && s.nanos-previousGyro > 200_000_000L) {
            calibration.clear(); calibrationStart=0L
        }
        lastGyro=s.nanos
        val aSample = acc ?: return emptyList()
        val a = aSample.values.map { it ?: return emptyList() }
        if (a.size != 3) return emptyList()
        val g = gps?.takeIf { phoneGnss && s.nanos-it.nanos in 0..1_500_000_000L }
        val speed = g?.values?.getOrNull(3)
        val accuracy = g?.values?.getOrNull(7)
        val reliable = speed != null && speed >= 0 && accuracy != null && accuracy in 0.0..0.3
        if (core == null) {
            val parked = generation > 0 && reliable && speed!! < 0.2 &&
                s.nanos-aSample.nanos in 0..100_000_000L && w.all { abs(it)<0.05 } &&
                abs(sqrt(a.sumOf { it*it })-9.80665)<0.2
            if (!parked) { calibration.clear(); calibrationStart=0L }
            else {
                if (calibrationStart == 0L) calibrationStart=s.nanos
                calibration.add(w to a)
                if (s.nanos-calibrationStart >= 3_000_000_000L && calibration.size >= 100) {
                    val meanW = List(3) { i -> calibration.map { it.first[i] }.average() }
                    val meanA = List(3) { i -> calibration.map { it.second[i] }.average() }
                    val stable = calibration.all { p -> (0..2).all { abs(p.first[it]-meanW[it])<0.01 && abs(p.second[it]-meanA[it])<0.12 } }
                    if (stable) {
                        bias=meanW
                        val n=sqrt(meanA.sumOf { it*it }); val z=meanA[2]/n
                        if (z > -0.99) {
                            initial=listOf(1+z,meanA[1]/n,-meanA[0]/n,0.0)
                            val qn=sqrt(initial.sumOf { it*it }); initial=initial.map { it/qn }
                            core=VehicleAttitudeEstimator(initial)
                            initializedAt=s.nanos
                            core!!.accelerometer(aSample.nanos,a)
                        }
                    }
                    calibration.clear(); calibrationStart=0L
                }
            }
            if (core == null) return listOf(
                Sample("estimate.attitude",s.nanos,null,listOf(null,null,null),"initializing"),
                Sample("estimate.status",s.nanos,null,listOf(0.0),"初始化：停车后点击校准；需可靠手机GNSS低速及连续3秒稳定IMU"))
        }
        val c=core!!
        c.motionRisk = (reliable && motion) || abs(w[2]-bias[2])>0.03
        val o=c.gyroscope(s.nanos,w,bias) ?: return emptyList()
        val q=o.wxyz
        val roll=atan2(2*(q[0]*q[1]+q[2]*q[3]),1-2*(q[1]*q[1]+q[2]*q[2]))*180/PI
        val pitch=asin((2*(q[0]*q[2]-q[3]*q[1])).coerceIn(-1.0,1.0))*180/PI
        val yaw=atan2(2*(q[0]*q[3]+q[1]*q[2]),1-2*(q[2]*q[2]+q[3]*q[3]))*180/PI
        val invalid = o.reason.contains("reinitialize")
        val state=when {
            o.reason.contains("reinitialize") -> "降级：采样中断/饱和，请重新停车校准"
            (o.trustedAgeSeconds ?: ((s.nanos-(initializedAt ?: s.nanos))*1e-9))>5 -> "降级：缺少可信倾斜参考"
            o.reason=="dynamic_gyro" -> "动态时陀螺仪主导"
            reliable -> "GNSS辅助"
            else -> "正常融合（GNSS不可用）"
        }
        val quality="$state;${o.reason};${VehicleAttitudeEstimator.VERSION}"
        return listOf(
            Sample("estimate.attitude",s.nanos,null,if(invalid) listOf(null,null,null) else listOf(roll,pitch,yaw),quality),
            Sample("estimate.quaternion",s.nanos,null,if(invalid) listOf(null,null,null,null) else q,quality),
            Sample("estimate.status",s.nanos,null,listOf(o.weight,o.accelAgeSeconds,o.trustedAgeSeconds,g?.let { (s.nanos-it.nanos)*1e-9 }),quality),
            Sample("estimate.initial",s.nanos,null,initial,VehicleAttitudeEstimator.VERSION),
            Sample("estimate.bias",s.nanos,null,bias,"parked_only_rad_s"))
    }
}

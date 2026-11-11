package com.cmts.mobile

import java.io.File

/** Causal offline driver, preserving recorded arrival order (never sorts future observations).
 * Requires full raw events from before explicit parked calibration. A mid-run CSV without
 * that history is rejected rather than seeded with a fabricated horizontal attitude.
 */
internal object AttitudeRecompute {
    /** Strict CSV reader. Context precedes the associated raw event in capture order. */
    fun fromCsv(file: File): List<Sample> {
        val result=mutableListOf<Sample>()
        streamCsv(file) { result.add(it) }
        return result
    }
    private fun streamCsv(file: File, emit: (Sample) -> Unit) {
        var context: Sample? = null
        val pipeline=AttitudePipeline()
        var initialized=false
        file.bufferedReader().useLines { lines -> lines.drop(1).forEach { line ->
            val c=line.split(',')
            require(c.size>=6) { "Malformed CSV row" }
            val source=c[1]
            if(source=="estimate.context" || source in setOf("phone.accel","phone.gyro","phone.gps")) {
                val s=Sample(source,c[2].toLong(),c[4].toLongOrNull(),c.drop(6).map { if(it.isBlank()) null else it.toDouble() },c[5])
                if(source=="estimate.context") context=s else {
                    val ctx=requireNotNull(context) { "不能重算：缺少校准/数据源上下文" }
                    require(ctx.nanos==s.nanos && ctx.quality=="${VehicleAttitudeEstimator.VERSION};$source;fixed_FLU") { "不能重算：上下文或算法版本不匹配" }
                    val mapped=if(source=="phone.accel" || source=="phone.gyro") {
                        require(s.values.size==3) { "Invalid raw IMU" }
                        s.copy(source=source.replace("phone.","vehicle."),values=vehicleAxes(s.values.map { requireNotNull(it) }))
                    } else s
                    pipeline.observe(mapped,requireNotNull(ctx.values.getOrNull(0)).toInt(),ctx.values.getOrNull(1)==1.0).forEach {
                        if(it.source=="estimate.attitude" && it.values.all { v -> v!=null }) initialized=true
                        emit(it)
                    }
                    context=null
                }
            }
        } }
        require(initialized) { "不能重算：缺少完整停车初始化历史" }
    }
    fun export(session: File): File {
        val output=File(session,"attitude-recomputed.csv")
        val temporary=File(session,"attitude-recomputed.csv.tmp")
        try { temporary.bufferedWriter().use { writer ->
            writer.appendLine("sequence,source,nanos,callbackNanos,utcMillis,quality,values")
            var sequence=0L
            streamCsv(File(session,"samples.csv")) { s -> writer.appendLine(s.csv(++sequence,s.nanos)) }
        }
        java.nio.file.Files.move(temporary.toPath(),output.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return output
    }
    data class Event(val sample: Sample, val calibrationRequest: Int, val phoneGnss: Boolean)
    fun run(events: List<Event>): List<Sample> {
        require(events.any { it.sample.source == "phone.accel" }) { "Cannot recompute: raw accelerometer missing" }
        require(events.any { it.sample.source == "phone.gyro" }) { "Cannot recompute: raw gyroscope missing" }
        require(events.any { it.calibrationRequest > 0 }) { "Cannot recompute: parked calibration history missing" }
        val pipeline=AttitudePipeline()
        val output=mutableListOf<Sample>()
        for(e in events) {
            val s=e.sample
            val mapped=when(s.source) {
                "phone.accel", "phone.gyro" -> s.copy(source=s.source.replace("phone.","vehicle."),
                    values=vehicleAxes(s.values.map { requireNotNull(it) }))
                "phone.gps" -> s
                else -> continue
            }
            output += pipeline.observe(mapped,e.calibrationRequest,e.phoneGnss)
        }
        require(output.any { it.source=="estimate.attitude" && it.values.all { v -> v!=null } }) { "Cannot recompute: no successful parked initialization" }
        return output
    }
}

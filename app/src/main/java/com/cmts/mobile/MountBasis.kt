package com.cmts.mobile

import kotlin.math.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal data class V3(val x: Double, val y: Double, val z: Double) {
 operator fun plus(b: V3) = V3(x+b.x,y+b.y,z+b.z)
 operator fun times(k: Double) = V3(x*k,y*k,z*k)
 fun dot(b: V3) = x*b.x+y*b.y+z*b.z
 fun cross(b: V3) = V3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
 fun length() = sqrt(dot(this))
 fun unit() = this * (1.0/length())
 companion object {
  fun read(v: List<Double?>?): V3? {
   if(v == null || v.size < 3 || v.take(3).any { it == null || !it.isFinite() }) return null
   return V3(v[0]!!,v[1]!!,v[2]!!)
  }
 }
}

// All basis vectors use the recorder's fixed vehicleAxes convention.
internal data class MountBasis(val forward: V3, val left: V3, val up: V3) {
 fun project(v: List<Double?>?): List<Double>? = V3.read(v)?.let { listOf(it.dot(forward),it.dot(left),it.dot(up)) }

 fun attitude(v: List<Double?>?, reference: List<Double>): List<Double>? {
  val a=V3.read(v) ?: return null
  fun world(axis: V3, e: V3): V3 {
   val r=Math.toRadians(e.x); val p=Math.toRadians(e.y); val h=Math.toRadians(e.z)
   val y=cos(r)*axis.y-sin(r)*axis.z
   val z=sin(r)*axis.y+cos(r)*axis.z
   val x=cos(p)*axis.x+sin(p)*z
   return V3(cos(h)*x-sin(h)*y,sin(h)*x+cos(h)*y,-sin(p)*axis.x+cos(p)*z)
  }
  val f=world(forward,a)
  val base=world(forward,V3.read(reference) ?: return null)
  return listOf(Math.toDegrees(atan2(world(left,a).z,world(up,a).z)),
   Math.toDegrees(asin((-f.z).coerceIn(-1.0,1.0))),
   wrapSignedDegrees(Math.toDegrees(atan2(f.y,f.x)-atan2(base.y,base.x))))
 }
 companion object {
  fun from(up: V3, hint: V3): MountBasis? {
   val horizontal=hint + up*(-hint.dot(up))
   if(horizontal.length()<0.1) return null
   val f=horizontal.unit()
   return MountBasis(f,up.cross(f),up)
  }
  fun stationary(angles: List<Double>): MountBasis {
   val up=V3.read(gravityVehicleFromAttitude(angles[0],angles[1],1.0))!!
   val hint=if(abs(up.z)<0.7) V3(0.0,0.0,-1.0) else V3(1.0,0.0,0.0)
   return from(up,hint) ?: from(up,V3(0.0,1.0,0.0))!!
  }
 }
}

internal object LaunchCalibration {
 private var armedAt=0L
 private var sum=V3(0.0,0.0,0.0)
 private var impulse=0.0
 private var duration=0.0
 private var last=0L
 var status by androidx.compose.runtime.mutableStateOf("")
  private set
 @Synchronized fun cancel(message: String="") { armedAt=0; status=message }
 @Synchronized fun arm(now: Long, gps: Sample?) {
  if(gps==null || now-gps.nanos !in 0..2_500_000_000L || (gps.values.getOrNull(3) ?: -1.0) !in 0.0..0.5) {
   cancel("请停车并等待 GNSS 速度就绪后重试"); return
  }
  armedAt=now; last=0; impulse=0.0; duration=0.0; sum=V3(0.0,0.0,0.0)
  status="已就绪，请向前直线平稳起步"
 }
 @Synchronized fun observe(s: Sample, latest: Map<String,Sample>) {
  if(armedAt==0L || s.nanos<armedAt) return
  if(s.nanos-armedAt>45_000_000_000L) { cancel("起步标定超时，可停车重试"); return }
  val zero=Live.mountZero
  val basis=zero.basis ?: return
  if(s.source=="phone.gps") {
   if((s.values.getOrNull(3) ?: 0.0)<2.0) return
   if(duration>=0.65 && impulse>=1.1 && sum.length()>=0.92*impulse) {
    val refined=MountBasis.from(basis.up,sum.unit()) ?: return
    Live.mountZero=zero.copy(basis=refined)
    cancel("直线起步标定完成")
   } else cancel("起步数据不足或方向不稳定，请停车重试")
   return
  }
  if(s.source!="vehicle.accel") return
  val dt=(s.nanos-last)/1e9
  last=s.nanos
  if(dt !in 0.001..0.12) return
  val gyro=latest["vehicle.gyro"] ?: return
  val att=latest["vehicle.attitude"] ?: return
  if(abs(s.nanos-gyro.nanos)>200_000_000 || abs(s.nanos-att.nanos)>200_000_000) return
  if((V3.read(applyMountZeroLinear(gyro.values,zero.gyro))?.length() ?: 1.0)>0.14) {
   cancel("检测到转动，请停车后直线重试"); return
  }
  val a=V3.read(vehicleLinearAccel(s.values,null,att.values)) ?: return
  val h=a+basis.up*(-a.dot(basis.up))
  if(h.length() in 0.45..5.0) { sum+=h*dt; impulse+=h.length()*dt; duration+=dt }
 }
}


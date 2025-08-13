/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.algorithms

// Error-State EKF core (no Android listeners) — pure logic. Forward sensor/location data from your Service.
// Wear-focused features retained: gyro-only predict, dynamic Q, GNSS pos/vel updates, baro vertical, ECEF/ENU,
// magnetometer yaw (requires world mag reference via setWorldMagReference(...)).

import kotlin.math.*
import java.util.ArrayDeque

class EKFSensorFusion(
    // --- Noise parameters (tune per device) ---
    private val sigmaGyro: Double = Math.toRadians(0.02),   // rad/s   (ARW)
    private val sigmaAccel: Double = 0.12,                  // m/s^2   (specific-force noise)
    private val sigmaBg: Double = Math.toRadians(0.0015),   // rad/s/sqrt(s)  gyro bias RW
    private val sigmaBa: Double = 0.03,                     // m/s^2/sqrt(s)  accel bias RW
    // GPS measurement floors (1σ)
    private val rFloorHoriz: Double = 3.0,
    private val rFloorVert: Double = 4.5,
    // Gating thresholds (Chi-square)
    private val gateGps3d: Double = 9.49,  // df=3
    private val gateVel2d: Double = 5.99,  // df=2
    private val gateAcc3d: Double = 9.49,  // df=3
    private val gateMag3d: Double = 7.81,  // df=3
    private val gateAlt1d: Double = 3.84,  // df=1
    // Accel gravity-correction usage
    private val linAccelThresh: Double = 0.20,              // m/s^2
    private val omegaThresh: Double = Math.toRadians(0.05), // rad/s
    // ZUPT (stationary) parameters
    private val zuptLinAThresh: Double = 0.15,              // m/s^2
    private val zuptOmegaThresh: Double = Math.toRadians(0.05), // rad/s
    private val zuptDwellNs: Long = 200_000_000L,           // 200 ms dwell
    private val zuptR: Double = 0.05                        // m/s stdev
) {
    // --- Nominal state ---
    @Volatile private var pE = 0.0; @Volatile private var pN = 0.0; @Volatile private var pU = 0.0
    @Volatile private var vE = 0.0; @Volatile private var vN = 0.0; @Volatile private var vU = 0.0
    @Volatile private var q0 = 1.0; @Volatile private var q1 = 0.0; @Volatile private var q2 = 0.0; @Volatile private var q3 = 0.0
    @Volatile private var bgx = 0.0; @Volatile private var bgy = 0.0; @Volatile private var bgz = 0.0
    @Volatile private var bax = 0.0; @Volatile private var bay = 0.0; @Volatile private var baz = 0.0

    // Error covariance (15x15) in row-major
    private val P = DoubleArray(15*15)

    // Latest raw values (body frame) + flags
    private var ax = 0.0; private var ay = 0.0; private var az = 0.0; private var haveAcc = false
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0; private var haveGyro = false
    private var mx = 0.0; private var my = 0.0; private var mz = 0.0; private var haveMag = false
    private var magReliable = true

    private var lastGyroTsNs: Long = 0L
    private var lastOmegaMag = 0.0
    private var lastLinAMag = 0.0

    // ENU origin (WGS84 ECEF-based)
    private var originSet = false
    private var originLat = Double.NaN
    private var originLon = Double.NaN
    private var originAlt = 0.0
    private val a = 6378137.0
    private val f = 1.0/298.257223563
    private val e2 = f*(2.0 - f)
    private val b = a * (1.0 - f)
    private val ep2 = (a*a - b*b)/(b*b)
    private var ecef0 = DoubleArray(3)
    private var Re2n = DoubleArray(9) // ECEF->ENU rotation at origin
    private var Rn2e = DoubleArray(9) // ENU->ECEF rotation

    // Gravity in world (ENU)
    private val g = 9.80665
    private val g_w = doubleArrayOf(0.0, 0.0, -g)

    // Reference magnetic field (unit) in world ENU; set via setWorldMagReference()
    private var haveMref = false
    private val m_w = DoubleArray(3)
    private var expectedField_uT = 50.0

    // Barometer LPF & bias
    private var haveBaro = false
    private var baroAltLP = Double.NaN
    private var baroBias = 0.0

    // ZUPT dwell
    private var zuptStartNs: Long = 0L

    // Rolling linear-accel magnitude for noise scaling
    private val linAccWindow = java.util.ArrayDeque<Double>()
    private val linAccWindowMax = 100

    // --- Public snapshot ---
    data class Snapshot(
        val e: Double, val n: Double, val u: Double,
        val vE: Double, val vN: Double, val vU: Double,
        val q: DoubleArray, val bg: DoubleArray, val ba: DoubleArray,
        val lat: Double?, val lon: Double?, val alt: Double?
    )
    fun snapshot(): Snapshot {
        val (lat, lon, alt) = getCurrentPositionAsLatLng()
        return Snapshot(
            pE, pN, pU, vE, vN, vU,
            doubleArrayOf(q0,q1,q2,q3), doubleArrayOf(bgx,bgy,bgz), doubleArrayOf(bax,bay,baz),
            if (lat.isNaN()) null else lat, if (lon.isNaN()) null else lon, if (alt.isNaN()) null else alt
        )
    }

    // --- GPS duty-cycling helpers ---
    data class GpsNeedDebug(
        val posSigmaHoriz_m: Double,
        val velSigmaHoriz_mps: Double,
        val yawSigma_rad: Double,
        val speed_mps: Double,
        val originIsSet: Boolean,
        val haveMagRef: Boolean,
        val magIsReliable: Boolean
    )

    fun needsGps(
        posThreshHorizMeters: Double = 8.0,
        velThreshHorizMps: Double = 1.5,
        yawThreshRad: Double = Math.toRadians(12.0),
        movingSpeedThreshMps: Double = 0.30
    ): Boolean {
        if (!originSet) return true

        val pEE = P[0*15 + 0].coerceAtLeast(0.0)
        val pNN = P[1*15 + 1].coerceAtLeast(0.0)
        val posSigmaHoriz = kotlin.math.sqrt(pEE + pNN)

        val vEE = P[3*15 + 3].coerceAtLeast(0.0)
        val vNN = P[4*15 + 4].coerceAtLeast(0.0)
        val velSigmaHoriz = kotlin.math.sqrt(vEE + vNN)

        val yawSigma = kotlin.math.sqrt(P[8*15 + 8].coerceAtLeast(0.0))

        val speed = kotlin.math.sqrt(vE*vE + vN*vN)
        val moving = speed > movingSpeedThreshMps

        if (posSigmaHoriz > posThreshHorizMeters) return true
        if (moving && velSigmaHoriz > velThreshHorizMps) return true
        if (moving && (!haveMref || !magReliable) && yawSigma > yawThreshRad) return true

        return false
    }

    fun gpsNeedDebug(): GpsNeedDebug {
        val pEE = P[0*15 + 0].coerceAtLeast(0.0)
        val pNN = P[1*15 + 1].coerceAtLeast(0.0)
        val vEE = P[3*15 + 3].coerceAtLeast(0.0)
        val vNN = P[4*15 + 4].coerceAtLeast(0.0)
        return GpsNeedDebug(
            posSigmaHoriz_m = kotlin.math.sqrt(pEE + pNN),
            velSigmaHoriz_mps = kotlin.math.sqrt(vEE + vNN),
            yawSigma_rad = kotlin.math.sqrt(P[8*15 + 8].coerceAtLeast(0.0)),
            speed_mps = kotlin.math.sqrt(vE*vE + vN*vN),
            originIsSet = originSet,
            haveMagRef = haveMref,
            magIsReliable = magReliable
        )
    }

    // --- Initialization ---
    init {
        zero(P)
        setDiag(P, 0, 3, 25.0) // δp
        setDiag(P, 3, 3, 6.0)  // δv
        setDiag(P, 6, 3, (Math.toRadians(8.0)).pow(2.0)) // δθ
        setDiag(P, 9, 3, (Math.toRadians(1.0)).pow(2.0)) // δb_g
        setDiag(P, 12,3, (0.3).pow(2.0))                 // δb_a
    }

    // --- Sensor forwarding API ---
    fun handleGyro(tsNs: Long, wx: Double, wy: Double, wz: Double) {
        gx = wx; gy = wy; gz = wz; haveGyro = true
        var dt = if (lastGyroTsNs != 0L) (tsNs - lastGyroTsNs) * 1e-9 else 0.0
        lastGyroTsNs = tsNs
        if (dt <= 0.0) return
        dt = dt.coerceIn(1e-3, 0.02)

        predict(dt)

        val Rwb = quatToRot(q0,q1,q2,q3)
        val f_b = doubleArrayOf(ax - bax, ay - bay, az - baz)
        val f_w = matVec(Rwb, f_b)
        val aLinMag = sqrt(f_w[0]*f_w[0] + f_w[1]*f_w[1] + f_w[2]*f_w[2])
        lastLinAMag = aLinMag; pushLinAcc(aLinMag)
        lastOmegaMag = sqrt(wx*wx + wy*wy + wz*wz)

        if (haveAcc && aLinMag < linAccelThresh && lastOmegaMag < omegaThresh) {
            accelGravityUpdate()
        }
        if (haveMag && haveMref && magReliable) {
            magnetometerUpdate()
        }
        val stationary = (aLinMag < zuptLinAThresh && lastOmegaMag < zuptOmegaThresh)
        if (stationary) {
            if (zuptStartNs == 0L) zuptStartNs = tsNs
            val dwell = tsNs - zuptStartNs
            if (dwell >= zuptDwellNs) zuptUpdate()
        } else {
            zuptStartNs = 0L
        }
    }
    fun handleAccel(@Suppress("UNUSED_PARAMETER") tsNs: Long, ax: Double, ay: Double, az: Double) {
        this.ax = ax; this.ay = ay; this.az = az; haveAcc = true
    }
    fun handleMag(@Suppress("UNUSED_PARAMETER") tsNs: Long, mx: Double, my: Double, mz: Double, reliable: Boolean) {
        this.mx = mx; this.my = my; this.mz = mz; this.haveMag = true; this.magReliable = reliable
    }
    fun handleBaro(tsNs: Long, pressure_hPa: Double, p0_hPa: Double = 1013.25, alpha: Double = 0.05) {
        val alt = 44330.0 * (1.0 - (pressure_hPa / p0_hPa).pow(0.1903))
        baroAltLP = if (baroAltLP.isFinite()) (1.0 - alpha) * baroAltLP + alpha * alt else alt
        haveBaro = true
        if (originSet && baroAltLP.isFinite()) {
            val z = baroAltLP + baroBias
            val y = doubleArrayOf(z - pU)
            val H = DoubleArray(1*15); H[0*15 + 2] = 1.0
            val r2 = 2.0*2.0
            val R = doubleArrayOf(r2)
            if (mahalanobisGate1(y, H, R, gateAlt1d)) {
                ekfUpdate1(y, H, R); injectError(kGainLastY)
            }
        }
    }

    fun setWorldMagReference(enuX: Double, enuY: Double, enuZ: Double, fieldStrength_uT: Double) {
        val n = sqrt(enuX*enuX + enuY*enuY + enuZ*enuZ)
        if (n > 1e-9) {
            m_w[0] = enuX/n; m_w[1] = enuY/n; m_w[2] = enuZ/n
            expectedField_uT = fieldStrength_uT
            haveMref = true
        }
    }

    // --- GNSS updates ---
    fun updateWithGps(lat: Double, lon: Double, alt: Double, hAcc: Double, vAcc: Double) {
        if (!originSet) {
            originSet = true
            originLat = lat; originLon = lon; originAlt = if (alt.isFinite()) alt else 0.0
            ecef0 = llaToEcef(originLat, originLon, originAlt)
            Re2n = ecefToEnuRot(originLat, originLon)
            Rn2e = transpose3(Re2n)
        }
        val ecef = llaToEcef(lat, lon, alt)
        val dEcef = doubleArrayOf(ecef[0]-ecef0[0], ecef[1]-ecef0[1], ecef[2]-ecef0[2])
        val zVec = matVec(Re2n, dEcef)
        val z = doubleArrayOf(zVec[0], zVec[1], zVec[2])

        val H = DoubleArray(3*15)
        H[0*15 + 0] = 1.0; H[1*15 + 1] = 1.0; H[2*15 + 2] = 1.0

        val sx = max(hAcc, rFloorHoriz)
        val sy = max(hAcc, rFloorHoriz)
        val sz = if (vAcc.isFinite()) max(vAcc, rFloorVert) else max(hAcc, rFloorVert)
        val Rm = doubleArrayOf(sx*sx,0.0,0.0, 0.0,sy*sy,0.0, 0.0,0.0,sz*sz)
        val y = doubleArrayOf(z[0]-pE, z[1]-pN, z[2]-pU)
        if (!mahalanobisGate(y, H, Rm, gateGps3d)) return
        ekfUpdate(y, H, Rm); injectError(kGainLastY)

        if (haveBaro && baroAltLP.isFinite()) { baroBias = alt - baroAltLP }
    }

    fun updateWithGnssVelocity(vE_meas: Double, vN_meas: Double, sv: Double) {
        val y = doubleArrayOf(vE_meas - vE, vN_meas - vN)
        val H = DoubleArray(2*15); H[0*15+3]=1.0; H[1*15+4]=1.0
        val r2 = max(0.5, sv)*max(0.5, sv)
        val Rm = doubleArrayOf(r2,0.0, 0.0,r2)
        if (!mahalanobisGate2(y, H, Rm, gateVel2d)) return
        ekfUpdate2(y, H, Rm); injectError(kGainLastY)
    }

    // --- Prediction ---
    private fun predict(dt: Double) {
        val wx = gx - bgx; val wy = gy - bgy; val wz = gz - bgz
        val axb = ax - bax; val ayb = ay - bay; val azb = az - baz

        // Quaternion kinematics
        val half = 0.5
        val qw=q0; val qx=q1; val qy=q2; val qz=q3
        val dq0 = (-qx*wx - qy*wy - qz*wz) * half
        val dq1 = ( qw*wx + qy*wz - qz*wy) * half
        val dq2 = ( qw*wy - qx*wz + qz*wx) * half
        val dq3 = ( qw*wz + qx*wy - qy*wx) * half
        q0 += dq0*dt; q1 += dq1*dt; q2 += dq2*dt; q3 += dq3*dt
        val nq = 1.0 / max(1e-12, sqrt(q0*q0 + q1*q1 + q2*q2 + q3*q3))
        q0*=nq; q1*=nq; q2*=nq; q3*=nq

        val Rwb = quatToRot(q0,q1,q2,q3)
        val f_w = matVec(Rwb, doubleArrayOf(axb, ayb, azb))

        vE += (f_w[0] + g_w[0]) * dt
        vN += (f_w[1] + g_w[1]) * dt
        vU += (f_w[2] + g_w[2]) * dt
        pE += vE*dt + 0.5*(f_w[0]+g_w[0])*dt*dt
        pN += vN*dt + 0.5*(f_w[1]+g_w[1])*dt*dt
        pU += vU*dt + 0.5*(f_w[2]+g_w[2])*dt*dt

        // Error-state propagation
        val F = DoubleArray(15*15); identity(F, 15)
        F[0*15+3]=dt; F[1*15+4]=dt; F[2*15+5]=dt
        val Sfw = skew(f_w[0], f_w[1], f_w[2])
        F[3*15+6] += -dt*Sfw[0]; F[3*15+7] += -dt*Sfw[1]; F[3*15+8] += -dt*Sfw[2]
        F[4*15+6] += -dt*Sfw[3]; F[4*15+7] += -dt*Sfw[4]; F[4*15+8] += -dt*Sfw[5]
        F[5*15+6] += -dt*Sfw[6]; F[5*15+7] += -dt*Sfw[7]; F[5*15+8] += -dt*Sfw[8]
        // -R δb_a
        F[3*15+12] += -dt*Rwb[0]; F[3*15+13] += -dt*Rwb[1]; F[3*15+14] += -dt*Rwb[2]
        F[4*15+12] += -dt*Rwb[3]; F[4*15+13] += -dt*Rwb[4]; F[4*15+14] += -dt*Rwb[5]
        F[5*15+12] += -dt*Rwb[6]; F[5*15+13] += -dt*Rwb[7]; F[5*15+14] += -dt*Rwb[8]
        // δθ̇ = -[ω]_x δθ - δb_g
        val Sω = skew(wx,wy,wz)
        F[6*15+6] += -dt*Sω[0]; F[6*15+7] += -dt*Sω[1]; F[6*15+8] += -dt*Sω[2]
        F[7*15+6] += -dt*Sω[3]; F[7*15+7] += -dt*Sω[4]; F[7*15+8] += -dt*Sω[5]
        F[8*15+6] += -dt*Sω[6]; F[8*15+7] += -dt*Sω[7]; F[8*15+8] += -dt*Sω[8]
        F[6*15+9] += -dt; F[7*15+10] += -dt; F[8*15+11] += -dt

        val dyn = 1.0 + 1.5*min(1.0, lastLinAMag) + 0.8*min(1.0, lastOmegaMag)
        val Qd = DoubleArray(15*15)
        addDiag(Qd, 6, 3, (sigmaGyro*sigmaGyro) * dt * dyn)
        addDiag(Qd, 9, 3, (sigmaBg*sigmaBg) * dt)
        addDiag(Qd, 3, 3, (sigmaAccel*sigmaAccel) * dt * dyn)
        addDiag(Qd,12, 3, (sigmaBa*sigmaBa) * dt)

        val FP = matMul(F, P, 15, 15, 15)
        val FPFt = matMul(FP, transpose(F, 15), 15, 15, 15)
        val Pnew = addMat(FPFt, Qd, 15, 15)
        // Symmetrize + jitter
        for (r in 0 until 15) {
            for (c in r until 15) {
                val v = 0.5*(Pnew[r*15 + c] + Pnew[c*15 + r])
                P[r*15 + c] = v; P[c*15 + r] = v
            }
            P[r*15 + r] += 1e-9
        }
    }

    // --- Measurement updates ---
    private fun accelGravityUpdate() {
        val Rwb = quatToRot(q0,q1,q2,q3)
        val Rt = transpose3(Rwb)
        val h = matVec(Rt, g_w)
        val hx = -h[0] + bax; val hy = -h[1] + bay; val hz = -h[2] + baz
        val y = doubleArrayOf(ax - hx, ay - hy, az - hz)
        val Sg = skew(g_w[0], g_w[1], g_w[2])
        val RtSg = mat3Mul(Rt, Sg)
        val H = DoubleArray(3*15)
        H[0*15+6]=RtSg[0]; H[0*15+7]=RtSg[1]; H[0*15+8]=RtSg[2]
        H[1*15+6]=RtSg[3]; H[1*15+7]=RtSg[4]; H[1*15+8]=RtSg[5]
        H[2*15+6]=RtSg[6]; H[2*15+7]=RtSg[7]; H[2*15+8]=RtSg[8]
        H[0*15+12]=1.0; H[1*15+13]=1.0; H[2*15+14]=1.0

        val base = 0.5
        val r = base * (1.0 + 2.0*lastLinAMag + 0.5*lastOmegaMag)
        val Rm = doubleArrayOf(r*r,0.0,0.0, 0.0,r*r,0.0, 0.0,0.0,r*r)
        if (!mahalanobisGate(y, H, Rm, gateAcc3d)) return
        ekfUpdate(y, H, Rm); injectError(kGainLastY)
    }

    private fun magnetometerUpdate() {
        val mb = doubleArrayOf(mx, my, mz)
        val normMb = sqrt(mb[0]*mb[0] + mb[1]*mb[1] + mb[2]*mb[2])
        if (!normMb.isFinite() || normMb < 1e-9) return
        if (abs(normMb - expectedField_uT) / max(1e-6, expectedField_uT) > 0.25) return
        val z = doubleArrayOf(mb[0]/normMb, mb[1]/normMb, mb[2]/normMb)

        val Rwb = quatToRot(q0,q1,q2,q3)
        val Rt = transpose3(Rwb)
        val h = matVec(Rt, m_w)
        val y = doubleArrayOf(z[0]-h[0], z[1]-h[1], z[2]-h[2])
        val Sm = skew(m_w[0], m_w[1], m_w[2])
        val RtSm = mat3Mul(Rt, Sm)
        val H = DoubleArray(3*15)
        H[0*15+6] = -RtSm[0]; H[0*15+7] = -RtSm[1]; H[0*15+8] = -RtSm[2]
        H[1*15+6] = -RtSm[3]; H[1*15+7] = -RtSm[4]; H[1*15+8] = -RtSm[5]
        H[2*15+6] = -RtSm[6]; H[2*15+7] = -RtSm[7]; H[2*15+8] = -RtSm[8]

        val r = 0.20
        val Rm = doubleArrayOf(r*r,0.0,0.0, 0.0,r*r,0.0, 0.0,0.0,r*r)
        if (!mahalanobisGate(y, H, Rm, gateMag3d)) return
        ekfUpdate(y, H, Rm); injectError(kGainLastY)
    }

    private fun zuptUpdate() {
        val y = doubleArrayOf(-vE, -vN, -vU)
        val H = DoubleArray(3*15); H[0*15+3]=1.0; H[1*15+4]=1.0; H[2*15+5]=1.0
        val r2 = zuptR*zuptR
        val Rm = doubleArrayOf(r2,0.0,0.0, 0.0,r2,0.0, 0.0,0.0,r2)
        if (!mahalanobisGate(y, H, Rm, gateAcc3d)) return
        ekfUpdate(y, H, Rm); injectError(kGainLastY)
    }

    // --- EKF update plumbing (Joseph) ---
    private var kGainLastY = DoubleArray(15)

    private fun ekfUpdate(y: DoubleArray, H: DoubleArray, Rm: DoubleArray) {
        val HP = matMul(H, P, 3, 15, 15)
        val HPHt = matMul(HP, transpose(H, 3, 15), 3, 15, 3)
        val S = addMat(HPHt, Rm, 3, 3)
        val Sinv = inv3(S)
        val PHt = matMul(P, transpose(H, 3, 15), 15, 15, 3)
        val K = matMul(PHt, Sinv, 15, 3, 3)      // 15x3
        val dx = DoubleArray(15)
        for (i in 0 until 15) dx[i] = K[i*3+0]*y[0] + K[i*3+1]*y[1] + K[i*3+2]*y[2]
        kGainLastY = dx
        josephUpdate(K, H, Rm, r = 3)
    }

    private fun ekfUpdate2(y: DoubleArray, H: DoubleArray, Rm: DoubleArray) {
        val HP = matMul(H, P, 2, 15, 15)
        val HPHt = matMul(HP, transpose(H, 2, 15), 2, 15, 2)
        val S = addMat(HPHt, Rm, 2, 2)
        val Sinv = inv2(S)
        val PHt = matMul(P, transpose(H, 2, 15), 15, 15, 2)
        val K = matMul(PHt, Sinv, 15, 2, 2)      // 15x2
        val dx = DoubleArray(15)
        for (i in 0 until 15) dx[i] = K[i*2+0]*y[0] + K[i*2+1]*y[1]
        kGainLastY = dx
        josephUpdate(K, H, Rm, r = 2)
    }

    private fun ekfUpdate1(y: DoubleArray, H: DoubleArray, Rm: DoubleArray) {
        val HP = matMul(H, P, 1, 15, 15)
        val HPHt = matMul(HP, transpose(H, 1, 15), 1, 15, 1)
        val S0 = HPHt[0] + Rm[0]
        val invS = 1.0 / max(1e-12, S0)
        val PHt = matMul(P, transpose(H, 1, 15), 15, 15, 1)
        val K = DoubleArray(15) { i -> PHt[i] * invS } // 15x1 gain
        val dx = DoubleArray(15) { i -> K[i] * y[0] }
        kGainLastY = dx
        josephUpdate1(K, H, Rm[0])
    }

    private fun josephUpdate(K: DoubleArray, H: DoubleArray, R: DoubleArray, r: Int) {
        // Shapes: K(15xr), H(rx15), R(rxr)
        val KH = matMul(K, H, 15, r, 15)                       // (15xr)*(rx15) -> (15x15)
        val I = identityMat(15)
        val IminusKH = subMat(I, KH, 15, 15)

        val term1 = matMul(
            matMul(IminusKH, P, 15, 15, 15),
            transpose(IminusKH, 15),
            15, 15, 15
        )                                                     // (15x15)

        val KR   = matMul(K, R, 15, r, r)                     // (15xr)
        val KRKt = matMul(KR, transpose(K, 15, r), 15, r, 15) // (15xr)*(rx15) -> (15x15)

        val Pnew = addMat(term1, KRKt, 15, 15)
        for (rr in 0 until 15) {
            for (cc in rr until 15) {
                val v = 0.5*(Pnew[rr*15 + cc] + Pnew[cc*15 + rr])
                P[rr*15 + cc] = v; P[cc*15 + rr] = v
            }
            P[rr*15 + rr] += 1e-9
        }
    }

    private fun josephUpdate1(K: DoubleArray, H: DoubleArray, R: Double) {
        // Generic KH = K(15x1) * H(1x15)
        val KH = DoubleArray(15*15)
        for (r in 0 until 15) {
            val k = K[r]
            for (c in 0 until 15) KH[r*15 + c] = k * H[c]
        }
        val I = identityMat(15)
        val IminusKH = subMat(I, KH, 15, 15)

        val term1 = matMul(
            matMul(IminusKH, P, 15, 15, 15),
            transpose(IminusKH, 15),
            15, 15, 15
        )

        // KRKt = K * R * K^T  with K(15x1)
        val KRKt = DoubleArray(15*15)
        val s = R
        for (r in 0 until 15) for (c in 0 until 15) KRKt[r*15+c] = K[r] * s * K[c]

        val Pnew = addMat(term1, KRKt, 15, 15)
        for (rr in 0 until 15) {
            for (cc in rr until 15) {
                val v = 0.5*(Pnew[rr*15 + cc] + Pnew[cc*15 + rr])
                P[rr*15 + cc] = v; P[cc*15 + rr] = v
            }
            P[rr*15 + rr] += 1e-9
        }
    }

    // --- Inject error into nominal ---
    private fun injectError(dx: DoubleArray) {
        pE += dx[0]; pN += dx[1]; pU += dx[2]
        vE += dx[3]; vN += dx[4]; vU += dx[5]
        val dq = smallAngleQuat(dx[6], dx[7], dx[8])
        val q = quatMul(dq, doubleArrayOf(q0,q1,q2,q3))
        q0=q[0]; q1=q[1]; q2=q[2]; q3=q[3]
        val n = 1.0 / max(1e-12, sqrt(q0*q0+q1*q1+q2*q2+q3*q3)); q0*=n; q1*=n; q2*=n; q3*=n
        bgx += dx[9]; bgy += dx[10]; bgz += dx[11]
        bax += dx[12]; bay += dx[13]; baz += dx[14]
    }

    // --- Public helpers ---
    fun getCurrentPositionAsLatLng(): Triple<Double, Double, Double> {
        if (!originSet) return Triple(Double.NaN, Double.NaN, Double.NaN)
        val ecef = doubleArrayOf(
            ecef0[0] + (Rn2e[0]*pE + Rn2e[1]*pN + Rn2e[2]*pU),
            ecef0[1] + (Rn2e[3]*pE + Rn2e[4]*pN + Rn2e[5]*pU),
            ecef0[2] + (Rn2e[6]*pE + Rn2e[7]*pN + Rn2e[8]*pU)
        )
        val lla = ecefToLla(ecef[0], ecef[1], ecef[2])
        return Triple(lla[0], lla[1], lla[2])
    }
    val position: Triple<Double,Double,Double> get() = Triple(pE,pN,pU)
    val velocity: Triple<Double,Double,Double> get() = Triple(vE,vN,vU)
    val quaternion: DoubleArray get() = doubleArrayOf(q0,q1,q2,q3)
    val gyroBias: DoubleArray get() = doubleArrayOf(bgx,bgy,bgz)
    val accelBias: DoubleArray get() = doubleArrayOf(bax,bay,baz)

    // --- Math utils ---
    private fun llaToEcef(lat: Double, lon: Double, alt: Double): DoubleArray {
        val phi = Math.toRadians(lat); val lam = Math.toRadians(lon); val s = sin(phi); val c = cos(phi)
        val N = a / sqrt(1.0 - e2*s*s)
        val x = (N + alt) * c * cos(lam)
        val y = (N + alt) * c * sin(lam)
        val z = (N*(1.0 - e2) + alt) * s
        return doubleArrayOf(x,y,z)
    }
    private fun ecefToLla(x: Double, y: Double, z: Double): DoubleArray {
        val r = sqrt(x*x + y*y)
        val Esq = a*a - b*b
        val F = 54.0*b*b*z*z
        val G = r*r + (1.0 - e2)*z*z - e2*Esq
        val c = (e2*e2*F*r*r)/(G*G*G)
        val s = (1.0 + c + sqrt(c*c + 2.0*c)).pow(1.0/3.0)
        val P = F / (3.0*(s + 1.0/s + 1.0)*(s + 1.0/s + 1.0) * G*G)
        val Q = sqrt(1.0 + 2.0*e2*e2*P)
        val r0 = -(P*e2*r)/(1.0 + Q) + sqrt(0.5*a*a*(1.0 + 1.0/Q) - P*(1.0 - e2)*z*z/(Q*(1.0 + Q)) - 0.5*P*r*r)
        val U = sqrt((r - e2*r0)*(r - e2*r0) + z*z)
        val V = sqrt((r - e2*r0)*(r - e2*r0) + (1.0 - e2)*z*z)
        val z0 = b*b*z/(a*V)
        val h = U*(1.0 - b*b/(a*V))
        val lat = atan((z + ep2*z0)/r)
        val lon = atan2(y, x)
        return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }
    private fun ecefToEnuRot(lat0: Double, lon0: Double): DoubleArray {
        val phi = Math.toRadians(lat0); val lam = Math.toRadians(lon0)
        val sphi = sin(phi); val cphi = cos(phi)
        val slam = sin(lam); val clam = cos(lam)
        return doubleArrayOf(
            -slam,          clam,            0.0,
            -sphi*clam,    -sphi*slam,       cphi,
             cphi*clam,     cphi*slam,       sphi
        )
    }
    private fun quatToRot(w: Double, x: Double, y: Double, z: Double): DoubleArray {
        val ww=w*w; val xx=x*x; val yy=y*y; val zz=z*z
        val wx=w*x; val wy=w*y; val wz=w*z
        val xy=x*y; val xz=x*z; val yz=y*z
        return doubleArrayOf(
            ww+xx-yy-zz, 2.0*(xy-wz),   2.0*(xz+wy),
            2.0*(xy+wz),  ww-xx+yy-zz, 2.0*(yz-wx),
            2.0*(xz-wy),  2.0*(yz+wx), ww-xx-yy+zz
        )
    }
    private fun transpose3(M: DoubleArray): DoubleArray = doubleArrayOf(M[0],M[3],M[6], M[1],M[4],M[7], M[2],M[5],M[8])
    private fun matVec(M: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        M[0]*v[0] + M[1]*v[1] + M[2]*v[2],
        M[3]*v[0] + M[4]*v[1] + M[5]*v[2],
        M[6]*v[0] + M[7]*v[1] + M[8]*v[2]
    )
    private fun skew(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(0.0,-z,y, z,0.0,-x, -y,x,0.0)
    private fun mat3Mul(A: DoubleArray, B: DoubleArray): DoubleArray = doubleArrayOf(
        A[0]*B[0]+A[1]*B[3]+A[2]*B[6], A[0]*B[1]+A[1]*B[4]+A[2]*B[7], A[0]*B[2]+A[1]*B[5]+A[2]*B[8],
        A[3]*B[0]+A[4]*B[3]+A[5]*B[6], A[3]*B[1]+A[4]*B[4]+A[5]*B[7], A[3]*B[2]+A[4]*B[5]+A[5]*B[8],
        A[6]*B[0]+A[7]*B[3]+A[8]*B[6], A[6]*B[1]+A[7]*B[4]+A[8]*B[7], A[6]*B[2]+A[7]*B[5]+A[8]*B[8]
    )
    private fun smallAngleQuat(dx: Double, dy: Double, dz: Double): DoubleArray {
        val halfx = 0.5*dx; val halfy = 0.5*dy; val halfz = 0.5*dz
        return doubleArrayOf(1.0, halfx, halfy, halfz)
    }
    private fun quatMul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val aw=a[0]; val ax=a[1]; val ay=a[2]; val az=a[3]
        val bw=b[0]; val bx=b[1]; val by=b[2]; val bz=b[3]
        return doubleArrayOf(
            aw*bw - ax*bx - ay*by - az*bz,
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw
        )
    }
    private fun zero(M: DoubleArray) { java.util.Arrays.fill(M, 0.0) }
    private fun setDiag(M: DoubleArray, offset: Int, n: Int, v: Double) { for (i in 0 until n) M[(offset+i)*15 + (offset+i)] = v }
    private fun addDiag(M: DoubleArray, offset: Int, n: Int, v: Double) { for (i in 0 until n) M[(offset+i)*15 + (offset+i)] += v }
    private fun identity(M: DoubleArray, n: Int) { zero(M); for (i in 0 until n) M[i*n+i]=1.0 }
    private fun identityMat(n: Int): DoubleArray { val M = DoubleArray(n*n); for (i in 0 until n) M[i*n+i]=1.0; return M }
    private fun transpose(M: DoubleArray, n: Int): DoubleArray { val T=DoubleArray(n*n); for (r in 0 until n) for (c in 0 until n) T[c*n+r]=M[r*n+c]; return T }
    private fun transpose(M: DoubleArray, rows: Int, cols: Int): DoubleArray { val T=DoubleArray(rows*cols); for (r in 0 until rows) for (c in 0 until cols) T[c*rows+r]=M[r*cols+c]; return T }
    private fun matMul(A: DoubleArray, B: DoubleArray, aRows: Int, aCols: Int, bCols: Int): DoubleArray {
        val C = DoubleArray(aRows*bCols)
        for (r in 0 until aRows) for (c in 0 until bCols) {
            var s=0.0; for (k in 0 until aCols) s += A[r*aCols+k]*B[k*bCols+c]; C[r*bCols+c]=s
        }
        return C
    }
    private fun addMat(A: DoubleArray, B: DoubleArray, rows: Int, cols: Int): DoubleArray { val C=DoubleArray(rows*cols); for (i in 0 until rows*cols) C[i]=A[i]+B[i]; return C }
    private fun subMat(A: DoubleArray, B: DoubleArray, rows: Int, cols: Int): DoubleArray { val C=DoubleArray(rows*cols); for (i in 0 until rows*cols) C[i]=A[i]-B[i]; return C }
    private fun inv3(S: DoubleArray): DoubleArray {
        val a=S[0]; val b=S[1]; val c=S[2]; val d=S[3]; val e=S[4]; val f=S[5]; val g=S[6]; val h=S[7]; val i=S[8]
        val A=e*i - f*h; val B=c*h - b*i; val C=b*f - c*e
        val D=f*g - d*i; val E=a*i - c*g; val F=c*d - a*f
        val G=d*h - e*g; val H=b*g - a*h; val I=a*e - b*d
        val det = a*A + b*D + c*G; val invDet = 1.0 / det.coerceAtLeast(1e-12)
        return doubleArrayOf(A*invDet,B*invDet,C*invDet, D*invDet,E*invDet,F*invDet, G*invDet,H*invDet,I*invDet)
    }
    private fun inv2(S: DoubleArray): DoubleArray { val a=S[0]; val b=S[1]; val c=S[2]; val d=S[3]; val det=a*d-b*c; val inv=1.0/max(1e-12,det); return doubleArrayOf(d*inv,-b*inv,-c*inv,a*inv) }
    private fun mahalanobisGate(y: DoubleArray, H: DoubleArray, Rm: DoubleArray, gate: Double): Boolean {
        val HP = matMul(H, P, 3, 15, 15); val HPHt = matMul(HP, transpose(H,3,15), 3,15,3); val S = addMat(HPHt, Rm, 3,3)
        val Sinv = inv3(S); val y0=y[0]; val y1=y[1]; val y2=y[2]
        val md = y0*(Sinv[0]*y0 + Sinv[1]*y1 + Sinv[2]*y2) + y1*(Sinv[3]*y0 + Sinv[4]*y1 + Sinv[5]*y2) + y2*(Sinv[6]*y0 + Sinv[7]*y1 + Sinv[8]*y2)
        return md.isFinite() && md <= gate
    }
    private fun mahalanobisGate2(y: DoubleArray, H: DoubleArray, Rm: DoubleArray, gate: Double): Boolean {
        val HP = matMul(H, P, 2, 15, 15); val HPHt = matMul(HP, transpose(H,2,15), 2,15,2); val S = addMat(HPHt, Rm, 2,2)
        val Sinv = inv2(S); val y0=y[0]; val y1=y[1]; val md = y0*(Sinv[0]*y0 + Sinv[1]*y1) + y1*(Sinv[2]*y0 + Sinv[3]*y1)
        return md.isFinite() && md <= gate
    }
    private fun mahalanobisGate1(y: DoubleArray, H: DoubleArray, Rm: DoubleArray, gate: Double): Boolean {
        val HP = matMul(H, P, 1, 15, 15); val HPHt = matMul(HP, transpose(H,1,15), 1,15,1); val S0 = HPHt[0] + Rm[0]
        val md = y[0]*y[0] / max(1e-12, S0); return md.isFinite() && md <= gate
    }
    private fun pushLinAcc(a: Double) { linAccWindow.addLast(a); if (linAccWindow.size > linAccWindowMax) linAccWindow.removeFirst() }
}

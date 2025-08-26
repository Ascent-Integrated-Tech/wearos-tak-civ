package com.aitech.weartak_civ.algorithm

import kotlin.math.*

/**
 * True MEKF for wearable dead reckoning (15-state error filter).
 * Nominal: p(3), v(3), q(4 scalar-first), b_g(3), b_a(3)
 * Error:   δx = [δp(3), δv(3), δθ(3), δb_g(3), δb_a(3)] (NX=15)
 */
class TrueMEKF(
    private val initialLLA: Triple<Double, Double, Double>,
    private val initialV: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    private val initialQ: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0), // [w,x,y,z]
) {
    companion object {
        const val NX = 15
        private const val G = -9.80665 // ENU Up is positive → g = (0,0,-9.80665)

        // Noise spectral densities (tune)
        private const val QV   = 0.6      // accel drive on velocity (m^2/s^3)
        private const val QT   = 0.02     // gyro drive on attitude (rad^2/s)
        private const val QBG  = 1e-6     // gyro bias RW (rad^2/s^3)
        private const val QBA  = 1e-4     // accel bias RW (m^2/s^5)

        private const val TAU_G = 300.0   // gyro bias time-constant (s)
        private const val TAU_A = 600.0   // accel bias time-constant (s)
    }

    // Nominal state
    private var p = doubleArrayOf(0.0, 0.0, 0.0)      // ENU meters
    private var v = doubleArrayOf(initialV.first, initialV.second, initialV.third)
    private var q = initialQ.copyOf()                 // [w,x,y,z]
    private var bg = doubleArrayOf(0.0, 0.0, 0.0)     // rad/s
    private var ba = doubleArrayOf(0.0, 0.0, 0.0)     // m/s^2

    // Error covariance (15x15)
    private var P = Array(NX) { DoubleArray(NX) }

    // Origin for ENU
    private val ref = initialLLA

    // Madgwick for attitude measurement generation (IMU-only or with mag as desired)
    private val ahrs = ModifiedMadgwickFilter(
        sampleFreq = 100f, beta = 0.05f, lambda = 0.001f, decayRate = 10f, sigma = 0.0f, mu = 0.92f
    )

    private var lastTsNs: Long = 0L

    init {
        // Set initial position from LLA → ENU at ref
        val enu = geodeticToLocal(ref.first, ref.second, ref.third, ref)
        p[0]=enu[0]; p[1]=enu[1]; p[2]=enu[2]
        // Reasonable initial P
        P.diagInit(
            pos=25.0, vel=4.0, att= (Math.toRadians(10.0)).pow(2)/4.0,
            bg= (0.01).pow(2), ba= (0.1).pow(2)
        )
    }

    /** Predict with raw IMU (ax,ay,az, wx,wy,wz) in body, timestamp in ns. */
    fun predict(tsNs: Long, imu: DoubleArray) {
        require(imu.size >= 6)
        val dt = ((if (lastTsNs > 0) tsNs - lastTsNs else 10_000_000L).toDouble() / 1e9)
            .coerceIn(0.01, 0.10)
        lastTsNs = tsNs

        // 1) Bias-corrected gyro & accel (specific force)
        val w = doubleArrayOf(imu[3]-bg[0], imu[4]-bg[1], imu[5]-bg[2])
        val fb = doubleArrayOf(imu[0]-ba[0], imu[1]-ba[1], imu[2]-ba[2])

        // 2) Propagate nominal attitude: q ← q ⊗ exp_q(0.5*w*dt)
        q = quatNormalize(quatMultiply(q, smallAngleToQuat(doubleArrayOf(w[0]*dt/2, w[1]*dt/2, w[2]*dt/2))))

        // 3) Specific force to nav and add gravity
        val Rnb = rotMatBodyToNav(q)
        val fn = Rnb.dot3(fb)
        val an = doubleArrayOf(fn[0], fn[1], fn[2] + G)

        // 4) Integrate v, p
        v[0] += an[0]*dt; v[1] += an[1]*dt; v[2] += an[2]*dt
        p[0] += v[0]*dt + 0.5*an[0]*dt*dt
        p[1] += v[1]*dt + 0.5*an[1]*dt*dt
        p[2] += v[2]*dt + 0.5*an[2]*dt*dt

        // 5) Error-state dynamics F, G and discretization
        val (F, G) = buildErrorDynamics(q, fb, w)
        val Phi = eye(NX).plus(F.scale(dt))
        val Qc  = diag( // 12x12 spectral densities
            doubleArrayOf(QV, QV, QV, QT, QT, QT, QBG, QBG, QBG, QBA, QBA, QBA)
        )
        val Qd = Phi.dot(G).dot(Qc).dot(G.T()).dot(Phi.T()).scale(dt)

        // 6) Covariance propagation
        P = Phi.dot(P).dot(Phi.T()).plus(Qd).symmetrize().also { it.makeSPD(1e-9) }

        // 7) Run Madgwick to produce attitude measurement (optionally with mag)
        ahrs.update(
            gyroscope = floatArrayOf(imu[3].toFloat(), imu[4].toFloat(), imu[5].toFloat()),
            accelerometer = floatArrayOf(imu[0].toFloat(), imu[1].toFloat(), imu[2].toFloat()),
            magnetometer = floatArrayOf(0f,0f,0f), // or real mag if available
            dt = dt.toFloat()
        )
    }

    /** Fuse attitude from Madgwick as 3x1 measurement of small-angle. */
    fun updateAttitudeFromMadgwick() {
        val qm = ahrs.getQuaternion()
        val qMea = doubleArrayOf(qm[0].toDouble(), qm[1].toDouble(), qm[2].toDouble(), qm[3].toDouble())
        val qErr = quatMultiply(qMea, quatConjugate(q))
        val r = doubleArrayOf(2.0*qErr[1], 2.0*qErr[2], 2.0*qErr[3]) // 3x1

        // H_att = [ 0 0 I3 0 0 ] on δθ block (cols 6..8)
        val H = Array(3) { DoubleArray(NX) { 0.0 } }
        H[0][6]=1.0; H[1][7]=1.0; H[2][8]=1.0

        // Adaptive R: trust yaw only when mag reliable
        val magW = ahrs.getMagWeight()
        val rpStd = 0.5
        val yawStd = if (magW > 0f) 1.0/(0.5+0.5*magW) * 1.5 else 1e3
        val Rm = arrayOf(
            doubleArrayOf(rpStd*rpStd, 0.0, 0.0),
            doubleArrayOf(0.0, rpStd*rpStd, 0.0),
            doubleArrayOf(0.0, 0.0, yawStd*yawStd)
        )

        val Ht = H.T()
        val S  = H.dot(P).dot(Ht).plus(Rm).symmetrize()
        val SInv = safeInvSPD(S) ?: return
        val K = P.dot(Ht).dot(SInv)
        val dx = K.vec(r)

        // Inject δθ into nominal q, zero δθ in error-state (by Joseph form)
        val dth = doubleArrayOf(dx[6], dx[7], dx[8])
        q = quatNormalize(quatMultiply(q, smallAngleToQuat(dth)))

        // Joseph covariance update
        val I = eye(NX)
        val IminusKH = I.minus(K.dot(H))
        P = IminusKH.dot(P).dot(IminusKH.T()).plus(K.dot(Rm).dot(K.T())).symmetrize().also { it.makeSPD(1e-9) }
    }

    /** GPS update: z = [x y z vx vy vz] in ENU, 6x1 */
    fun updateGps(posVelEnu: DoubleArray) {
        require(posVelEnu.size == 6)
        val H = Array(6) { DoubleArray(NX) { 0.0 } }
        // δp block
        H[0][0]=1.0; H[1][1]=1.0; H[2][2]=1.0
        // δv block
        H[3][3]=1.0; H[4][4]=1.0; H[5][5]=1.0

        val zPred = doubleArrayOf(p[0],p[1],p[2], v[0],v[1],v[2])
        val r = DoubleArray(6) { i -> posVelEnu[i] - zPred[i] }
        val Rm = diag(doubleArrayOf(3.0,3.0,6.0, 0.2,0.2,0.5)) // tune

        val Ht = H.T()
        val S  = H.dot(P).dot(Ht).plus(Rm).symmetrize()
        val SInv = safeInvSPD(S) ?: return
        val K = P.dot(Ht).dot(SInv)
        val dx = K.vec(r)

        // Nominal corrections except attitude (handled via attitude updates)
        p[0]+=dx[0]; p[1]+=dx[1]; p[2]+=dx[2]
        v[0]+=dx[3]; v[1]+=dx[4]; v[2]+=dx[5]
        bg[0]+=dx[9]; bg[1]+=dx[10]; bg[2]+=dx[11]
        ba[0]+=dx[12]; ba[1]+=dx[13]; ba[2]+=dx[14]

        val I = eye(NX)
        val IminusKH = I.minus(K.dot(H))
        P = IminusKH.dot(P).dot(IminusKH.T()).plus(K.dot(Rm).dot(K.T())).symmetrize().also { it.makeSPD(1e-9) }
    }

    /** GPS heading update when speed > 0.5 m/s */
    fun updateGpsHeading(gpsHeading: Double) {
        val speed = sqrt(v[0]*v[0] + v[1]*v[1])
        if (speed < 0.5) return // Only update when moving

        // Extract current yaw from quaternion
        val currentYaw = quatToYaw(q)
        
        // Wrap residual: ψ_gps - ψ(q)
        val residual = wrapAngle(gpsHeading - currentYaw)

        // H selects δθ_z (index 8 in δx)
        val H = Array(1) { DoubleArray(NX) { 0.0 } }
        H[0][8] = 1.0

        // Measurement noise (tune based on GPS heading accuracy)
        val R = arrayOf(doubleArrayOf(0.1 * 0.1)) // 0.1 rad² ≈ 5.7° std

        // 1×1 Joseph update
        val Ht = H.T()
        val S = H.dot(P).dot(Ht).plus(R).symmetrize()
        val SInv = safeInvSPD(S) ?: return
        val K = P.dot(Ht).dot(SInv)
        val dx = K.vec(doubleArrayOf(residual))

        // Inject δθ_z into nominal q
        val dth = doubleArrayOf(0.0, 0.0, dx[8])
        q = quatNormalize(quatMultiply(q, smallAngleToQuat(dth)))

        // Joseph covariance update
        val I = eye(NX)
        val IminusKH = I.minus(K.dot(H))
        P = IminusKH.dot(P).dot(IminusKH.T()).plus(K.dot(R).dot(K.T())).symmetrize().also { it.makeSPD(1e-9) }
    }

    // ---------- Error dynamics (continuous) ----------
    private fun buildErrorDynamics(q: DoubleArray, fb: DoubleArray, w: DoubleArray): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val F = zeros(NX, NX)
        val G = zeros(NX, 12)

        // δṗ = δv
        F[0][3]=1.0; F[1][4]=1.0; F[2][5]=1.0

        // δv̇ = -R * [fb]x * δθ - R * δb_a + n_v
        val R = rotMatBodyToNav(q)
        val Ax = skew(fb)
        val M = R.dot(Ax) // 3x3
        for (i in 0..2) for (j in 0..2) F[3+i][6+j] = -M[i][j]
        for (i in 0..2) for (j in 0..2) F[3+i][12+j] = -R[i][j]

        // δθ̇ = -[w]x δθ - δb_g + n_theta
        val Wx = skew(w)
        for (i in 0..2) for (j in 0..2) F[6+i][6+j] = -Wx[i][j]
        for (i in 0..2) F[6+i][9+i] = -1.0

        // Bias first-order GM
        val invTauG = 1.0/TAU_G
        val invTauA = 1.0/TAU_A
        for (i in 0..2) F[9+i][9+i]  = -invTauG
        for (i in 0..2) F[12+i][12+i]= -invTauA

        // G mapping (n_v, n_theta, n_bg, n_ba)
        for (i in 0..2) G[3+i][i]     = 1.0
        for (i in 0..2) G[6+i][3+i]   = 1.0
        for (i in 0..2) G[9+i][6+i]   = 1.0
        for (i in 0..2) G[12+i][9+i]  = 1.0
        return F to G
    }

    // ---------- Accessors ----------
    fun getPose(): Pose {
        val lla = localToGeodetic(p[0], p[1], p[2], ref)
        return Pose(lla, Triple(v[0],v[1],v[2]), floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()))
    }

    data class Pose(
        val positionLLA: Triple<Double,Double,Double>,
        val velocityENU: Triple<Double,Double,Double>,
        val quaternionWxyz: FloatArray
    )

    // ---------- Math helpers (Double) ----------
    private fun eye(n:Int) = Array(n){ i-> DoubleArray(n){ j-> if(i==j)1.0 else 0.0 } }
    private fun zeros(r:Int,c:Int) = Array(r){ DoubleArray(c){0.0} }
    private fun Array<DoubleArray>.plus(B:Array<DoubleArray>)=Array(size){i->DoubleArray(this[0].size){j->this[i][j]+B[i][j]} }
    private fun Array<DoubleArray>.minus(B:Array<DoubleArray>)=Array(size){i->DoubleArray(this[0].size){j->this[i][j]-B[i][j]} }
    private fun Array<DoubleArray>.scale(s:Double)=Array(size){i->DoubleArray(this[0].size){j->this[i][j]*s} }
    private fun Array<DoubleArray>.dot(B:Array<DoubleArray>):Array<DoubleArray>{
        val r=size; val c=B[0].size; val k=this[0].size
        val out=Array(r){ DoubleArray(c) }
        for(i in 0 until r) for(j in 0 until c){ var s=0.0; for(t in 0 until k) s+=this[i][t]*B[t][j]; out[i][j]=s }
        return out
    }
    private fun Array<DoubleArray>.T():Array<DoubleArray>{
        val r=size; val c=this[0].size
        val out=Array(c){ DoubleArray(r) }
        for(i in 0 until r) for(j in 0 until c) out[j][i]=this[i][j]
        return out
    }
    private fun Array<DoubleArray>.symmetrize()=apply{
        for(i in indices) for(j in i+1 until this[0].size){
            val v=0.5*(this[i][j]+this[j][i]); this[i][j]=v; this[j][i]=v
        }
    }
    private fun Array<DoubleArray>.makeSPD(eps:Double){
        for(i in indices) this[i][i]=kotlin.math.max(this[i][i], eps)
    }
    private fun diag(vals: DoubleArray): Array<DoubleArray> {
        val n = vals.size; val out = Array(n){ DoubleArray(n){0.0} }
        for(i in 0 until n) out[i][i]=vals[i]; return out
    }
    private fun Array<DoubleArray>.vec(v:DoubleArray):DoubleArray{
        val r=size; val c=this[0].size; require(c==v.size)
        val out=DoubleArray(r); for(i in 0 until r){ var s=0.0; for(j in 0 until c) s+=this[i][j]*v[j]; out[i]=s }; return out
    }
    private fun Array<DoubleArray>.diagInit(pos:Double, vel:Double, att:Double, bg:Double, ba:Double){
        for(i in indices) for(j in indices) this[i][j]=0.0
        this[0][0]=pos; this[1][1]=pos; this[2][2]=pos
        this[3][3]=vel; this[4][4]=vel; this[5][5]=vel
        this[6][6]=att; this[7][7]=att; this[8][8]=att
        this[9][9]=bg;  this[10][10]=bg; this[11][11]=bg
        this[12][12]=ba; this[13][13]=ba; this[14][14]=ba
    }

    private fun skew(v: DoubleArray) = arrayOf(
        doubleArrayOf(0.0, -v[2], v[1]),
        doubleArrayOf(v[2], 0.0, -v[0]),
        doubleArrayOf(-v[1], v[0], 0.0)
    )
    private fun rotMatBodyToNav(q: DoubleArray): Array<DoubleArray> {
        val w=q[0]; val x=q[1]; val y=q[2]; val z=q[3]
        return arrayOf(
            doubleArrayOf(1-2*(y*y+z*z), 2*(x*y-w*z),     2*(x*z+w*y)),
            doubleArrayOf(2*(x*y+w*z),   1-2*(x*x+z*z),   2*(y*z-w*x)),
            doubleArrayOf(2*(x*z-w*y),   2*(y*z+w*x),     1-2*(x*x+y*y))
        )
    }
    private fun Array<DoubleArray>.dot3(v: DoubleArray): DoubleArray {
        return doubleArrayOf(
            this[0][0]*v[0]+this[0][1]*v[1]+this[0][2]*v[2],
            this[1][0]*v[0]+this[1][1]*v[1]+this[1][2]*v[2],
            this[2][0]*v[0]+this[2][1]*v[1]+this[2][2]*v[2]
        )
    }
    private fun smallAngleToQuat(d: DoubleArray) = doubleArrayOf(1.0, 0.5*d[0], 0.5*d[1], 0.5*d[2])
    private fun quatMultiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0]*b[0] - a[1]*b[1] - a[2]*b[2] - a[3]*b[3],
        a[0]*b[1] + a[1]*b[0] + a[2]*b[3] - a[3]*b[2],
        a[0]*b[2] - a[1]*b[3] + a[2]*b[0] + a[3]*b[1],
        a[0]*b[3] + a[1]*b[2] - a[2]*b[1] + a[3]*b[0]
    )
    private fun quatConjugate(q: DoubleArray) = doubleArrayOf(q[0], -q[1], -q[2], -q[3])
    private fun quatNormalize(q: DoubleArray): DoubleArray {
        val n = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3])
        val s = if (n>0.0) 1.0/n else 1.0
        return doubleArrayOf(q[0]*s,q[1]*s,q[2]*s,q[3]*s)
    }

    // Extract yaw from quaternion
    private fun quatToYaw(q: DoubleArray): Double {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        return atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
    }

    // Wrap angle to [-π, π]
    private fun wrapAngle(angle: Double): Double {
        var wrapped = angle
        while (wrapped > PI) wrapped -= 2.0 * PI
        while (wrapped < -PI) wrapped += 2.0 * PI
        return wrapped
    }

    private fun safeInvSPD(M: Array<DoubleArray>): Array<DoubleArray>? {
        // Use EKF's matrixInverse if present; else implement a simple Gauss-Jordan here.
        return try { 
            // Try to use EKF's matrix utilities if available
            val ekfClass = Class.forName("com.aitech.weartak_civ.algorithm.ExtendedKalmanFilter")
            val matrixInverseMethod = ekfClass.getDeclaredMethod("matrixInverse", Array<DoubleArray>::class.java)
            matrixInverseMethod.invoke(null, M) as Array<DoubleArray>
        } catch (e: Exception) { 
            // Fallback to local implementation
            matrixInverseWithJitter(M) 
        }
    }

    // --- Geodesy (reuse from EKF or minimal local ENU) ---
    private fun geodeticToLocal(lat: Double, lon: Double, alt: Double, ref: Triple<Double,Double,Double>): DoubleArray {
        val R = 6378137.0
        val dLat = Math.toRadians(lat - ref.first)
        val dLon = Math.toRadians(lon - ref.second)
        val x = R * dLon * cos(Math.toRadians(ref.first))
        val y = R * dLat
        val z = alt - ref.third
        return doubleArrayOf(x, y, z)
    }
    private fun localToGeodetic(x: Double, y: Double, z: Double, ref: Triple<Double,Double,Double>): Triple<Double,Double,Double> {
        val R = 6378137.0
        val dLat = y / R
        val dLon = x / (R * cos(Math.toRadians(ref.first)))
        return Triple(ref.first + Math.toDegrees(dLat), ref.second + Math.toDegrees(dLon), ref.third + z)
    }

    // Minimal safe inverse (or lift EKF's matrixInverse/safeInverseSPD)
    private fun matrixInverseWithJitter(A: Array<DoubleArray>): Array<DoubleArray> {
        // Simple Gauss-Jordan elimination with jitter for stability
        val n = A.size
        val augmented = Array(n) { i -> DoubleArray(2 * n) { j -> 
            if (j < n) A[i][j] else if (j == n + i) 1.0 else 0.0 
        }}
        
        // Add small jitter to diagonal for stability
        for (i in 0 until n) {
            augmented[i][i] += 1e-12
        }
        
        // Forward elimination
        for (i in 0 until n) {
            val pivot = augmented[i][i]
            if (abs(pivot) < 1e-12) continue
            
            for (j in 0 until 2 * n) {
                augmented[i][j] /= pivot
            }
            
            for (k in 0 until n) {
                if (k != i) {
                    val factor = augmented[k][i]
                    for (j in 0 until 2 * n) {
                        augmented[k][j] -= factor * augmented[i][j]
                    }
                }
            }
        }
        
        // Extract inverse from right half
        return Array(n) { i -> DoubleArray(n) { j -> augmented[i][n + j] } }
    }
}

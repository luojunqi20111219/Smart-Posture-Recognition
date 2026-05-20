package com.example.ztsb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Size
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Button
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.Locale
import kotlin.math.acos
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvMetrics: TextView
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnToggleSound: Button
    private lateinit var btnModeChest: Button
    private lateinit var btnModeLeg: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var isFrontCamera = true
    private var isSoundEnabled = true
    private var currentMode = "chest" // "chest" 或 "leg"
    private var textToSpeech: TextToSpeech? = null
    private var lastSpokenStatus: String = ""
    private var lastSpeakTime: Long = 0

    // 初始化离线姿态检测器（使用最适合轻量实时的 CPU/GPU 基础模式）
    private val options = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
        .build()
    private val poseDetector = PoseDetection.getClient(options)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        poseOverlayView = findViewById(R.id.poseOverlayView)
        tvStatus = findViewById(R.id.tvStatus)
        tvMetrics = findViewById(R.id.tvMetrics)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnToggleSound = findViewById(R.id.btnToggleSound)
        btnModeChest = findViewById(R.id.btnModeChest)
        btnModeLeg = findViewById(R.id.btnModeLeg)

        btnSwitchCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            startNativeCamera()
        }

        btnToggleSound.setOnClickListener {
            isSoundEnabled = !isSoundEnabled
            btnToggleSound.text = if (isSoundEnabled) "🔊" else "🔇"
        }

        btnModeChest.setOnClickListener {
            setMode("chest")
        }

        btnModeLeg.setOnClickListener {
            setMode("leg")
        }

        // 初始化 TTS
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.CHINESE
                textToSpeech?.setSpeechRate(1.2f)
            }
        }

        // 检查原生相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startNativeCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun setMode(mode: String) {
        currentMode = mode
        if (mode == "chest") {
            btnModeChest.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
            btnModeLeg.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
        } else {
            btnModeChest.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
            btnModeLeg.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        }
    }

    private fun speak(text: String) {
        if (!isSoundEnabled) return
        val now = System.currentTimeMillis()
        // 避免重复播报同一内容，至少间隔 3 秒
        if (text != lastSpokenStatus || now - lastSpeakTime > 3000) {
            lastSpokenStatus = text
            lastSpeakTime = now
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun startNativeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            // 1. 相机预览用例
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 2. 核心：原生高频图像分析用例（替代网页端的 RequestAnimationFrame）
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                Size(480, 640),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                processImageFrame(imageProxy)
            }

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Toast.makeText(this, "相机绑定失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // 将原始相机流转化为 ML Kit 支持的输入图像（完全本地，不需要任何网络）
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    val allLandmarks = pose.allPoseLandmarks
                    if (allLandmarks.isNotEmpty()) {
                        if (currentMode == "chest") {
                            evaluateChestExpansion(allLandmarks)
                        } else {
                            evaluateHighKnee(allLandmarks)
                        }
                    } else {
                        tvStatus.text = "🔄 请将身体完全对准摄像头"
                    }
                }
                .addOnFailureListener { e ->
                    tvStatus.text = "Error: ${e.message}"
                }
                .addOnCompleteListener {
                    // 必须释放帧，否则相机流会卡死
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // ========== 高抬腿姿态识别 ==========
    private fun evaluateHighKnee(landmarks: List<PoseLandmark>) {
        val lmMap = landmarks.associateBy { it.landmarkType }
        val lh = lmMap[PoseLandmark.LEFT_HIP]
        val rh = lmMap[PoseLandmark.RIGHT_HIP]
        val lk = lmMap[PoseLandmark.LEFT_KNEE]
        val rk = lmMap[PoseLandmark.RIGHT_KNEE]
        val la = lmMap[PoseLandmark.LEFT_ANKLE]
        val ra = lmMap[PoseLandmark.RIGHT_ANKLE]

        if (lh == null || rh == null || lk == null || rk == null || la == null || ra == null) return

        // 计算大腿与垂直方向的夹角（抬腿角度）
        // 0° = 腿垂直向下（立正），90° = 大腿水平，>90° = 向上抬
        fun calcThighAngle(hip: PoseLandmark, knee: PoseLandmark): Double {
            val dx = (knee.position.x - hip.position.x).toDouble()
            val dy = (hip.position.y - knee.position.y).toDouble() // Y轴向下，反转
            val mag = hypot(dx, dy)
            if (mag < 0.001) return 0.0
            // acos(dy/mag) 得到与垂直方向的夹角
            var angle = acos((dy / mag).coerceIn(-1.0, 1.0)) * 180 / Math.PI
            return angle
        }

        // 计算膝盖弯曲角度（大腿与小腿的夹角）
        fun calcKneeAngle(hip: PoseLandmark, knee: PoseLandmark, ankle: PoseLandmark): Double {
            val ax = (hip.position.x - knee.position.x).toDouble()
            val ay = (hip.position.y - knee.position.y).toDouble()
            val bx = (ankle.position.x - knee.position.x).toDouble()
            val by = (ankle.position.y - knee.position.y).toDouble()
            val dot = ax * bx + ay * by
            val magA = hypot(ax, ay)
            val magB = hypot(bx, by)
            if (magA < 0.001 || magB < 0.001) return 180.0
            return acos((dot / (magA * magB)).coerceIn(-1.0, 1.0)) * 180 / Math.PI
        }

        val leftThighAngle = calcThighAngle(lh, lk)
        val rightThighAngle = calcThighAngle(rh, rk)
        val leftKneeAngle = calcKneeAngle(lh, lk, la)
        val rightKneeAngle = calcKneeAngle(rh, rk, ra)

        // 判断哪条腿是支撑腿（角度小的），哪条是抬起腿（角度大的）
        val supportThigh: Double
        val raiseThigh: Double
        val supportKnee: Double
        val raiseKnee: Double
        if (leftThighAngle >= rightThighAngle) {
            // 左腿抬得更高（或相等）
            supportThigh = rightThighAngle
            raiseThigh = leftThighAngle
            supportKnee = rightKneeAngle
            raiseKnee = leftKneeAngle
        } else {
            // 右腿抬得更高
            supportThigh = leftThighAngle
            raiseThigh = rightThighAngle
            supportKnee = leftKneeAngle
            raiseKnee = rightKneeAngle
        }

        // 判断当前状态
        var isStandard = false
        val statusText: String

        when {
            // === 立正状态：双腿都垂直向下 ===
            raiseThigh < 15 -> {
                statusText = "请开始抬腿"
                tvStatus.text = "🔄 $statusText"
            }

            // === 支撑腿不直（站立腿弯曲了）===
            supportThigh > 30 -> {
                statusText = "支撑腿要站直，不要弯曲"
                tvStatus.text = "⚠️ $statusText"
            }

            // === 抬起腿不够高 ===
            raiseThigh < 45 -> {
                statusText = "抬起腿再抬高，大腿要抬到水平"
                tvStatus.text = "🔻 $statusText"
            }

            // === 抬起腿膝盖太直（踢腿而不是抬腿）===
            raiseThigh > 40 && raiseKnee > 170 -> {
                statusText = "抬起腿膝盖要弯曲，小腿自然下垂"
                tvStatus.text = "⚠️ $statusText"
            }

            // === 抬起腿抬得太高 ===
            raiseThigh > 130 -> {
                statusText = "抬得太高了，大腿放平即可"
                tvStatus.text = "🔺 $statusText"
            }

            // === 标准高抬腿（放宽标准）：
            // 支撑腿站直（<30°），抬起腿大腿抬起（45-130°），膝盖弯曲（70-170°）===
            supportThigh < 30 && raiseThigh in 45.0..130.0 && raiseKnee in 70.0..170.0 -> {
                isStandard = true
                statusText = "高抬腿动作标准，继续保持"
                tvStatus.text = "✅ $statusText"
            }

            // === 其他情况 ===
            else -> {
                statusText = "调整姿势，一腿站直，一腿抬平弯曲"
                tvStatus.text = "🔄 $statusText"
            }
        }

        tvMetrics.text = String.format("支撑腿: %.0f° | 抬起腿: %.0f° | 抬起腿膝盖: %.0f°",
            supportThigh, raiseThigh, raiseKnee)

        speak(statusText)

        // 驱动原生画布刷新骨骼线
        poseOverlayView.updatePose(landmarks, isStandard)
    }

    // 核心数学计算（移植自你的终极防漏版H5算法）
    private fun evaluateChestExpansion(landmarks: List<PoseLandmark>) {
        val lmMap = landmarks.associateBy { it.landmarkType }
        val ls = lmMap[PoseLandmark.LEFT_SHOULDER]
        val rs = lmMap[PoseLandmark.RIGHT_SHOULDER]
        val le = lmMap[PoseLandmark.LEFT_ELBOW]
        val re = lmMap[PoseLandmark.RIGHT_ELBOW]
        val lw = lmMap[PoseLandmark.LEFT_WRIST]
        val rw = lmMap[PoseLandmark.RIGHT_WRIST]

        if (ls == null || rs == null || le == null || re == null || lw == null || rw == null) return

        // 3D 夹角计算函数
        fun calcAngle(p1: PoseLandmark, p2: PoseLandmark, p3: PoseLandmark): Double {
            val ax = p1.position3D.x - p2.position3D.x
            val ay = p1.position3D.y - p2.position3D.y
            val az = p1.position3D.z - p2.position3D.z
            val bx = p3.position3D.x - p2.position3D.x
            val by = p3.position3D.y - p2.position3D.y
            val bz = p3.position3D.z - p2.position3D.z
            val dot = ax * bx + ay * by + az * bz
            val magA = hypot(hypot(ax, ay), az)
            val magB = hypot(hypot(bx, by), bz)
            return acos(dot / (magA * magB)) * 180 / Math.PI
        }

        // 抬臂高度计算
        fun calcElevation(shoulder: PoseLandmark, elbow: PoseLandmark): Double {
            val dy = elbow.position.y - shoulder.position.y
            val mag = hypot(elbow.position.x - shoulder.position.x, dy)
            return acos(dy / mag) * 180 / Math.PI
        }

        val leftElbowAngle = calcAngle(ls, le, lw)
        val rightElbowAngle = calcAngle(rs, re, rw)
        val leftElevation = calcElevation(ls, le)
        val rightElevation = calcElevation(rs, re)

        val avgElbow = (leftElbowAngle + rightElbowAngle) / 2
        val avgElevation = (leftElevation + rightElevation) / 2

        tvMetrics.text = String.format("肘部夹角: %.0f° | 抬臂高度: %.0f°", avgElbow, avgElevation)

        // 拦截投降动作校验
        val shoulderWidth = hypot(ls.position.x - rs.position.x, ls.position.y - rs.position.y)
        val leftLiftRatio = (le.position.y - lw.position.y) / shoulderWidth
        val rightLiftRatio = (re.position.y - rw.position.y) / shoulderWidth

        var isStandard = false
        val statusText: String
        when {
            leftLiftRatio > 0.45 || rightLiftRatio > 0.45 -> {
                statusText = "不要投降，小臂请端平"
                tvStatus.text = "❌ $statusText"
            }
            avgElevation < 52 -> {
                statusText = "胳膊有点掉下来了，抬高点"
                tvStatus.text = "🔻 $statusText"
            }
            avgElevation > 128 -> {
                statusText = "胳膊抬得太高了"
                tvStatus.text = "🔺 $statusText"
            }
            avgElbow < 62 -> {
                statusText = "手肘夹得太紧，外展一些"
                tvStatus.text = "⚠️ $statusText"
            }
            avgElbow > 138 -> {
                statusText = "手臂伸得太直了"
                tvStatus.text = "⚠️ $statusText"
            }
            else -> {
                isStandard = true
                statusText = "动作标准，非常漂亮"
                tvStatus.text = "✅ $statusText"
            }
        }
        speak(statusText)

        // 驱动原生画布刷新骨骼线
        poseOverlayView.updatePose(landmarks, isStandard)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startNativeCamera();
        } else {
            Toast.makeText(this, "需要相机权限才可运行", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
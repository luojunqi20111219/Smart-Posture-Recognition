package com.example.ztsb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.PoseLandmark

class PoseOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var landmarks: List<PoseLandmark> = emptyList()
    private var isStandard: Boolean = false

    private val paintLine = Paint().apply {
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    private val paintPoint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    fun updatePose(landmarks: List<PoseLandmark>, isStandard: Boolean) {
        this.landmarks = landmarks
        this.isStandard = isStandard
        invalidate() // 强制重新绘制
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty()) return

        // 根据动作是否达标变换骨骼线颜色
        paintLine.color = if (isStandard) Color.GREEN else Color.YELLOW

        // ML Kit 关键点编号：11左肩, 12右肩, 13左肘, 14右肘, 15左腕, 16右腕
        val lmMap = landmarks.associateBy { it.landmarkType }
        val ls = lmMap[PoseLandmark.LEFT_SHOULDER]
        val rs = lmMap[PoseLandmark.RIGHT_SHOULDER]
        val le = lmMap[PoseLandmark.LEFT_ELBOW]
        val re = lmMap[PoseLandmark.RIGHT_ELBOW]
        val lw = lmMap[PoseLandmark.LEFT_WRIST]
        val rw = lmMap[PoseLandmark.RIGHT_WRIST]

        // 转换坐标并画线（注意：由于前置摄像头镜像，通常需要处理X轴，此处简化为直接按比例转换）
        fun drawBone(p1: PoseLandmark?, p2: PoseLandmark?) {
            if (p1 != null && p2 != null && p1.inFrameLikelihood > 0.5f && p2.inFrameLikelihood > 0.5f) {
                // 将 0~1 的相对坐标转换为屏幕像素坐标
                val x1 = p1.position.x * width / 480f  // 假设分析器分辨率宽为480
                val y1 = p1.position.y * height / 640f // 假设分析器分辨率高为640
                val x2 = p2.position.x * width / 480f
                val y2 = p2.position.y * height / 640f
                canvas.drawLine(x1, y1, x2, y2, paintLine)
                canvas.drawCircle(x1, y1, 12f, paintPoint)
                canvas.drawCircle(x2, y2, 12f, paintPoint)
            }
        }

        drawBone(ls, rs) // 画双肩
        drawBone(ls, le) // 左大臂
        drawBone(le, lw) // 左小臂
        drawBone(rs, re) // 右大臂
        drawBone(re, rw) // 右小臂
    }
}
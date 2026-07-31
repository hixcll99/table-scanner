package com.example

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import com.example.measurement.CaptureQuality
import com.example.measurement.CaptureRejectionReason
import com.example.measurement.CaptureSession
import com.example.measurement.TiltFilter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(navController: NavController, retakeShotIndex: Int? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        CameraContent(navController, context, lifecycleOwner, retakeShotIndex)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("نحتاج إلى صلاحية الكاميرا للاستمرار.")
        }
    }
}

@Composable
fun CameraContent(
    navController: NavController,
    context: Context,
    lifecycleOwner: LifecycleOwner,
    retakeShotIndex: Int?,
) {
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val coroutineScope = rememberCoroutineScope()

    // Raw + low-pass-filtered tilt. The filter is what drives the level indicator
    // and the shutter-enable decision; the raw values are only used for the small
    // bubble offset animation so it still feels responsive to touch.
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var filteredTiltG by remember { mutableFloatStateOf(0f) }
    var isLevel by remember { mutableStateOf(false) }
    var levelMessage by remember { mutableStateOf("يرجى إمالة الهاتف ليصبح أفقياً تماماً") }

    // Multi-shot flow state.
    var isRetake by remember { mutableStateOf(retakeShotIndex != null) }
    var shotNumber by remember { mutableIntStateOf(1) }
    var totalShots by remember { mutableIntStateOf(CaptureSession.DEFAULT_SHOT_COUNT) }

    // Post-capture quality-gate state.
    var isProcessing by remember { mutableStateOf(false) }
    var rejectionReasons by remember { mutableStateOf<List<CaptureRejectionReason>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (retakeShotIndex == null) {
            CaptureSession.reset()
        }
        shotNumber = retakeShotIndex?.plus(1) ?: CaptureSession.nextShotNumber()
        totalShots = CaptureSession.targetShotCount
    }

    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gravitySensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val tiltFilter = remember { TiltFilter() }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val rawX = it.values[0] / 9.81f
                    val rawY = it.values[1] / 9.81f
                    val (fx, fy) = tiltFilter.update(rawX, rawY)
                    tiltX = fx
                    tiltY = fy

                    val maxTilt = maxOf(abs(fx), abs(fy))
                    filteredTiltG = maxTilt
                    val threshold = TiltFilter.DEFAULT_TILT_THRESHOLD_G
                    val warnThreshold = threshold + TiltFilter.TILT_WARN_MARGIN_G
                    when {
                        maxTilt < threshold -> {
                            isLevel = true
                            levelMessage = "ممتاز! يمكنك التقاط الصورة الآن."
                        }
                        maxTilt < warnThreshold -> {
                            isLevel = false
                            levelMessage = "أنت قريب جداً... واصل التعديل."
                        }
                        else -> {
                            isLevel = false
                            levelMessage = "قم بإمالة الهاتف حتى تتوسط الدائرة."
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(listener)
            cameraExecutor.shutdown()
        }
    }

    fun proceedAfterGoodShot(photoFile: File) {
        val label = "shot_$shotNumber"
        val shot = CaptureSession.Shot(label = label, photoPath = photoFile.absolutePath, filteredTiltG = filteredTiltG)
        if (retakeShotIndex != null) {
            // retakeShotIndex may point at an existing shot (a true retake) or
            // one past the end (an extra shot added from the review screen,
            // e.g. because the reported range was wider than acceptable) —
            // either way, going back to review re-runs the pipeline fresh.
            if (retakeShotIndex < CaptureSession.shots.size) {
                CaptureSession.replaceShot(retakeShotIndex, shot)
            } else {
                CaptureSession.addShot(shot)
            }
            navController.popBackStack()
            return
        }
        CaptureSession.addShot(shot)
        if (CaptureSession.isComplete()) {
            navController.navigate("review")
        } else {
            // Advance in place to the next shot of this session rather than
            // re-navigating, so the camera preview doesn't have to rebind.
            shotNumber = CaptureSession.nextShotNumber()
            totalShots = CaptureSession.targetShotCount
        }
    }

    fun handleCaptured(photoFile: File) {
        isProcessing = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.Default) {
                val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                if (bitmap == null) {
                    com.example.measurement.CaptureQualityResult(
                        passed = false,
                        rejections = listOf(CaptureRejectionReason.PHOTO_UNREADABLE),
                    )
                } else {
                    CaptureQuality.evaluateCapturedPhoto(bitmap, filteredTiltG)
                }
            }
            isProcessing = false
            if (result.passed) {
                rejectionReasons = emptyList()
                proceedAfterGoodShot(photoFile)
            } else {
                rejectionReasons = result.rejections
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraScreen", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Rule of thirds grid
        GridOverlay()

        // Level Indicator
        val levelColor by animateColorAsState(
            targetValue = when {
                isLevel -> Color(0xFF4CAF50)
                maxOf(abs(tiltX), abs(tiltY)) < 1.0f -> Color(0xFFFFEB3B)
                else -> Color(0xFFF44336)
            }
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .border(2.dp, levelColor, CircleShape)
            )

            // The moving bubble
            Box(
                modifier = Modifier
                    .offset(x = (tiltX * -20).dp, y = (tiltY * 20).dp)
                    .size(20.dp)
                    .background(levelColor, CircleShape)
            )
        }

        // Shot progress / instruction message
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val instruction = shotInstruction(shotNumber, totalShots, isRetake)
            Text(
                text = instruction,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = levelMessage,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }

        // Rejection banner — shown after a shot fails the quality gate, offering a
        // cheap retake with a plain-language reason instead of silently accepting
        // a bad frame into the measurement pipeline.
        AnimatedVisibility(
            visible = rejectionReasons.isNotEmpty(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(20.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "لم تنجح الصورة",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                rejectionReasons.forEach { reason ->
                    Text(
                        text = "• ${reason.userMessage}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { rejectionReasons = emptyList() }) {
                    Text("إعادة المحاولة")
                }
            }
        }

        if (isProcessing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Capture Button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .size(80.dp)
                .background(
                    if (isLevel) Color.White else Color.White.copy(alpha = 0.5f),
                    CircleShape
                )
                .border(4.dp, Color.LightGray.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (isProcessing) return@Button
                    rejectionReasons = emptyList()
                    val photoFile = File(context.cacheDir, "captured_table_shot_${shotNumber}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                handleCaptured(photoFile)
                            }
                            override fun onError(exc: ImageCaptureException) {
                                Log.e("CameraScreen", "Photo capture failed: ${exc.message}", exc)
                            }
                        }
                    )
                },
                enabled = isLevel && !isProcessing,
                modifier = Modifier.size(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    disabledContainerColor = Color.Transparent
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) { }
        }
    }
}

private fun shotInstruction(shotNumber: Int, totalShots: Int, isRetake: Boolean): String {
    if (isRetake) return "إعادة التقاط الصورة"
    if (totalShots <= 1) return "التقط صورة واحدة للطاولة كاملة"
    return when (shotNumber) {
        1 -> "الصورة 1 من $totalShots — التقط الطرف الأقرب من الطاولة"
        totalShots -> "الصورة $shotNumber من $totalShots — التقط الطرف البعيد من الطاولة"
        else -> "الصورة $shotNumber من $totalShots — التقط الجانب التالي من الطاولة"
    }
}

@Composable
fun GridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Vertical lines
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(width / 3, 0f),
            end = Offset(width / 3, height),
            strokeWidth = 2f
        )
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(width * 2 / 3, 0f),
            end = Offset(width * 2 / 3, height),
            strokeWidth = 2f
        )

        // Horizontal lines
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(0f, height / 3),
            end = Offset(width, height / 3),
            strokeWidth = 2f
        )
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(0f, height * 2 / 3),
            end = Offset(width, height * 2 / 3),
            strokeWidth = 2f
        )
    }
}

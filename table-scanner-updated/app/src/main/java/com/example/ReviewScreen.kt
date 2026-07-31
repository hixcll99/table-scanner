package com.example

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.measurement.CaptureSession
import com.example.measurement.MeasurementPipeline
import com.example.measurement.TableMeasurementResult
import com.example.ui.theme.Turquoise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ReviewState {
    data object Loading : ReviewState
    data class Success(val pipelineResult: MeasurementPipeline.PipelineResult, val displayImage: File?) : ReviewState
    data class Failure(val reason: String) : ReviewState
}

@Composable
fun ReviewScreen(navController: NavController) {
    val context = LocalContext.current
    var showGrid by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ReviewState>(ReviewState.Loading) }

    LaunchedEffect(Unit) {
        val outputDir = File(context.cacheDir, "measurement").apply { mkdirs() }
        val result = withContext(Dispatchers.Default) {
            MeasurementPipeline.run(context, outputDir)
        }
        state = when {
            result.overallFailureReason != null -> ReviewState.Failure(result.overallFailureReason)
            result.finalResult == null -> ReviewState.Failure("تعذر إكمال القياس.")
            else -> {
                val displayPath = result.shotOutcomes.firstOrNull { it.rectifiedImagePath != null }?.rectifiedImagePath
                ReviewState.Success(result, displayPath?.let { File(it) })
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "نتيجة القياس",
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(
                onClick = {
                    CaptureSession.reset()
                    navController.popBackStack("home", inclusive = false)
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "إعادة التصوير",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Image / result preview area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is ReviewState.Loading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("جارٍ حساب القياسات...", color = Color.White.copy(alpha = 0.7f))
                    }
                }
                is ReviewState.Failure -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(s.reason, color = Color.White, textAlign = TextAlign.Center)
                    }
                }
                is ReviewState.Success -> {
                    if (s.displayImage != null && s.displayImage.exists()) {
                        Image(
                            painter = rememberAsyncImagePainter(s.displayImage),
                            contentDescription = "الصورة بعد التصحيح",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (showGrid) {
                            val ppmm = s.pipelineResult.shotOutcomes
                                .firstOrNull { it.rectifiedImagePath == s.displayImage.absolutePath }
                                ?.measurement?.pixelsPerMmCardA
                            MeasurementGridOverlay(pixelsPerMm = ppmm ?: 4.0)
                        }
                    } else {
                        Text("لا توجد صورة مصححة للعرض", color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        }

        // Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val successState = state as? ReviewState.Success
            val finalResult = successState?.pipelineResult?.finalResult

            if (finalResult != null) {
                MeasurementSummary(finalResult)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "عرض شبكة القياس الفعلية",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Switch(
                    checked = showGrid,
                    onCheckedChange = { showGrid = it },
                    enabled = successState?.displayImage != null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cheap retake path when confidence is wide — cheaper than starting
            // the whole session over, and directly addresses design constraint
            // #6/#7 (report a range, make it cheap to tighten it).
            if (finalResult != null && !finalResult.isConfident()) {
                OutlinedButton(
                    onClick = {
                        val newShotNumber = CaptureSession.shots.size + 1 // 1-based, matches route's indexing
                        CaptureSession.addExtraShot()
                        navController.navigate("camera_retake/$newShotNumber")
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("النتيجة غير دقيقة كفاية — التقط صورة إضافية")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    val photoFile = successState?.displayImage
                    if (photoFile != null && photoFile.exists()) {
                        val uri: Uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            photoFile
                        )
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, uri)
                            type = "image/jpeg"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "إرسال إلى الاستوديو"))
                    }
                },
                enabled = successState?.displayImage != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "إرسال إلى الاستوديو",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun MeasurementSummary(result: TableMeasurementResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("العرض", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Text(
                    result.widthRange.toCmString(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("الطول", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Text(
                    result.heightRange.toCmString(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "استناداً إلى ${result.shotCount} ${if (result.shotCount == 1) "صورة" else "صور"}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        if (result.warnings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            result.warnings.forEach { warning ->
                Text(
                    "⚠ $warning",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** Grid overlay spaced at real 10cm increments using the rectified image's actual pixels-per-mm scale. */
@Composable
private fun MeasurementGridOverlay(pixelsPerMm: Double) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val stepPx = (100.0 * pixelsPerMm).toFloat() // 10cm = 100mm
        if (stepPx <= 1f) return@Canvas

        var x = stepPx
        while (x < size.width) {
            drawLine(
                color = Turquoise.copy(alpha = 0.5f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f
            )
            x += stepPx
        }

        var y = stepPx
        while (y < size.height) {
            drawLine(
                color = Turquoise.copy(alpha = 0.5f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f
            )
            y += stepPx
        }
    }
}

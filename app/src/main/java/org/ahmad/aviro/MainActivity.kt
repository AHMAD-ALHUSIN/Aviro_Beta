package org.ahmad.aviro

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var audioOnly by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("جارٍ التهيئة...") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // تهيئة yt-dlp و ffmpeg مرة واحدة
    LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)

            status = "جارٍ تحديث yt-dlp..."
            try {
                YoutubeDL.getInstance().updateYoutubeDL(ctx)
            } catch (_: Exception) {
                // لا إنترنت أو فشل التحديث: نكمل بالنسخة الموجودة
            }

            ready = true
            status = "جاهز — yt-dlp ${YoutubeDL.getInstance().version(ctx)}"
        } catch (e: Exception) {
            status = "فشلت التهيئة: ${e.message}"
        }
    }
}

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Aviro", style = MaterialTheme.typography.headlineLarge)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("رابط الفيديو") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = audioOnly, onCheckedChange = { audioOnly = it })
            Spacer(Modifier.width(10.dp))
            Text("صوت فقط (MP3)")
        }

        Button(
            enabled = ready && !busy && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                progress = 0f
                status = "جارٍ التنزيل..."
                scope.launch {
                    status = try {
                        val name = download(ctx, url.trim(), audioOnly) { progress = it }
                        "تم الحفظ في Download/Aviro:\n$name"
                    } catch (e: Exception) {
                        "خطأ: ${e.message}"
                    }
                    busy = false
                }
            }
        ) { Text("تنزيل") }

        if (busy) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${(progress * 100).toInt()}%")
        }

        Text(status)
    }
}

/** ينزّل ويدمج، ثم ينقل الملف إلى مجلد التنزيلات العام. يرجع اسم الملف. */
suspend fun download(
    ctx: Context,
    url: String,
    audioOnly: Boolean,
    onProgress: (Float) -> Unit
): String = withContext(Dispatchers.IO) {
    val tmp = File(ctx.cacheDir, "dl_${System.currentTimeMillis()}").apply { mkdirs() }

    val req = YoutubeDLRequest(url).apply {
        addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")
        addOption("--no-mtime")
        if (audioOnly) {
            addOption("-x")
            addOption("--audio-format", "mp3")
        } else {
            addOption("-f", "bv*+ba/b")          // أفضل فيديو + أفضل صوت
            addOption("--merge-output-format", "mp4") // الدمج عبر ffmpeg
        }
    }

    YoutubeDL.getInstance().execute(req, "aviro") { p, _, _ ->
        onProgress((p / 100f).coerceIn(0f, 1f))
    }

    val file = tmp.listFiles()
        ?.firstOrNull { it.extension in setOf("mp4", "mp3", "mkv", "webm", "m4a") }
        ?: error("لم يتم العثور على الملف الناتج")

    val mime = if (file.extension == "mp3") "audio/mpeg" else "video/mp4"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Aviro")
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("تعذّر إنشاء الملف")
    resolver.openOutputStream(uri)!!.use { out ->
        file.inputStream().use { it.copyTo(out) }
    }

    tmp.deleteRecursively()
    file.name
}

package com.aazim.antigravity.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aazim.antigravity.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * In-app Preview: a WebView panel for the site the agent serves locally.
 *
 * v1.1.3 fix: the old screen blindly loaded port 8000 and showed the raw
 * WebView "ERR_CONNECTION_REFUSED" page when the agent's server was on
 * another port (e.g. a Node site on 3000) or had stopped. Now opening
 * Preview first probes the common dev ports on 127.0.0.1 and loads the
 * first one that answers; the last working port is remembered and tried
 * first next time. When nothing answers, a plain-language screen explains
 * that the server is not running and offers a re-scan plus port shortcuts
 * instead of the browser error page.
 */
private val COMMON_PORTS = listOf(3000, 8000, 5173, 8080, 5000, 4200, 5500, 9000)
private const val PREVIEW_PREFS = "preview_prefs"
private const val KEY_LAST_PORT = "last_port"

private enum class PreviewStatus { SCANNING, READY, FAILED }

private fun portOf(url: String): Int? =
    Regex(":(\\d{2,5})(/|$)").find(url)?.groupValues?.get(1)?.toIntOrNull()

private suspend fun probePorts(candidates: List<Int>): Int? = withContext(Dispatchers.IO) {
    for (port in candidates) {
        val open = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                true
            }
        } catch (e: Exception) {
            false
        }
        if (open) return@withContext port
    }
    null
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREVIEW_PREFS, Context.MODE_PRIVATE) }
    var urlText by remember { mutableStateOf("http://127.0.0.1:8000") }
    var loadedUrl by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf(PreviewStatus.SCANNING) }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return loadedUrl
        // Allow typing just a port, e.g. "3000".
        if (trimmed.all { it.isDigit() }) return "http://127.0.0.1:$trimmed"
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }

    fun loadUrl(target: String) {
        urlText = target
        loadedUrl = target
        status = PreviewStatus.SCANNING
        webView?.loadUrl(target)
    }

    fun scanAndLoad() {
        status = PreviewStatus.SCANNING
        scope.launch {
            val last = prefs.getInt(KEY_LAST_PORT, -1)
            val candidates = (listOf(last) + COMMON_PORTS).filter { it > 0 }.distinct()
            val found = probePorts(candidates)
            if (found != null) {
                loadUrl("http://127.0.0.1:$found")
            } else {
                status = PreviewStatus.FAILED
            }
        }
    }

    LaunchedEffect(Unit) { scanAndLoad() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back_to_chat),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.preview_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = {
                        if (loadedUrl.isNotBlank() && status == PreviewStatus.READY) webView?.reload()
                        else scanAndLoad()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.preview_reload),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        val target = loadedUrl.ifBlank { urlText }
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                        } catch (e: Exception) { /* no browser */ }
                    }) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = stringResource(R.string.preview_open_in_browser),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.preview_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text(stringResource(R.string.preview_url_label)) },
                    placeholder = { Text("http://127.0.0.1:3000") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { loadUrl(normalize(urlText)) }),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { loadUrl(normalize(urlText)) }) {
                    Text(stringResource(R.string.preview_load))
                }
            }
            Text(
                text = stringResource(R.string.preview_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        status = PreviewStatus.FAILED
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (status != PreviewStatus.FAILED) {
                                        status = PreviewStatus.READY
                                        portOf(url ?: "")?.let { port ->
                                            prefs.edit().putInt(KEY_LAST_PORT, port).apply()
                                        }
                                    }
                                }
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webView = this
                            if (loadedUrl.isNotBlank()) loadUrl(loadedUrl)
                        }
                    },
                    update = { view ->
                        webView = view
                        if (loadedUrl.isNotBlank() && view.url != loadedUrl && status == PreviewStatus.SCANNING) {
                            view.loadUrl(loadedUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (status == PreviewStatus.SCANNING) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.preview_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (status == PreviewStatus.FAILED) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.preview_no_server_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.preview_no_server_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { scanAndLoad() }) {
                            Text(stringResource(R.string.preview_scan_again))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(3000, 8000, 5173, 8080).forEach { port ->
                                OutlinedButton(onClick = { loadUrl("http://127.0.0.1:$port") }) {
                                    Text("$port")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

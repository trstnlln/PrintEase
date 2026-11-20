package com.printease.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.net.URI
import java.util.Locale

fun googleProfilePhoto(value: String?): String? =
    runCatching {
            val uri = URI(value ?: return null)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            value.takeIf {
                uri.scheme == "https" &&
                    uri.userInfo == null &&
                    uri.port in listOf(-1, 443) &&
                    (host == "googleusercontent.com" ||
                        host.endsWith(".googleusercontent.com") ||
                        host.endsWith(".ggpht.com"))
            }
        }
        .getOrNull()

@Composable
fun UserAvatar(session: Session, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current
    Surface(
        modifier.size(size).semantics { contentDescription = "Profile for ${session.name}" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                session.name.trim().take(1).uppercase(Locale.ROOT).ifBlank { "P" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style =
                    if (size >= 64.dp) MaterialTheme.typography.headlineLarge
                    else MaterialTheme.typography.titleMedium,
            )
            googleProfilePhoto(session.photoUrl)?.let { photo ->
                AsyncImage(
                    model =
                        ImageRequest.Builder(context)
                            .data(photo)
                            .crossfade(true)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .memoryCacheKey("${session.uid}:$photo")
                            .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

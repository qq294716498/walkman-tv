package com.walkman.tv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.walkman.tv.data.store.FoliaBackground
import com.walkman.tv.data.store.FoliaTemplate
import com.walkman.tv.data.store.LyricStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight 16:9 vector/Canvas preview thumbnails for every Folia template, native style,
 * background shader, and random shuffle mode — zero bitmap RAM overhead, razor sharp on 4K TV.
 */
@Composable
fun ShufflePreviewThumb(
    accent: Color,
    bright: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(Color(0xFF151926), Color(0xFF1E2235), accent.copy(alpha = 0.22f))
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.minDimension * 0.65f,
                ),
            )
        }
        Icon(
            imageVector = Icons.Filled.Shuffle,
            contentDescription = null,
            tint = bright,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
fun NativeStylePreviewThumb(
    style: LyricStyle,
    accent: Color,
    bright: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.background(Color(0xFF101522))) {
        val w = size.width
        val h = size.height
        // Mini vinyl disc on right
        drawCircle(color = Color(0xFF1C2333), radius = h * 0.34f, center = Offset(w * 0.78f, h * 0.5f))
        drawCircle(color = accent.copy(alpha = 0.55f), radius = h * 0.13f, center = Offset(w * 0.78f, h * 0.5f))
        drawCircle(color = Color(0xFF0A0D16), radius = h * 0.04f, center = Offset(w * 0.78f, h * 0.5f))

        if (style == LyricStyle.CLASSIC) {
            // Centered classic bars on left area
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(w * 0.14f, h * 0.26f), Size(w * 0.34f, h * 0.09f), CornerRadius(4f))
            drawRoundRect(bright, Offset(w * 0.10f, h * 0.45f), Size(w * 0.42f, h * 0.12f), CornerRadius(5f))
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(w * 0.16f, h * 0.66f), Size(w * 0.30f, h * 0.09f), CornerRadius(4f))
        } else {
            // Apple left-aligned bold + sweep highlight
            drawRoundRect(Color.White.copy(alpha = 0.20f), Offset(w * 0.08f, h * 0.22f), Size(w * 0.36f, h * 0.09f), CornerRadius(4f))
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(Color.White, Color.White, Color.White.copy(alpha = 0.35f))),
                topLeft = Offset(w * 0.08f, h * 0.42f),
                size = Size(w * 0.46f, h * 0.15f),
                cornerRadius = CornerRadius(5f),
            )
            drawRoundRect(Color.White.copy(alpha = 0.32f), Offset(w * 0.08f, h * 0.66f), Size(w * 0.32f, h * 0.09f), CornerRadius(4f))
        }
    }
}

@Composable
fun FoliaTemplateThumb(
    template: FoliaTemplate,
    accent: Color,
    bright: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.background(Color(0xFF101320))) {
        val w = size.width
        val h = size.height
        val softWhite = Color.White.copy(alpha = 0.28f)
        val dimWhite = Color.White.copy(alpha = 0.14f)

        when (template) {
            FoliaTemplate.CLASSIC -> {
                drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent), Offset(w * 0.5f, h * 0.5f), w * 0.6f))
                drawRoundRect(softWhite, Offset(w * 0.28f, h * 0.24f), Size(w * 0.44f, h * 0.09f), CornerRadius(4f))
                drawRoundRect(bright, Offset(w * 0.18f, h * 0.44f), Size(w * 0.64f, h * 0.13f), CornerRadius(5f))
                drawRoundRect(softWhite, Offset(w * 0.30f, h * 0.68f), Size(w * 0.40f, h * 0.09f), CornerRadius(4f))
            }
            FoliaTemplate.STILL -> {
                drawRect(Color(0xFF0A0C12))
                drawRoundRect(Color.White.copy(alpha = 0.88f), Offset(w * 0.24f, h * 0.44f), Size(w * 0.52f, h * 0.12f), CornerRadius(4f))
            }
            FoliaTemplate.CADENZA -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF121829), Color(0xFF1F1D36))))
                val spots = listOf(
                    Triple(0.18f, 0.28f, 0.14f),
                    Triple(0.42f, 0.22f, 0.16f),
                    Triple(0.68f, 0.32f, 0.13f),
                    Triple(0.28f, 0.58f, 0.18f),
                    Triple(0.56f, 0.62f, 0.15f),
                    Triple(0.78f, 0.66f, 0.12f),
                )
                spots.forEachIndexed { i, (px, py, s) ->
                    val col = if (i % 2 == 0) bright else Color.White.copy(alpha = 0.72f)
                    drawRoundRect(col, Offset(w * px, h * py), Size(h * s, h * s), CornerRadius(4f))
                }
            }
            FoliaTemplate.PARTITA -> {
                drawRect(Color(0xFF111827))
                drawLine(bright.copy(alpha = 0.4f), Offset(w * 0.14f, h * 0.14f), Offset(w * 0.86f, h * 0.86f), strokeWidth = 2f)
                for (i in 0..3) {
                    val x = w * (0.18f + i * 0.16f)
                    val y = h * (0.20f + i * 0.18f)
                    drawCircle(if (i == 1) accent else softWhite, radius = 3.5f, center = Offset(x - 8f, y + h * 0.04f))
                    drawRoundRect(if (i == 1) Color.White else softWhite, Offset(x, y), Size(w * 0.26f, h * 0.09f), CornerRadius(3f))
                }
            }
            FoliaTemplate.FUME -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0E1324), Color(0xFF1A1F36))))
                drawRoundRect(dimWhite, Offset(w * 0.12f, h * 0.14f), Size(w * 0.76f, h * 0.14f), CornerRadius(4f))
                drawRoundRect(Color.White, Offset(w * 0.08f, h * 0.40f), Size(w * 0.84f, h * 0.20f), CornerRadius(5f))
                drawRoundRect(dimWhite, Offset(w * 0.16f, h * 0.72f), Size(w * 0.68f, h * 0.14f), CornerRadius(4f))
            }
            FoliaTemplate.CAPPELLA -> {
                drawRect(Color(0xFF121626))
                // Left bubble
                drawCircle(Color(0xFF6366F1), radius = h * 0.09f, center = Offset(w * 0.14f, h * 0.30f))
                drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(w * 0.22f, h * 0.20f), Size(w * 0.44f, h * 0.20f), CornerRadius(10f))
                // Right bubble
                drawCircle(accent, radius = h * 0.09f, center = Offset(w * 0.86f, h * 0.68f))
                drawRoundRect(accent.copy(alpha = 0.75f), Offset(w * 0.34f, h * 0.58f), Size(w * 0.44f, h * 0.20f), CornerRadius(10f))
            }
            FoliaTemplate.TILT -> {
                drawRect(Color(0xFF141828))
                rotate(degrees = -7f, pivot = Offset(w * 0.5f, h * 0.5f)) {
                    drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(w * 0.16f, h * 0.32f), Size(w * 0.58f, h * 0.13f), CornerRadius(4f))
                    drawRoundRect(bright, Offset(w * 0.24f, h * 0.54f), Size(w * 0.62f, h * 0.15f), CornerRadius(4f))
                }
            }
            FoliaTemplate.CLADDAGH -> {
                drawRect(Color(0xFF0E1322))
                rotate(degrees = -10f, pivot = Offset(w * 0.5f, h * 0.5f)) {
                    drawOval(
                        color = bright.copy(alpha = 0.38f),
                        topLeft = Offset(w * 0.14f, h * 0.24f),
                        size = Size(w * 0.72f, h * 0.52f),
                        style = Stroke(width = 2.2f),
                    )
                    drawRoundRect(Color.White, Offset(w * 0.28f, h * 0.66f), Size(w * 0.44f, h * 0.14f), CornerRadius(4f))
                    drawRoundRect(dimWhite, Offset(w * 0.36f, h * 0.20f), Size(w * 0.28f, h * 0.09f), CornerRadius(3f))
                }
            }
            FoliaTemplate.MONET -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF14212E), Color(0xFF1E293B))))
                // Left lyric rail
                drawRoundRect(softWhite, Offset(w * 0.08f, h * 0.22f), Size(w * 0.38f, h * 0.09f), CornerRadius(3f))
                drawRoundRect(Color.White, Offset(w * 0.08f, h * 0.38f), Size(w * 0.44f, h * 0.12f), CornerRadius(4f))
                drawRoundRect(softWhite, Offset(w * 0.08f, h * 0.56f), Size(w * 0.34f, h * 0.09f), CornerRadius(3f))
                // Right portrait card
                drawRoundRect(accent.copy(alpha = 0.45f), Offset(w * 0.62f, h * 0.16f), Size(w * 0.28f, h * 0.54f), CornerRadius(6f))
                // Bottom spectrum
                for (b in 0..11) {
                    val bh = h * (0.06f + ((b * 7) % 5) * 0.025f)
                    drawRect(bright.copy(alpha = 0.7f), Offset(w * (0.08f + b * 0.07f), h * 0.88f - bh), Size(w * 0.045f, bh))
                }
            }
            FoliaTemplate.DIORAMA -> {
                drawRect(Color(0xFF0B0F1A))
                // 3D horizon grid
                val hy = h * 0.58f
                for (i in -3..3) {
                    drawLine(accent.copy(alpha = 0.32f), Offset(w * 0.5f + i * w * 0.06f, hy), Offset(w * 0.5f + i * w * 0.24f, h), strokeWidth = 1.2f)
                }
                drawLine(accent.copy(alpha = 0.35f), Offset(0f, hy), Offset(w, hy), strokeWidth = 1.2f)
                drawLine(accent.copy(alpha = 0.25f), Offset(0f, h * 0.78f), Offset(w, h * 0.78f), strokeWidth = 1.2f)
                drawRoundRect(Color.White, Offset(w * 0.22f, h * 0.32f), Size(w * 0.56f, h * 0.14f), CornerRadius(3f))
            }
            FoliaTemplate.PENDOLO -> {
                drawRect(Color(0xFF111521))
                val c = Offset(w * 0.32f, h * 0.50f)
                val r = h * 0.32f
                drawCircle(bright.copy(alpha = 0.45f), r, c, style = Stroke(2f))
                drawCircle(accent.copy(alpha = 0.35f), r * 0.65f, c)
                drawLine(Color.White, c, Offset(c.x + r * 0.7f, c.y - r * 0.4f), strokeWidth = 2f)
                drawRoundRect(softWhite, Offset(w * 0.56f, h * 0.26f), Size(w * 0.30f, h * 0.08f), CornerRadius(3f))
                drawRoundRect(Color.White, Offset(w * 0.58f, h * 0.44f), Size(w * 0.34f, h * 0.12f), CornerRadius(4f))
                drawRoundRect(softWhite, Offset(w * 0.56f, h * 0.66f), Size(w * 0.28f, h * 0.08f), CornerRadius(3f))
            }
            FoliaTemplate.SONNET -> {
                drawRect(Color(0xFF141622))
                drawRect(bright.copy(alpha = 0.38f), Offset(w * 0.10f, h * 0.14f), Size(w * 0.80f, h * 0.72f), style = Stroke(1.5f))
                drawLine(accent, Offset(w * 0.40f, h * 0.34f), Offset(w * 0.60f, h * 0.34f), strokeWidth = 2f)
                drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(w * 0.24f, h * 0.46f), Size(w * 0.52f, h * 0.12f), CornerRadius(3f))
                drawRoundRect(softWhite, Offset(w * 0.34f, h * 0.66f), Size(w * 0.32f, h * 0.07f), CornerRadius(3f))
            }
            FoliaTemplate.TEMPERA -> {
                drawRect(Color(0xFF1A1817))
                drawRect(Color(0xFFE2D7BE), Offset(w * 0.10f, h * 0.14f), Size(w * 0.50f, h * 0.72f))
                drawRect(accent.copy(alpha = 0.85f), Offset(w * 0.54f, h * 0.24f), Size(w * 0.36f, h * 0.56f))
                drawRoundRect(Color(0xFF141414), Offset(w * 0.22f, h * 0.42f), Size(w * 0.56f, h * 0.16f), CornerRadius(4f))
            }
            FoliaTemplate.LUMIERE -> {
                drawRect(Color(0xFF0A0908))
                val beam = Path().apply {
                    moveTo(w * 0.38f, 0f)
                    lineTo(w * 0.62f, 0f)
                    lineTo(w * 0.80f, h)
                    lineTo(w * 0.20f, h)
                    close()
                }
                drawPath(beam, Brush.verticalGradient(listOf(Color(0x88FDE68A), Color(0x22F59E0B), Color.Transparent)))
                drawRoundRect(Color(0xFFFFFBEB), Offset(w * 0.28f, h * 0.45f), Size(w * 0.44f, h * 0.11f), CornerRadius(4f))
            }
            FoliaTemplate.NEBBIA -> {
                drawRect(Brush.radialGradient(listOf(Color(0xFF3E3529), Color(0xFF13131A)), Offset(w * 0.5f, h * 0.5f), w * 0.65f))
                drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(w * 0.14f, h * 0.22f), Size(w * 0.72f, h * 0.56f), CornerRadius(12f))
                drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(w * 0.26f, h * 0.44f), Size(w * 0.48f, h * 0.11f), CornerRadius(4f))
            }
            FoliaTemplate.METAPHOR -> {
                drawRect(Color(0xFFE5E7EB))
                drawRect(Color(0xFF090B10), Offset(0f, 0f), Size(w, h * 0.16f))
                drawRect(Color(0xFF090B10), Offset(0f, h * 0.84f), Size(w, h * 0.16f))
                drawRoundRect(Color(0xFF111827), Offset(w * 0.16f, h * 0.38f), Size(w * 0.68f, h * 0.22f), CornerRadius(4f))
            }
            FoliaTemplate.HANABI -> {
                drawRect(Brush.radialGradient(listOf(Color(0xFF2D1229), Color(0xFF0D0B18)), Offset(w * 0.5f, h * 0.5f), w * 0.65f))
                val c = Offset(w * 0.5f, h * 0.5f)
                for (k in 0..9) {
                    val a = (k * 36.0) * PI / 180.0
                    val end = Offset(c.x + (cos(a) * h * 0.36f).toFloat(), c.y + (sin(a) * h * 0.36f).toFloat())
                    drawLine(if (k % 2 == 0) Color(0xFFFBBF24) else Color(0xFFF472B6), c, end, strokeWidth = 2f, cap = StrokeCap.Round)
                }
                drawCircle(Color(0xFFFDE68A), radius = h * 0.16f, center = c, style = Stroke(2f))
            }
            FoliaTemplate.MACHI -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))))
                // Isometric building silhouettes + neon signs
                drawRect(Color(0xFF273043), Offset(w * 0.12f, h * 0.24f), Size(w * 0.36f, h * 0.68f))
                drawRect(Color(0xFF1E2433), Offset(w * 0.46f, h * 0.14f), Size(w * 0.42f, h * 0.78f))
                drawRoundRect(Color(0xFFF43F5E), Offset(w * 0.20f, h * 0.36f), Size(w * 0.22f, h * 0.12f), CornerRadius(3f))
                drawRoundRect(Color(0xFF38BDF8), Offset(w * 0.54f, h * 0.26f), Size(w * 0.26f, h * 0.12f), CornerRadius(3f))
                drawRoundRect(Color(0xFFFBBF24), Offset(w * 0.52f, h * 0.48f), Size(w * 0.30f, h * 0.16f), CornerRadius(3f))
            }
        }
    }
}

@Composable
fun FoliaBackgroundThumb(
    bg: FoliaBackground,
    modifier: Modifier = Modifier,
) {
    val cA = Color(bg.previewColorA)
    val cB = Color(bg.previewColorB)
    Canvas(modifier = modifier.background(Color(0xFF0A0D16))) {
        val w = size.width
        val h = size.height
        when (bg) {
            FoliaBackground.LATENT -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF0B1120), cA.copy(alpha = 0.65f), Color(0xFF0F172A))))
                drawCircle(Brush.radialGradient(listOf(cB.copy(alpha = 0.65f), Color.Transparent), Offset(w * 0.28f, h * 0.35f), w * 0.48f))
                drawCircle(Brush.radialGradient(listOf(cA.copy(alpha = 0.8f), Color.Transparent), Offset(w * 0.75f, h * 0.68f), w * 0.52f))
            }
            FoliaBackground.COMMON -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF16192C), Color(0xFF232642))))
                drawCircle(Brush.radialGradient(listOf(cB.copy(alpha = 0.45f), Color.Transparent), Offset(w * 0.5f, h * 0.5f), w * 0.5f))
                // Subtle geometric triangles matching Folia's common bg
                val tri = Path().apply {
                    moveTo(w * 0.22f, h * 0.24f); lineTo(w * 0.30f, h * 0.34f); lineTo(w * 0.18f, h * 0.36f); close()
                }
                drawPath(tri, Color.White.copy(alpha = 0.14f))
                val tri2 = Path().apply {
                    moveTo(w * 0.78f, h * 0.52f); lineTo(w * 0.86f, h * 0.64f); lineTo(w * 0.74f, h * 0.66f); close()
                }
                drawPath(tri2, Color.White.copy(alpha = 0.12f))
            }
            FoliaBackground.MONET -> {
                drawRect(Brush.linearGradient(listOf(Color(0xFF2B2838), Color(0xFF3E3546))))
                drawCircle(Brush.radialGradient(listOf(Color(0x88F59E0B), Color.Transparent), Offset(w * 0.32f, h * 0.42f), w * 0.42f))
                drawCircle(Brush.radialGradient(listOf(Color(0x882DD4BF), Color.Transparent), Offset(w * 0.68f, h * 0.56f), w * 0.46f))
            }
            FoliaBackground.NOMAND -> {
                drawRect(Color(0xFF0A0F1D))
                drawCircle(Brush.radialGradient(listOf(cB.copy(alpha = 0.30f), Color.Transparent), Offset(w * 0.5f, h * 0.5f), w * 0.55f))
                for (i in 0..22) {
                    val sx = w * (((i * 37) % 97) / 97f)
                    val sy = h * (((i * 53) % 89) / 89f)
                    drawCircle(Color.White.copy(alpha = if (i % 3 == 0) 0.85f else 0.35f), radius = if (i % 4 == 0) 2.2f else 1.2f, center = Offset(sx, sy))
                }
            }
            FoliaBackground.SORA -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0C4A6E), Color(0xFF38BDF8).copy(alpha = 0.55f), Color(0xFFFB7185).copy(alpha = 0.65f))))
                drawOval(Color.White.copy(alpha = 0.22f), Offset(w * 0.12f, h * 0.38f), Size(w * 0.46f, h * 0.22f))
                drawOval(Color.White.copy(alpha = 0.18f), Offset(w * 0.48f, h * 0.50f), Size(w * 0.44f, h * 0.20f))
            }
            FoliaBackground.PURE -> {
                drawRect(Color(0xFF06070B))
                for (i in 0..6) {
                    val sx = w * (((i * 41 + 11) % 89) / 89f)
                    val sy = h * (((i * 29 + 7) % 79) / 79f)
                    drawCircle(Color.White.copy(alpha = 0.25f), radius = 1.1f, center = Offset(sx, sy))
                }
            }
        }
    }
}

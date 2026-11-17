package no.aura.os.v2.cards

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Cheap glass sheen over artwork; apply after clipping, before content padding. No blur or extra layer. */
fun Modifier.auraGlass(shape: Shape): Modifier = drawWithCache {
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.045f), Color.Transparent))
    onDrawWithContent {
        drawContent()
        drawRect(sheen)
    }
}.border(1.dp, Color.White.copy(alpha = 0.25f), shape)

/** Episode artwork only: fine edge, soft upper reflection and diagonal gloss, without blur or a bitmap layer. */
fun Modifier.auraEpisodeGloss(shape: Shape): Modifier = drawWithCache {
    val topLight = Brush.verticalGradient(listOf(Color.White.copy(alpha=.20f), Color.White.copy(alpha=.025f), Color.Transparent))
    val reflection = Brush.linearGradient(listOf(Color.White.copy(alpha=.07f), Color.Transparent, Color.Transparent),
        start=androidx.compose.ui.geometry.Offset.Zero, end=androidx.compose.ui.geometry.Offset(size.width*.75f,size.height))
    onDrawWithContent { drawContent(); drawRect(topLight); drawRect(reflection) }
}.border(1.dp, Color.White.copy(alpha=.24f), shape)

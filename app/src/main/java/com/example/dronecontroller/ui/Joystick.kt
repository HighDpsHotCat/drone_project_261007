package com.example.dronecontroller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.dronecontroller.control.Stick
import com.example.dronecontroller.control.normalizedStick

@Composable
fun Joystick(label: String, diameter: Dp, verticalOnly: Boolean, enabled: Boolean, onInput: (Stick) -> Unit) {
    var position by remember { mutableStateOf(Stick()) }
    val currentInput by rememberUpdatedState(onInput)
    DisposableEffect(enabled) {
        if (!enabled) position = Stick()
        onDispose { position = Stick(); currentInput(Stick()) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(diameter).semantics { contentDescription = label }
            .pointerInput(enabled, verticalOnly) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) * 0.36f
                    fun update(point: Offset) {
                        position = normalizedStick((point.x-center.x)/radius, (center.y-point.y)/radius, verticalOnly)
                        currentInput(position)
                    }
                    down.consume(); update(down.position)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { change.consume(); break }
                            change.consume(); update(change.position)
                        }
                    } finally { position = Stick(); currentInput(position) }
                }
            }) {
            val radius = size.minDimension * 0.46f
            val center = Offset(size.width / 2, size.height / 2)
            val color = if (enabled) Color(0xFF79D4FE) else Color.Gray
            drawCircle(Color(0xDA122331), radius, center)
            drawCircle(color.copy(alpha = .7f), radius, center, style = Stroke(2.dp.toPx()))
            drawLine(color.copy(alpha = .35f), Offset(center.x, center.y-radius*.75f), Offset(center.x, center.y+radius*.75f), 2.dp.toPx())
            if (!verticalOnly) drawLine(color.copy(alpha=.35f), Offset(center.x-radius*.75f,center.y), Offset(center.x+radius*.75f,center.y), 2.dp.toPx())
            val knob = Offset(center.x+position.x*size.minDimension*.36f, center.y-position.y*size.minDimension*.36f)
            drawCircle(color, radius*.26f, knob)
        }
        Text(label, color = Color.White)
    }
}

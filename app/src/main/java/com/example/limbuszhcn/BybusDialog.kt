package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.limbuszhcn.ui.theme.LauncherColors as C

/** Shared bounded prompt: body scrolls independently; equally sized action areas stay visible. */
@Composable
internal fun BybusDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, shape: Shape = RoundedCornerShape(26.dp),
    containerColor: Color = C.Panel, properties: DialogProperties = DialogProperties(), destructive: Boolean = false) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .86f).dp
    Dialog(onDismissRequest, DialogProperties(dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside, securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = properties.decorFitsSystemWindows)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(.22f)
                setWindowAnimations(R.style.BybusDialogAnimation)
            }
        }
        Surface(modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = shape, color = containerColor, tonalElevation = 0.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                icon?.let { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
                title?.let { ProvideTextStyle(MaterialTheme.typography.titleMedium) { it() } }
                text?.let {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        MaterialTheme(colorScheme = if (destructive) MaterialTheme.colorScheme.copy(primary = C.Error) else MaterialTheme.colorScheme) {
                            ProvideTextStyle(MaterialTheme.typography.labelLarge) { confirmButton() }
                        }
                    }
                    dismissButton?.let { Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) { it() } }
                }
            }
        }
    }
}

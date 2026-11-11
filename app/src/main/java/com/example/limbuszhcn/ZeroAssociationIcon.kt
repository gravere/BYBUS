package com.example.limbuszhcn

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Unmodified avatar from the translation project's official GitHub organization. */
@Composable
internal fun ZeroAssociationIcon(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.zero_association), contentDescription = "都市零协会",
        modifier = modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
}

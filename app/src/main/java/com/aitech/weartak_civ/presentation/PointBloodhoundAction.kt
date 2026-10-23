package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitech.weartak_civ.R

@Composable
fun PointBloodhoundAction(
    isActive: Boolean,
    onBloodhound: () -> Unit,
    onInPosition: () -> Unit
) {
    Button(
        onClick = if (isActive) onInPosition else onBloodhound,
        modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = Color.Black
        )
    ) {
        androidx.wear.compose.material.Text(
            text = stringResource(if (isActive) R.string.npos else R.string.point_bloodhound),
            fontSize = 15.sp,
            overflow = TextOverflow.Ellipsis,
            color = Color.Black,
            fontWeight = FontWeight.SemiBold
        )
    }
}

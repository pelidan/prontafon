package com.prontafon.presentation.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import com.prontafon.R

// Montserrat with variable font support (weight axis: 100-900)
@OptIn(ExperimentalTextApi::class)
val MontserratFontFamily = FontFamily(
    Font(
        resId = R.font.montserrat,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(800)
        )
    ),
    Font(
        resId = R.font.montserrat,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(500)
        )
    )
)

// Inter with variable font support (weight axis: 100-900)
@OptIn(ExperimentalTextApi::class)
val InterFontFamily = FontFamily(
    Font(
        resId = R.font.inter,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(400)
        )
    )
)

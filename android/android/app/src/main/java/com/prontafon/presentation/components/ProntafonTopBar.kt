package com.prontafon.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prontafon.R
import com.prontafon.presentation.theme.Background
import com.prontafon.presentation.theme.MontserratFontFamily
import com.prontafon.presentation.theme.ProntafonTheme

/**
 * Custom top app bar for Prontafon application
 * Displays logo, app name, and optional actions
 *
 * @param title The title text to display (defaults to "Prontafon")
 * @param onNavigateBack Optional callback for back navigation. If provided, shows a back button.
 * @param actions Optional actions to display on the right side
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProntafonTopBar(
    title: String = "Prontafon",
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Logo
                Image(
                    painter = painterResource(id = R.drawable.logo_dark_small),
                    contentDescription = "Prontafon Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(24.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // Title text
                Text(
                    text = title,
                    fontFamily = MontserratFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back",
                        tint = Color.White
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Background,
            titleContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
}

@Preview(showBackground = true)
@Composable
private fun ProntafonTopBarPreview() {
    ProntafonTheme {
        ProntafonTopBar()
    }
}

@Preview(showBackground = true)
@Composable
private fun ProntafonTopBarWithActionsPreview() {
    ProntafonTheme {
        ProntafonTopBar(
            actions = {
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_preferences),
                        contentDescription = "Settings"
                    )
                }
            }
        )
    }
}

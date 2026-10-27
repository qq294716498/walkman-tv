package com.walkman.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.ui.theme.AppColors

/** Generic destructive-action confirm (删除下载 / 清空历史 …). Same look as the exit dialog:
 *  focus starts on 取消 so a stray OK never deletes anything. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "删除",
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text(title, color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = AppColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TvPill(
                    onClick = onCancel,
                    focusRequester = cancelFocus,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                ) {
                    Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                TvPill(
                    onClick = onConfirm,
                    selected = true,
                    accent = AppColors.Danger,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                ) {
                    Text(confirmLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

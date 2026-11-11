package com.example.limbuszhcn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun GooglePaymentPane(state: LauncherUiState, onRefresh: () -> Unit,
    onEnable: () -> Unit, onSettings: () -> Unit) {
    val payment = state.googlePayment
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        DetailActionSection("付款准备") {
            PaymentStatusRow("Google 账号", payment?.hasAccount?.let { if (it) "已登录" else "待登录" })
            DetailActionDivider()
            PaymentStatusRow("应用内购买", payment?.billingEnabled?.let { if (it) "已启用" else "未启用" })
            DetailActionDivider()
            PaymentStatusRow("设备注册", payment?.let {
                if (it.registered) "已注册" else if (it.registrationEnabled) "等待注册" else "未启用"
            })
        }
        state.googlePaymentIssue?.let { Text(it, color = C.Error, style = MaterialTheme.typography.bodyMedium) }
        if (payment != null && (!payment.billingEnabled || !payment.registrationEnabled)) {
            Text("启用容器内的付款服务与 Google 设备注册；账号和支付方式仍由你在结算页确认。",
                color = C.Muted, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onEnable, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("启用付款服务")
            }
        }
        if (payment?.registrationEnabled == true && !payment.registered) {
            Text("设备注册需要联网，完成后点击刷新状态。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        DetailActionSection("账号设置") {
            SheetAction(Glyph.Account, if (payment?.hasAccount == false) "登录 Google 账号" else "管理 Google 账号",
                enabled = !state.busy, navigation = true, onClick = onSettings)
            DetailActionDivider()
            SheetAction(Glyph.Refresh, "刷新状态", enabled = !state.busy, onClick = onRefresh)
        }
    }
}

@Composable
private fun PaymentStatusRow(title: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "待检查", color = C.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

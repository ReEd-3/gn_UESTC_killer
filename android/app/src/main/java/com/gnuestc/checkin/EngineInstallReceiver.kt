package com.gnuestc.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * [修复 2026-10-18] 引擎安装结果回执（PackageInstaller 会话）：
 *  - 第 1 次回调 STATUS_PENDING_USER_ACTION：需要用户确认，把系统给出的确认界面拉起来
 *  - 第 2 次回调为最终结果：成功/失败（含具体失败原因，如签名冲突、降级、存储不足）
 *
 * 背景：旧 ACTION_VIEW 安装方式在部分国产 ROM 上会被文件管理/应用商店接管，
 * 安装被取消或失败时毫无提示，导致"引擎更新点了但一直停在旧版本"无从察觉。
 */
class EngineInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 需要用户确认：拉起系统安装确认界面（PendingIntent 回调自带后台启动豁免）
                LogStore.append(context, "[引擎] 安装会话等待用户确认，拉起系统确认界面")
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // [修复 2026-10-22] 此处是系统要求的"拉起确认界面"，
                    // 但 Android 10+ 后台启动 Activity 会被静默丢弃（MIUI 尤严）→ 不能只为它写日志就算完
                    runCatching { context.startActivity(confirm) }
                        .onFailure {
                            LogStore.append(
                                context,
                                "[引擎] 拉起安装确认界面失败（可能被后台启动限制拦截）：${it.javaClass.simpleName}"
                            )
                        }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                LogStore.append(context, "[引擎] 后台组件安装成功，正在生效")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                // [修复 2026-10-20] 良性失败：核对当前实际安装版本。
                // 实测（ColorOS）会出现“多个安装会话并存”：其中一个被拒（statusCode=-115
                // User rejected permissions / 用户取消二次弹窗），而实际已安装成功，
                // 此前会因此况弹“安装失败”，与事实不符。
                val installed = EngineBridge.installedVersionCode(context)
                val bundled = EngineBridge.bundledVersionCodeOrZero(context)
                if (installed > 0 && bundled > 0 && installed >= bundled) {
                    LogStore.append(
                        context,
                        "[引擎] 安装会话返回 status=$status（$msg），但当前已是 v$installed" +
                            "（目标 v$bundled），按成功处理"
                    )
                    runCatching {
                        Toast.makeText(
                            context,
                            "后台组件已就绪（v$installed）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    LogStore.append(
                        context,
                        "[引擎] 安装失败（status=$status）${if (msg.isNotBlank()) "：$msg" else ""}"
                    )
                    runCatching {
                        Toast.makeText(context, "安装失败，请重试（详情见运行日志）", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

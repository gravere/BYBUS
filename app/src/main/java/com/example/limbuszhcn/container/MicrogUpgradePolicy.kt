package com.example.limbuszhcn.container

internal data class MicrogPackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>
)

/** Only the two pinned microG packages may enter this data-preserving upgrade path. */
internal object MicrogUpgradePolicy {
    val packages = listOf("com.google.android.gms", "com.android.vending")
    const val CERTIFICATE = "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"

    fun needsInstall(target: MicrogPackageIdentity, installed: MicrogPackageIdentity?): Boolean {
        require(target.packageName in packages && target.versionCode > 0 &&
            target.signers == setOf(CERTIFICATE)) { "Google 组件来源校验失败，未更改已有数据" }
        if (installed == null) return true
        require(installed.packageName == target.packageName && installed.signers == target.signers) {
            "已有 Google 组件签名不一致，已保留原组件和账号"
        }
        require(installed.versionCode <= target.versionCode) {
            "已有 Google 组件版本较新，已保留原组件和账号"
        }
        return installed.versionCode < target.versionCode
    }
}

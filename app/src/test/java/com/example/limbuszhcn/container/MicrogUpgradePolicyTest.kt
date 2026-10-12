package com.example.limbuszhcn.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrogUpgradePolicyTest {
    private val target = MicrogPackageIdentity("com.google.android.gms", 252432034,
        setOf(MicrogUpgradePolicy.CERTIFICATE))

    @Test fun installsMissingComponentAndUpgradesOlderSignedComponent() {
        assertTrue(MicrogUpgradePolicy.needsInstall(target, null))
        assertTrue(MicrogUpgradePolicy.needsInstall(target, target.copy(versionCode = 250932030)))
    }

    @Test fun doesNotReinstallCurrentComponentOnEveryLaunch() {
        assertFalse(MicrogUpgradePolicy.needsInstall(target, target))
    }

    @Test fun rejectsDifferentSignerWithoutOfferingDestructiveMigration() {
        assertTrue(runCatching {
            MicrogUpgradePolicy.needsInstall(target, target.copy(signers = setOf("other")))
        }.isFailure)
    }

    @Test fun rejectsDowngradeAndUnexpectedPackage() {
        assertTrue(runCatching {
            MicrogUpgradePolicy.needsInstall(target, target.copy(versionCode = target.versionCode + 1))
        }.isFailure)
        assertTrue(runCatching {
            MicrogUpgradePolicy.needsInstall(target.copy(packageName = "com.ProjectMoon.LimbusCompany"), null)
        }.isFailure)
    }

    @Test fun rejectsUnsignedAndMultipleSignerTargetsBeforeAnyInstall() {
        assertTrue(runCatching {
            MicrogUpgradePolicy.needsInstall(target.copy(signers = emptySet()), null)
        }.isFailure)
        assertTrue(runCatching {
            MicrogUpgradePolicy.needsInstall(target.copy(signers = target.signers + "other"), null)
        }.isFailure)
    }
}

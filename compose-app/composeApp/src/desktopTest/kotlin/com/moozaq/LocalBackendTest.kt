package com.moozaq

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalBackendTest {
    private fun helper(root: File): File {
        val method = Class.forName("com.moozaq.PythonBackendKt").getDeclaredMethod("audioHelper", File::class.java)
        method.isAccessible = true
        return method.invoke(null, root) as File
    }

    @Test fun packagedHelperIsPreferredAndCheckoutIsFallback() {
        val original = System.getProperty("java.home")
        val bundle = File("build/test-local-runtime/Moozaq.app").absoluteFile
        val helper = File(bundle, "Contents/Resources/MoozaqAudioPlayer")
        try {
            helper.parentFile.mkdirs()
            helper.writeText("test only; not a player")
            helper.setExecutable(true)
            System.setProperty("java.home", File(bundle, "Contents/runtime/Contents/Home").absolutePath)
            assertEquals(helper.absolutePath, helper(File("/checkout")).absolutePath)
            System.setProperty("java.home", "/missing/runtime/Contents/Home")
            assertEquals(File("/checkout/compose-app/composeApp/build/macos/MoozaqAudioPlayer").absolutePath,
                helper(File("/checkout")).absolutePath)
        } finally {
            System.setProperty("java.home", original)
        }
    }
}

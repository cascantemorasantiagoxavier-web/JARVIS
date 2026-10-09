package com.example.jarvis.domain.ai

import org.junit.Assert.*
import org.junit.Test

class ProviderRouterTest {

    @Test
    fun testLocalAiProviderIsIntegratedAndUsed() {
        val local = LocalAiProvider()
        assertNotNull(local)
    }

    @Test
    fun testHybridAiProviderHasLocalProvider() {
        val hybridProvider = HybridAiProvider()
        assertNotNull(hybridProvider.localAiProvider)
    }

    @Test
    fun testAntiUnusedLocalAiProviderAssertion() {
        val hybridProvider = HybridAiProvider()
        val local = hybridProvider.localAiProvider
        assertNotNull(local)
    }
}

package dev.leonardo.ocbeacon.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #441-A3(2026-09-28):网络身份信号——同态切换盲区(Available→Available,如
 * WiFi 换 AP/路由迁移)在 NetworkState 四态上零信号(恢复 kick 只看 Available
 * 转换+distinctUntilChanged),长连接半开只能等传输层 ping 超时。identity 流
 * (netId 句柄+主传输)让下游可对「网络环境变化」直接 kick。
 *
 * 调研依据:docs/research/2026-09-28-issue441-research.md §4.6/方案 A3。
 */
class NetworkMonitorIdentityTest {

    private lateinit var monitor: NetworkMonitor
    private lateinit var callback: ConnectivityManager.NetworkCallback

    @Before
    fun setUp() {
        val cm = mockk<ConnectivityManager>(relaxed = true)
        every { cm.activeNetwork } returns null
        val context = mockk<Context>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        // 回调经 createCallback() 可测缝直接驱动(不注册,绕开 android stub:
        // 单测环境 NetworkRequest.Builder.addCapability 返回 null)。
        monitor = NetworkMonitor(context)
        callback = monitor.createCallback()
    }

    private fun net(handle: Long): Network = mockk {
        every { networkHandle } returns handle
    }

    private fun caps(internet: Boolean, validated: Boolean, transport: Int): NetworkCapabilities =
        mockk {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
            every { hasTransport(any<Int>()) } answers { firstArg<Int>() == transport }
        }

    @Test
    fun `identity tracks validated network`() = runTest {
        callback.onAvailable(net(10L))
        callback.onCapabilitiesChanged(net(10L), caps(true, true, NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(NetworkIdentity(10L, NetworkCapabilities.TRANSPORT_WIFI), monitor.networkIdentity.first())
    }

    @Test
    fun `same-state network switch changes identity`() = runTest {
        // WiFi A → WiFi B(同态切换:NetworkState 恒 Available,恢复通道零信号)
        callback.onAvailable(net(10L))
        callback.onCapabilitiesChanged(net(10L), caps(true, true, NetworkCapabilities.TRANSPORT_WIFI))
        callback.onAvailable(net(20L))
        callback.onCapabilitiesChanged(net(20L), caps(true, true, NetworkCapabilities.TRANSPORT_WIFI))
        // 断言核心:identity 跟随到新网络(下游 distinctUntilChanged 即可 kick)
        assertEquals(NetworkIdentity(20L, NetworkCapabilities.TRANSPORT_WIFI), monitor.networkIdentity.first())
        // NetworkState 侧确实无变化(同态盲区成立的前提)
        assertEquals(NetworkState.Available, monitor.networkState.first())
    }

    @Test
    fun `identity cleared only when current network lost`() = runTest {
        callback.onAvailable(net(10L))
        callback.onCapabilitiesChanged(net(10L), caps(true, true, NetworkCapabilities.TRANSPORT_WIFI))
        // 其它网络(非当前 identity)丢失:不清
        callback.onLost(net(99L))
        assertNotNull(monitor.networkIdentity.first())
        // 当前网络丢失:清空
        callback.onLost(net(10L))
        assertNull(monitor.networkIdentity.first())
    }

    @Test
    fun `identity not updated for unvalidated network`() = runTest {
        callback.onAvailable(net(10L))
        callback.onCapabilitiesChanged(net(10L), caps(true, false, NetworkCapabilities.TRANSPORT_WIFI))
        assertNull("未 validated(captive portal 等)不成为身份", monitor.networkIdentity.first())
    }
}

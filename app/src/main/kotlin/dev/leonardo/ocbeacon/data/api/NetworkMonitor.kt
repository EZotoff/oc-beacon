package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络连接状态。
 */
sealed class NetworkState {
    /** 网络已连接且可用。 */
    data object Available : NetworkState()

    /** 网络即将丢失（宽限期）。 */
    data object Losing : NetworkState()

    /** 网络已丢失。 */
    data object Lost : NetworkState()

    /** 完全没有可用网络。 */
    data object Unavailable : NetworkState()

    /** 是否处于已连接状态的便捷判断。 */
    val isOnline: Boolean
        get() = this is Available
}

/**
 * #441-A3(2026-09-28):网络身份——当前 validated 网络的稳定句柄+主传输。
 *
 * 动机:同态网络切换(WiFi 换 AP/路由迁移)在 [NetworkState] 四态上是
 * Available→Available,恢复 kick 通道(debounce+distinctUntilChanged)零信号
 * ——长连接半开只能等传输层 ping 超时(≤~50s),逻辑流死亡(DSH follow 半开)
 * 则完全无兜底。下游观察 [NetworkMonitor.networkIdentity] 的**值变化**即可
 * 对网络环境变化直接 kick 重连。
 */
data class NetworkIdentity(
    /** Network.getNetworkHandle():基于 netId 的稳定句柄(公开 API)。 */
    val handle: Long,
    /** 主传输类型(NetworkCapabilities.TRANSPORT_*)。 */
    val transport: Int,
)

/**
 * 通过 [ConnectivityManager] 监控网络连接状态。
 *
 * 暴露一个可被 ViewModel 和服务观察、以响应网络变化的
 * [StateFlow]<[NetworkState]>。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkMonitor"
    }
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow<NetworkState>(detectInitialState())

    /** 可观察的网络状态。 */
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    /** #441-A3:当前网络身份(null=无 validated 网络);值变化=网络环境变化。 */
    private val _networkIdentity = MutableStateFlow<NetworkIdentity?>(null)

    /** 可观察的网络身份——同态切换(Available→Available)的唯一信号源。 */
    val networkIdentity: StateFlow<NetworkIdentity?> = _networkIdentity.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 开始监控网络变化。在 service/init 期间调用一次。
     * 幂等——多次调用是安全的。
     */
    fun startMonitoring() {
        if (callback != null) return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = createCallback()

        connectivityManager.registerNetworkCallback(networkRequest, cb)
        callback = cb

        // 立即设置初始状态
        _networkState.value = detectInitialState()
    }

    /**
     * 回调工厂(#441-A3 可测缝):事件→状态/身份映射逻辑在此,JVM 单测直接
     * 驱动回调方法——绕开 ConnectivityManager 注册与 NetworkRequest.Builder
     * 的 android stub(单测环境 addCapability 返回 null)。
     */
    internal fun createCallback(): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AppLogger.i(TAG, "Network available")
                _networkState.value = NetworkState.Available
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                _networkState.value = NetworkState.Losing
            }

            override fun onLost(network: Network) {
                AppLogger.w(TAG, "Network lost")
                _networkState.value = NetworkState.Lost
                // #441-A3:仅当前身份网络丢失才清空(多网络并行时其它网络
                // 丢失不代表环境变化)。
                if (_networkIdentity.value?.handle == network.networkHandle) {
                    _networkIdentity.value = null
                }
            }

            override fun onUnavailable() {
                AppLogger.i(TAG, "Network unavailable")
                _networkState.value = NetworkState.Unavailable
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val validated = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                if (hasInternet && validated) {
                    _networkState.value = NetworkState.Available
                    // #441-A3:validated 网络即成为当前身份——同态切换
                    // (新网络 validated)由此产生值变化信号。
                    _networkIdentity.value =
                        NetworkIdentity(network.networkHandle, primaryTransport(networkCapabilities))
                } else if (!validated) {
                    // #133（D2-L41）：失去 VALIDATED（captive portal / 认证墙）——
                    // 网络名义可用但请求会被劫持/失败。原实现只处理 validated 分支，
                    // 失去验证后状态卡在旧值（Available）→ 连接层误判在线。
                    _networkState.value = NetworkState.Unavailable
                }
            }
        }

    /**
     * 停止监控。在 service 销毁时调用。
     */
    fun stopMonitoring() {
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
    }

    private fun detectInitialState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState.Unavailable
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkState.Unavailable
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (hasInternet && validated) NetworkState.Available else NetworkState.Unavailable
    }

    /** #441-A3:主传输判定(WIFI→CELLULAR→ETHERNET→VPN→BLUETOOTH 优先序)。 */
    private fun primaryTransport(caps: NetworkCapabilities): Int = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapabilities.TRANSPORT_ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkCapabilities.TRANSPORT_VPN
        else -> NetworkCapabilities.TRANSPORT_BLUETOOTH
    }
}

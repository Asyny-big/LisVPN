package com.lisvpn.android.vpn.libbox

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.lisvpn.android.core.domain.model.AppRules
import libbox.InterfaceUpdateListener
import libbox.NetworkInterfaceIterator
import libbox.PlatformInterface
import libbox.RoutePrefix
import libbox.RoutePrefixIterator
import libbox.TunOptions
import libbox.WIFIState
import java.util.concurrent.CountDownLatch
import timber.log.Timber

/**
 * Adapter between sing-box (`libbox`) and Android's [VpnService] APIs.
 *
 * Responsibilities:
 *  - Open the TUN interface using [VpnService.Builder] and return the file descriptor.
 *  - Apply per-app split tunneling rules ([AppRules]).
 *  - Protect outgoing libbox sockets from looping back into the tunnel ([VpnService.protect]).
 *  - Resolve UID ↔ package mappings for sing-box's `process_name` / `process_path` rules.
 *
 * Each method is invoked by libbox on its own goroutine — keep them short and exception-safe.
 */
class LisPlatformInterface(
    private val service: VpnService,
    private val appRules: AppRules,
) : PlatformInterface {

    private val packageManager: PackageManager get() = service.packageManager
    private val connectivityManager: ConnectivityManager
        get() = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    // ---- TUN setup -------------------------------------------------------

    override fun openTun(options: TunOptions): Int {
        Timber.i("openTun callback received, dispatching worker")
        val latch = CountDownLatch(1)
        var fd: Int? = null
        var failure: Throwable? = null

        Thread({
            try {
                fd = openTunOnWorker(options)
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }, "LisVPNOpenTun").start()

        try {
            latch.await()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }

        failure?.let { throw it }
        return fd ?: error("VpnService.Builder.establish() returned null")
    }

    private fun openTunOnWorker(options: TunOptions): Int {
        if (VpnService.prepare(service) != null) {
            error("VPN permission is not granted")
        }

        val mtu = options.safeMtu()
        val dnsServer = options.safeDnsServer()
        val inet4Address = options.safeInet4Address()
        val inet4Routes = options.safeInet4Routes()
        val inet6Address = options.safeInet6Address()
        val inet6Routes = options.safeInet6Routes()

        val builder = service.Builder().apply {
            setSession(SESSION_NAME)
            setMtu(mtu)
            Timber.i(
                "openTun config: mode=%s packages=%d mtu=%d dns=%s inet4=%s routes4=%s inet6=%s routes6=%s",
                appRules.mode,
                appRules.packages.size,
                mtu,
                dnsServer,
                inet4Address.joinToString { it.diagnosticLabel() },
                inet4Routes.joinToString { it.diagnosticLabel() },
                inet6Address.joinToString { it.diagnosticLabel() },
                inet6Routes.joinToString { it.diagnosticLabel() },
            )

            addPrefixAddresses(
                inet4Address.mapNotNull { it.toRouteAddress() }
                    .ifEmpty { listOf(RouteAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)) },
            )
            addPrefixAddresses(inet6Address.mapNotNull { it.toRouteAddress() })

            addPrefixRoutes(
                inet4Routes.mapNotNull { it.toRouteAddress() }
                    .ifEmpty { listOf(RouteAddress("0.0.0.0", 0)) },
            )
            addPrefixRoutes(inet6Routes.mapNotNull { it.toRouteAddress() })

            addDnsServer(dnsServer)

            // Per-app split tunneling.
            applyAppRules(this, appRules)

            setBlocking(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMetered(false)
                setUnderlyingNetworks(currentUnderlyingNetworks())
            }
        }

        closeTun()
        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        synchronized(this) {
            vpnInterface = pfd
        }
        Timber.d("openTun: fd established")
        return pfd.fd
    }

    fun closeTun() {
        synchronized(this) {
            runCatching { vpnInterface?.close() }
                .onFailure { Timber.w(it, "closeTun failed") }
            vpnInterface = null
        }
    }

    private fun applyAppRules(builder: VpnService.Builder, rules: AppRules) {
        val ourPackage = service.packageName
        when (rules.mode) {
            AppRules.Mode.Off -> Unit
            AppRules.Mode.AllowList -> {
                rules.packages.forEach { pkg ->
                    if (pkg == ourPackage) return@forEach
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { Timber.w(it, "addAllowedApplication: %s not installed?", pkg) }
                }
            }
            AppRules.Mode.DisallowList -> {
                rules.packages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { Timber.w(it, "addDisallowedApplication: %s not installed?", pkg) }
                }
            }
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!service.protect(fd)) Timber.w("VpnService.protect(fd=%d) returned false", fd)
    }

    // ---- DNS / WiFi / Interface monitor ---------------------------------

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProto: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int = 0 // unused — split tunneling handled at VpnService layer

    override fun packageNameByUid(uid: Int): String =
        runCatching { packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty() }
            .getOrDefault("")

    override fun uidByPackageName(packageName: String): Int =
        runCatching { packageManager.getPackageUid(packageName, 0) }.getOrDefault(0)

    override fun usePlatformDefaultInterfaceMonitor(): Boolean = false
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun usePlatformInterfaceGetter(): Boolean = false
    override fun getInterfaces(): NetworkInterfaceIterator = EmptyNetworkInterfaceIterator

    override fun underNetworkExtension(): Boolean = false
    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState = WIFIState("", "")

    override fun writeLog(message: String) {
        // sing-box logs `WARN inbound/tun: bind forwarder to interface: route ip+net:
        // netlinkrib: permission denied` on every start. On Android the app process never has
        // CAP_NET_ADMIN to read RTNETLINK, the WARN is benign (libbox falls back to the regular
        // route table via JNI) but it pollutes every connection log and looks like a real bug
        // in user reports. Drop it to debug, keep every other libbox log line as-is.
        if (message.isLibboxBenignWarning()) {
            Timber.tag(TAG_LIBBOX_NOISY).v(message)
        } else {
            Timber.tag(TAG_LIBBOX).d(message)
        }
    }

    private fun String.isLibboxBenignWarning(): Boolean =
        contains("netlinkrib: permission denied") ||
            contains("operation not permitted") && contains("netlink")

    private fun TunOptions.safeMtu(): Int =
        runCatching { getMTU().takeIf { it > 0 } ?: TUN_MTU }.getOrDefault(TUN_MTU)

    private fun TunOptions.safeDnsServer(): String =
        runCatching { getDNSServerAddress().trim().takeIf { it.isNotBlank() } ?: TUN_DNS_SERVER }
            .getOrDefault(TUN_DNS_SERVER)

    private fun TunOptions.safeInet4Address(): List<RoutePrefix> =
        runCatching { getInet4Address().toList() }.getOrDefault(emptyList())

    private fun TunOptions.safeInet4Routes(): List<RoutePrefix> =
        runCatching { getInet4RouteAddress().toList() }.getOrDefault(emptyList())

    private fun TunOptions.safeInet6Address(): List<RoutePrefix> =
        runCatching { getInet6Address().toList() }.getOrDefault(emptyList())

    private fun TunOptions.safeInet6Routes(): List<RoutePrefix> =
        runCatching { getInet6RouteAddress().toList() }.getOrDefault(emptyList())

    private fun RoutePrefixIterator?.toList(): List<RoutePrefix> {
        if (this == null) return emptyList()
        val result = mutableListOf<RoutePrefix>()
        while (hasNext()) result += next()
        return result
    }

    private fun VpnService.Builder.addPrefixAddresses(addresses: List<RouteAddress>) {
        addresses.forEach { address ->
            runCatching { addAddress(address.address, address.prefix) }
                .onFailure { Timber.w(it, "addAddress failed: %s/%d", address.address, address.prefix) }
        }
    }

    private fun VpnService.Builder.addPrefixRoutes(routes: List<RouteAddress>) {
        routes.forEach { route ->
            runCatching { addRoute(route.address, route.prefix) }
                .onFailure { Timber.w(it, "addRoute failed: %s/%d", route.address, route.prefix) }
        }
    }

    private fun RoutePrefix.toRouteAddress(): RouteAddress? {
        val value = address.trim().substringBefore('/').trim()
        if (value.isBlank()) return null
        return RouteAddress(value, prefix)
    }

    private fun RoutePrefix.diagnosticLabel(): String =
        "${address.trim().substringBefore('/').ifBlank { "?" }}/$prefix"

    private fun currentUnderlyingNetworks(): Array<Network>? {
        val networks = listOfNotNull(bestUnderlyingNetwork())
        return networks.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun bestUnderlyingNetwork(): Network? {
        val manager = connectivityManager
        val active = manager.activeNetwork
            ?.takeIf { network -> manager.getNetworkCapabilities(network)?.isUsableUnderlying() == true }
        if (active != null) return active
        return manager.allNetworks
            .filter { network -> manager.getNetworkCapabilities(network)?.isUsableUnderlying() == true }
            .maxByOrNull { network -> manager.getNetworkCapabilities(network)?.underlyingPriority() ?: 0 }
    }

    private fun NetworkCapabilities.isUsableUnderlying(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun NetworkCapabilities.underlyingPriority(): Int {
        val validation = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0
        val transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 40
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            else -> 0
        }
        return validation + transport
    }

    private data class RouteAddress(val address: String, val prefix: Int)

    private object EmptyNetworkInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): libbox.NetworkInterface = libbox.NetworkInterface()
    }

    private companion object {
        const val SESSION_NAME = "LisVPN"
        const val TAG_LIBBOX = "libbox"
        const val TAG_LIBBOX_NOISY = "libbox-noise"
        // sing-box previously asked us for a 1280 MTU (IPv6 minimum). On a TCP-over-TLS-over-
        // REALITY transport the inner TCP MSS shrinks to ~1180 bytes once you account for the
        // TLS record + REALITY overhead. Big TLS records from the upstream (Telegram, YouTube)
        // get split into many small segments, killing throughput and triggering retransmits
        // that look indistinguishable from packet loss. 1420 is the conservative ceiling that
        // still leaves slack for the TCP/TLS framing on the underlying carrier (most cellular
        // and Wi-Fi paths support 1500 MTU end-to-end).
        const val TUN_MTU = 1420
        const val TUN_IPV4_ADDRESS = "172.19.0.1"
        const val TUN_IPV4_PREFIX = 30
        const val TUN_DNS_SERVER = "172.19.0.2"
    }
}

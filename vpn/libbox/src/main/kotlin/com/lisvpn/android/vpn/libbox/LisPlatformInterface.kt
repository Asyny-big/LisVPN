package com.lisvpn.android.vpn.libbox

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.lisvpn.android.core.domain.model.AppRules
import libbox.InterfaceUpdateListener
import libbox.NetworkInterfaceIterator
import libbox.PlatformInterface
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
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    // ---- TUN setup -------------------------------------------------------

    override fun openTun(@Suppress("UNUSED_PARAMETER") options: TunOptions): Int {
        Timber.i("openTun callback received, dispatching worker")
        val latch = CountDownLatch(1)
        var fd: Int? = null
        var failure: Throwable? = null

        Thread({
            try {
                fd = openTunOnWorker()
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

    private fun openTunOnWorker(): Int {
        if (VpnService.prepare(service) != null) {
            error("VPN permission is not granted")
        }

        val builder = service.Builder().apply {
            setSession(SESSION_NAME)
            setMtu(TUN_MTU)
            Timber.i("openTun config: mode=%s packages=%d", appRules.mode, appRules.packages.size)

            addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)

            addRoute("0.0.0.0", 0)

            addDnsServer(TUN_DNS_SERVER)

            // Per-app split tunneling.
            applyAppRules(this, appRules)

            setBlocking(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setMetered(false)
                setUnderlyingNetworks(null)
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
                // Always exclude self to prevent loops.
                runCatching { builder.addDisallowedApplication(ourPackage) }
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
        Timber.tag(TAG_LIBBOX).d(message)
    }

    private object EmptyNetworkInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): libbox.NetworkInterface = libbox.NetworkInterface()
    }

    private companion object {
        const val SESSION_NAME = "LisVPN"
        const val TAG_LIBBOX = "libbox"
        const val TUN_MTU = 1280
        const val TUN_IPV4_ADDRESS = "172.19.0.1"
        const val TUN_IPV4_PREFIX = 30
        const val TUN_DNS_SERVER = "172.19.0.2"
    }
}

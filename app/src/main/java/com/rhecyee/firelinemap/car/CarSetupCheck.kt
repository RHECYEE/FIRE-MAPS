package com.rhecyee.firelinemap.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import org.xmlpull.v1.XmlPullParser

/**
 * Why the app is, or is not, in the car.
 *
 * An app missing from the Android Auto launcher is reported by nothing: no
 * error, no log line, no entry anywhere. The declarations either satisfy the
 * host or the app quietly does not exist as far as the car is concerned. That
 * makes it exactly the wrong problem to debug from a vehicle, so the checks a
 * developer would run over a cable are run by the app on itself instead.
 *
 * Gathering the facts and reading them are kept apart on purpose: the reading
 * is where the judgement is, and it is testable.
 */

enum class CheckState { PASS, FAIL, WARN, INFO }

data class CheckLine(val label: String, val detail: String, val state: CheckState)

/** Everything the app can find out about its own place in the car. */
data class CarSetupFacts(
    val serviceDeclared: Boolean,
    val serviceExported: Boolean,
    val serviceEnabled: Boolean,
    /** Resolvable through the action alone. */
    val serviceResolvable: Boolean,
    /** Resolvable through the action together with the navigation category. */
    val navigationCategoryResolvable: Boolean,
    val descriptorDeclared: Boolean,
    val descriptorDeclaresTemplate: Boolean,
    val minCarApiLevel: Int?,
    val navigationTemplatesPermission: Boolean,
    val accessSurfacePermission: Boolean,
    val applicationIcon: Boolean,
    val androidAutoInstalled: Boolean,
    val androidAutoVersion: String?,
    val androidAutoEnabled: Boolean,
    /** Other template apps the system will admit to; empty is itself a finding. */
    val otherTemplateApps: List<String>,
    val debuggable: Boolean,
    /** Android user the app is installed for. Anything but 0 the car cannot see. */
    val userId: Int,
    val connectionType: Int?,
    /** Whether the host has ever bound this app, and what happened when it did. */
    val hostEverBound: Boolean,
    val linkEvents: List<String>,
    val installedBy: String?
)

private const val CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
private const val NAVIGATION_CATEGORY = "androidx.car.app.category.NAVIGATION"
private const val DESCRIPTOR_META = "com.google.android.gms.car.application"
private const val MIN_API_META = "androidx.car.app.minCarApiLevel"
private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

fun gatherCarSetupFacts(context: Context, connectionType: Int? = null): CarSetupFacts {
    val packages = context.packageManager
    val component = ComponentName(context, FirelineCarAppService::class.java)

    val service = runCatching { packages.getServiceInfo(component, 0) }.getOrNull()
    val enabledSetting = runCatching {
        packages.getComponentEnabledSetting(component)
    }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    val enabled = when (enabledSetting) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
        else -> service?.isEnabled ?: false
    }

    fun resolve(withCategory: Boolean): List<String> {
        val intent = Intent(CAR_APP_SERVICE_ACTION).apply {
            if (withCategory) addCategory(NAVIGATION_CATEGORY)
        }
        return runCatching { packages.queryIntentServices(intent, 0) }
            .getOrDefault(emptyList())
            .mapNotNull { it.serviceInfo?.packageName }
    }

    val byAction = resolve(withCategory = false)
    val byCategory = resolve(withCategory = true)

    val application = runCatching {
        packages.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    }.getOrNull()
    val metadata = application?.metaData
    val descriptor = metadata?.getInt(DESCRIPTOR_META, 0) ?: 0
    val minLevel = if (metadata?.containsKey(MIN_API_META) == true) {
        metadata.getInt(MIN_API_META, -1).takeIf { it >= 0 }
    } else {
        null
    }

    val auto = runCatching { packages.getPackageInfo(ANDROID_AUTO, 0) }.getOrNull()

    return CarSetupFacts(
        serviceDeclared = service != null,
        serviceExported = service?.exported == true,
        serviceEnabled = enabled,
        serviceResolvable = byAction.contains(context.packageName),
        navigationCategoryResolvable = byCategory.contains(context.packageName),
        descriptorDeclared = descriptor != 0,
        descriptorDeclaresTemplate = descriptor != 0 && declaresTemplate(context, descriptor),
        minCarApiLevel = minLevel,
        navigationTemplatesPermission = ContextCompat.checkSelfPermission(
            context, "androidx.car.app.NAVIGATION_TEMPLATES"
        ) == PackageManager.PERMISSION_GRANTED,
        accessSurfacePermission = ContextCompat.checkSelfPermission(
            context, "androidx.car.app.ACCESS_SURFACE"
        ) == PackageManager.PERMISSION_GRANTED,
        applicationIcon = (application?.icon ?: 0) != 0,
        androidAutoInstalled = auto != null,
        androidAutoVersion = auto?.versionName,
        androidAutoEnabled = auto?.applicationInfo?.enabled ?: false,
        otherTemplateApps = byAction.filter { it != context.packageName }.distinct().sorted(),
        debuggable = (application?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        userId = runCatching { Process.myUserHandle().hashCode() }.getOrDefault(0),
        connectionType = connectionType,
        hostEverBound = CarLinkLog.everReached(context),
        linkEvents = CarLinkLog.events(context),
        installedBy = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                packages.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packages.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
    )
}

/** Whether the automotive descriptor actually declares a template app. */
private fun declaresTemplate(context: Context, resource: Int): Boolean = runCatching {
    context.resources.getXml(resource).use { parser ->
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "uses") {
                for (index in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(index) == "name" &&
                        parser.getAttributeValue(index) == "template"
                    ) {
                        return@runCatching true
                    }
                }
            }
            event = parser.next()
        }
        false
    }
}.getOrDefault(false)

private inline fun <T> android.content.res.XmlResourceParser.use(block: (android.content.res.XmlResourceParser) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

/**
 * Reads the facts into something a person can act on.
 *
 * Ordered so the first FAIL is the thing to go and do. Everything the app can
 * fix about itself comes first; the settings only a person can reach come after,
 * because saying "check your phone settings" while the manifest is wrong sends
 * someone off to fiddle with a car for an hour.
 */
fun interpret(facts: CarSetupFacts): List<CheckLine> {
    val lines = mutableListOf<CheckLine>()

    lines += CheckLine(
        "Car app service",
        when {
            !facts.serviceDeclared -> "Not declared — this build cannot appear in any car"
            !facts.serviceExported -> "Declared but not exported — the host cannot bind it"
            !facts.serviceEnabled -> "Declared but disabled on this device"
            else -> "Declared, exported and enabled"
        },
        if (facts.serviceDeclared && facts.serviceExported && facts.serviceEnabled) {
            CheckState.PASS
        } else {
            CheckState.FAIL
        }
    )

    lines += CheckLine(
        "Discoverable by the host",
        when {
            !facts.serviceResolvable -> "The system does not resolve the CarAppService action"
            !facts.navigationCategoryResolvable ->
                "Resolves, but not under the navigation category"
            else -> "Resolves under the navigation category"
        },
        if (facts.navigationCategoryResolvable) CheckState.PASS else CheckState.FAIL
    )

    lines += CheckLine(
        "Template descriptor",
        when {
            !facts.descriptorDeclared -> "No automotive_app_desc metadata"
            !facts.descriptorDeclaresTemplate -> "Descriptor found but does not declare template"
            else -> "Declares a template app"
        },
        if (facts.descriptorDeclaresTemplate) CheckState.PASS else CheckState.FAIL
    )

    lines += CheckLine(
        "Minimum car API level",
        facts.minCarApiLevel?.let { "Declared as $it" } ?: "Not declared",
        if (facts.minCarApiLevel != null) CheckState.PASS else CheckState.FAIL
    )

    lines += CheckLine(
        "Car permissions",
        listOfNotNull(
            "NAVIGATION_TEMPLATES".takeIf { !facts.navigationTemplatesPermission },
            "ACCESS_SURFACE".takeIf { !facts.accessSurfacePermission }
        ).let { missing ->
            if (missing.isEmpty()) "Both held" else "Missing ${missing.joinToString(", ")}"
        },
        if (facts.navigationTemplatesPermission && facts.accessSurfacePermission) {
            CheckState.PASS
        } else {
            CheckState.FAIL
        }
    )

    lines += CheckLine(
        "App icon",
        if (facts.applicationIcon) "Set" else "Missing — the car shows a default",
        if (facts.applicationIcon) CheckState.PASS else CheckState.FAIL
    )

    lines += CheckLine(
        "Android Auto",
        when {
            !facts.androidAutoInstalled -> "Not installed on this phone"
            !facts.androidAutoEnabled -> "Installed but disabled"
            else -> "Installed, version ${facts.androidAutoVersion ?: "unknown"}"
        },
        when {
            !facts.androidAutoInstalled -> CheckState.FAIL
            !facts.androidAutoEnabled -> CheckState.FAIL
            else -> CheckState.PASS
        }
    )

    // A profile other than the primary one is invisible to the car however
    // correct everything above is, and nothing says so anywhere.
    lines += CheckLine(
        "Android user",
        if (facts.userId == 0) {
            "Primary user"
        } else {
            "Secondary profile (user ${facts.userId}) — Android Auto only lists apps " +
                "installed for the primary user. Reinstall outside the work profile or " +
                "secure folder."
        },
        if (facts.userId == 0) CheckState.PASS else CheckState.FAIL
    )

    lines += CheckLine(
        "Host connection",
        when (facts.connectionType) {
            null -> "Unknown"
            CONNECTION_NOT_CONNECTED -> "Not connected to a car right now"
            CONNECTION_NATIVE -> "Running on a car head unit"
            CONNECTION_PROJECTION -> "Projecting to Android Auto now"
            else -> "Reported as ${facts.connectionType}"
        },
        CheckState.INFO
    )

    lines += CheckLine(
        "Other template apps seen",
        if (facts.otherTemplateApps.isEmpty()) {
            "None"
        } else {
            facts.otherTemplateApps.joinToString(", ")
        },
        CheckState.INFO
    )

    lines += CheckLine(
        "Has the car ever opened this app",
        if (facts.hostEverBound) {
            facts.linkEvents.take(4).joinToString("\n")
        } else {
            "Never. The host has not bound this app once, so it is not being " +
                "offered in the car at all rather than failing after it is opened."
        },
        if (facts.hostEverBound) CheckState.PASS else CheckState.WARN
    )

    lines += CheckLine(
        "Installed by",
        facts.installedBy ?: "Sideloaded (no installing app recorded)",
        CheckState.INFO
    )

    lines += CheckLine(
        "Build",
        if (facts.debuggable) {
            "Debug — accepts any host, and needs Unknown sources in Android Auto"
        } else {
            "Release — only Google's car hosts may bind it"
        },
        CheckState.INFO
    )

    lines += CheckLine("Verdict", verdict(facts), verdictState(facts))
    return lines
}

private fun appSideGood(facts: CarSetupFacts): Boolean =
    facts.serviceDeclared && facts.serviceExported && facts.serviceEnabled &&
        facts.navigationCategoryResolvable && facts.descriptorDeclaresTemplate &&
        facts.minCarApiLevel != null && facts.navigationTemplatesPermission &&
        facts.accessSurfacePermission && facts.applicationIcon

private fun verdictState(facts: CarSetupFacts): CheckState = when {
    !appSideGood(facts) -> CheckState.FAIL
    !facts.androidAutoInstalled || !facts.androidAutoEnabled -> CheckState.FAIL
    facts.userId != 0 -> CheckState.FAIL
    else -> CheckState.WARN
}

private fun verdict(facts: CarSetupFacts): String = when {
    !appSideGood(facts) ->
        "This build is not set up correctly. Fix the failures above; nothing on the " +
            "phone will make it appear until they pass."
    !facts.androidAutoInstalled ->
        "Android Auto is not installed on this phone, so there is nothing to appear in."
    !facts.androidAutoEnabled ->
        "Android Auto is disabled on this phone. Re-enable it in Settings, Apps."
    facts.userId != 0 ->
        "The app is installed for a secondary profile, which Android Auto never lists. " +
            "Reinstall it for the primary user."
    facts.hostEverBound ->
        "Everything this app controls is correct and the car has opened this app " +
            "before, so discovery works. If it is missing now, it is the launcher " +
            "list rather than the app: check Android Auto, Customize launcher, and " +
            "make sure Fireline Map is switched on there."
    else ->
        "Everything this app controls is correct and the car has never once bound " +
            "this app, so it is not being offered in the launcher at all. That is " +
            "Android Auto's own list, not the build. With Unknown sources already on, " +
            "the remaining step is Android Auto, Customize launcher: sideloaded apps " +
            "are listed there switched off, and stay invisible in the car until they " +
            "are switched on. Force stop Android Auto afterwards so it rescans, then " +
            "reconnect."
}

/** The report as text, for pasting into a message. */
fun List<CheckLine>.asReport(header: String): String = buildString {
    appendLine(header)
    appendLine()
    this@asReport.forEach { line ->
        val mark = when (line.state) {
            CheckState.PASS -> "PASS"
            CheckState.FAIL -> "FAIL"
            CheckState.WARN -> "NOTE"
            CheckState.INFO -> "INFO"
        }
        appendLine("[$mark] ${line.label}: ${line.detail}")
    }
}

const val CONNECTION_NOT_CONNECTED = 0
const val CONNECTION_NATIVE = 1
const val CONNECTION_PROJECTION = 2

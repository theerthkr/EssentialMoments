package com.theerthkr.essentialmoments.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Three-state photo access.
 *
 * Android 14 introduced selected-photo access, so "granted" is no longer a
 * boolean. [PARTIAL] means the app can read only the photos the user picked
 * from the system selection dialog.
 */
enum class MediaAccess {
    FULL,
    PARTIAL,
    NONE;

    val canReadPhotos: Boolean
        get() = this != NONE
}

/**
 * Single source of truth for runtime permission checks.
 *
 * Permission state is deliberately never persisted: users can change access
 * from app info, permission auto-reset, or the system selection dialog while
 * the app is in the background. Call these functions again on every resume.
 */
object AppPermissions {

    fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES
        )

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun mediaAccess(context: Context): MediaAccess = classifyMediaAccess(
        sdkInt = Build.VERSION.SDK_INT,
        hasReadExternalStorage = context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE),
        hasReadMediaImages = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES),
        hasVisualUserSelected = context.isGranted(
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
    )

    /**
     * Pure classification used by [mediaAccess] and unit tests.
     */
    fun classifyMediaAccess(
        sdkInt: Int,
        hasReadExternalStorage: Boolean,
        hasReadMediaImages: Boolean,
        hasVisualUserSelected: Boolean
    ): MediaAccess = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> when {
            hasReadMediaImages -> MediaAccess.FULL
            hasVisualUserSelected -> MediaAccess.PARTIAL
            else -> MediaAccess.NONE
        }

        sdkInt >= Build.VERSION_CODES.TIRAMISU -> {
            if (hasReadMediaImages) MediaAccess.FULL else MediaAccess.NONE
        }

        else -> {
            if (hasReadExternalStorage) MediaAccess.FULL else MediaAccess.NONE
        }
    }

    fun canRequestNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasNotificationPermission(context: Context): Boolean =
        !canRequestNotificationPermission() ||
            context.isGranted(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * True when the app is allowed to post notifications. On Android 13+ this
     * includes the runtime permission; on older versions it reflects the
     * user-wide/channel switch in system settings.
     */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun notificationAccess(context: Context): Boolean =
        hasNotificationPermission(context) && notificationsEnabled(context)

    /**
     * True when Android will still show a photo-permission dialog after a
     * denial. Once this is false and access is still [MediaAccess.NONE], the
     * system has stopped showing the dialog, so requesting again would silently
     * do nothing and the user has to enable access from app settings.
     */
    fun canRequestMediaPermission(activity: Activity): Boolean =
        mediaPermissions().any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }

    /**
     * Called after a media-permission request finishes without a grant.
     *
     * If Android has permanently blocked the request, open app settings so the
     * "Choose Photos" button still leads somewhere instead of appearing dead.
     * Returns true when settings were opened.
     */
    fun openSettingsIfMediaRequestBlocked(activity: Activity): Boolean {
        if (canRequestMediaPermission(activity)) return false
        openAppSettings(activity)
        return true
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotificationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

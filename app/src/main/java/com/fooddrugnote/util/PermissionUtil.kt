package com.fooddrugnote.util

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 权限申请工具类
 * 封装相机权限的检测与申请
 *
 * 说明：相册选图使用 ActivityResultContracts.GetContent()（系统文件选择器），
 * 其返回的 URI 自带临时读取授权，任何 Android 版本都【不需要】READ_EXTERNAL_STORAGE 权限，
 * 因此本工具不再申请存储权限。
 *
 * 使用方式：
 * 1. 在 Activity onCreate 中调用 PermissionUtil.init(this)
 * 2. 需要拍照时调用 requestCameraPermission { 已授权回调 }
 */
object PermissionUtil {

    private lateinit var activity: FragmentActivity
    private var cameraCallback: (() -> Unit)? = null

    private lateinit var cameraLauncher: ActivityResultLauncher<String>

    /**
     * 初始化，必须在 Activity onCreate 中调用
     */
    fun init(activity: FragmentActivity) {
        this.activity = activity

        // 相机权限申请器
        cameraLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val cb = cameraCallback
            cameraCallback = null
            if (granted) {
                cb?.invoke()
            } else {
                Toast.makeText(
                    activity,
                    "需要相机权限才能拍照，请在设置中开启",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 检查并申请相机权限
     */
    fun requestCameraPermission(onGranted: () -> Unit) {
        cameraCallback = onGranted
        if (ContextCompat.checkSelfPermission(
                activity, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            cameraCallback = null
            onGranted()
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * 申请从相册选择图片的权限（无需任何运行时权限）。
     * GetContent() 通过系统选择器返回带临时授权的 URI，所有 Android 版本直接放行。
     */
    fun requestStoragePermission(onGranted: () -> Unit) {
        onGranted()
    }
}

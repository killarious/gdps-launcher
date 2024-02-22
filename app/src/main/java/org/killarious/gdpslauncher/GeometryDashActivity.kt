package org.killarious.gdpslauncher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.customRobTop.BaseRobTopActivity
import com.customRobTop.JniToCpp
import org.killarious.gdpslauncher.utils.Constants
import org.killarious.gdpslauncher.utils.ConstrainedFrameLayout
import org.killarious.gdpslauncher.utils.DownloadUtils
import org.killarious.gdpslauncher.utils.GeodeUtils
import org.killarious.gdpslauncher.utils.LaunchUtils
import org.killarious.gdpslauncher.utils.PreferenceUtils
import org.cocos2dx.lib.Cocos2dxEditText
import org.cocos2dx.lib.Cocos2dxGLSurfaceView
import org.cocos2dx.lib.Cocos2dxHelper
import org.cocos2dx.lib.Cocos2dxRenderer
import org.fmod.FMOD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class DisplayMode {
    DEFAULT, LIMITED, FULLSCREEN;

    companion object {
        fun fromInt(i: Int) = when (i) {
            1 -> LIMITED
            2 -> FULLSCREEN
            else -> DEFAULT
        }
    }
}

class GeometryDashActivity : AppCompatActivity(), Cocos2dxHelper.Cocos2dxHelperListener, GeodeUtils.CapabilityListener {
    private var mGLSurfaceView: Cocos2dxGLSurfaceView? = null
    private val sTag = GeometryDashActivity::class.simpleName
    private var mIsRunning = false
    private var mIsOnPause = false
    private var mHasWindowFocus = false
    private var mReceiver: BroadcastReceiver? = null

    private var displayMode = DisplayMode.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        setupUIState()

        super.onCreate(savedInstanceState)

        // return back to main if Geometry Dash isn't found
        if (!LaunchUtils.isGeometryDashInstalled(packageManager)) {
            returnToMain()
            return
        }

        try {
            tryLoadGame()
        } catch (e: UnsatisfiedLinkError) {
            Log.e("GeodeLauncher", "Library linkage failure", e)

            // generates helpful information for use in debugging library load failures
            val gdPackageInfo = packageManager.getPackageInfo(Constants.PACKAGE_NAME, 0)
            val abiMismatch = LaunchUtils.detectAbiMismatch(this, gdPackageInfo, e)

            val is64bit = LaunchUtils.is64bit
            val errorMessage = when {
                abiMismatch && is64bit -> LaunchUtils.LauncherError.LINKER_NEEDS_32BIT
                abiMismatch -> LaunchUtils.LauncherError.LINKER_NEEDS_64BIT
                else -> LaunchUtils.LauncherError.LINKER_FAILED
            }

            returnToMain(errorMessage, e.message, e.stackTraceToString())

            return
        } catch (e: Exception) {
            Log.e("GeodeLauncher", "Uncaught error during game load", e)

            returnToMain(
                LaunchUtils.LauncherError.GENERIC,
                e.message ?: "Unknown Exception",
                e.stackTraceToString()
            )

            return
        }
    }

    private fun returnToMain(
        error: LaunchUtils.LauncherError? = null,
        returnMessage: String? = null,
        returnExtendedMessage: String? = null
    ) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK

            if (error != null && !returnMessage.isNullOrEmpty()) {
                putExtra(LaunchUtils.LAUNCHER_KEY_RETURN_ERROR, error)
                putExtra(LaunchUtils.LAUNCHER_KEY_RETURN_MESSAGE, returnMessage)
                putExtra(LaunchUtils.LAUNCHER_KEY_RETURN_EXTENDED_MESSAGE, returnExtendedMessage)
            }
        }

        startActivity(launchIntent)
    }

    private fun tryLoadGame() {
        val gdPackageInfo = packageManager.getPackageInfo(Constants.PACKAGE_NAME, 0)

        setupRedirection(gdPackageInfo)

        Cocos2dxHelper.init(this, this)

        GeodeUtils.setContext(this)
        GeodeUtils.setCapabilityListener(this)

        tryLoadLibrary(gdPackageInfo, Constants.FMOD_LIB_NAME)
        tryLoadLibrary(gdPackageInfo, Constants.COCOS_LIB_NAME)

        if (getLoadTesting()) {
            loadTestingLibraries()
        }

        setContentView(createView())

        setupPostLibraryLoad(gdPackageInfo)

        try {
            loadGeodeLibrary()
        } catch (e: UnsatisfiedLinkError) {
            handleGeodeException(e)
        } catch (e: Exception) {
            handleGeodeException(e)
        }
    }

    private fun handleGeodeException(e: Throwable) {
        e.printStackTrace()

        val ignoreFailure = PreferenceUtils.get(this)
            .getBoolean(PreferenceUtils.Key.IGNORE_LOAD_FAILURE)

        if (ignoreFailure) {
            Log.w("GeodeLauncher", "could not load Geode object!")
        } else {
            throw e
        }
    }

    private fun setupRedirection(packageInfo: PackageInfo) {
        try {
            LaunchUtils.addAssetsFromPackage(assets, packageInfo)
        } catch (e: NoSuchMethodException) {
            Log.e("GeodeLauncher", "Failed to add asset redirection", e)
        }

        try {
            // fixes bugs specific to the new app directory, such as package name
            val saveDir = LaunchUtils.getSaveDirectory(this)
            saveDir.mkdir()

            LauncherFix.loadLibrary()
            LauncherFix.setOriginalDataPath(Constants.GJ_DATA_DIR)
            LauncherFix.setDataPath(saveDir.path)
        } catch (e: UnsatisfiedLinkError) {
            Log.e("GeodeLauncher", "Failed to load LauncherFix", e)
        }
    }

    private fun setupPostLibraryLoad(packageInfo: PackageInfo) {
        FMOD.init(this)

        // call native functions after native libraries init
        JniToCpp.setupHSSAssets(
            packageInfo.applicationInfo.sourceDir,
            Environment.getExternalStorageDirectory().absolutePath
        )
        Cocos2dxHelper.nativeSetApkPath(packageInfo.applicationInfo.sourceDir)

        BaseRobTopActivity.setCurrentActivity(this)
        registerReceiver()
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun tryLoadLibrary(packageInfo: PackageInfo, libraryName: String) {
        val nativeDir = getNativeLibraryDirectory(packageInfo.applicationInfo)
        val libraryPath = if (nativeDir.endsWith('/')) "${nativeDir}lib$libraryName.so" else "$nativeDir/lib$libraryName.so"

        try {
            System.load(libraryPath)
        } catch (ule: UnsatisfiedLinkError) {
            // some devices (samsung a series, cough) have an overly restrictive application classloader
            // this is a workaround for that. hopefully
            if (ule.message?.contains("not accessible for the namespace") != true) {
                throw ule
            }

            println("Using copy for library $libraryName")
            loadLibraryCopy(libraryName, libraryPath)
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadLibraryCopy(libraryName: String, libraryPath: String) {
        if (libraryPath.contains("!/")) {
            // library in apk can't be loaded directly
            loadLibraryFromAssetsCopy(libraryName)
            return
        }

        val library = File(libraryPath)
        val libraryCopy = File(cacheDir, "lib$libraryName.so")

        libraryCopy.outputStream().use { libraryOutput ->
            library.inputStream().use { inputStream ->
                DownloadUtils.copyFile(inputStream, libraryOutput)
            }
        }

        System.load(libraryCopy.path)

        return
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadLibraryFromAssetsCopy(libraryName: String) {
        // loads a library loaded in assets
        // this copies the library to a non-compressed directory

        val arch = LaunchUtils.applicationArchitecture
        val libraryFd = try {
            assets.openNonAssetFd("lib/$arch/lib$libraryName.so")
        } catch (_: Exception) {
            throw UnsatisfiedLinkError("Could not find library lib$libraryName.so for abi $arch")
        }

        // copy the library to a path we can access
        // there doesn't seem to be a way to load a library from a file descriptor
        val libraryCopy = File(cacheDir, "lib$libraryName.so")

        libraryCopy.outputStream().use { libraryOutput ->
            libraryFd.createInputStream().use { inputStream ->
                DownloadUtils.copyFile(inputStream, libraryOutput)
            }
        }

        System.load(libraryCopy.path)

        return
    }

    private fun getNativeLibraryDirectory(applicationInfo: ApplicationInfo): String {
        // native libraries have been extracted, so the path is same as usual
        if (applicationInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS == ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) {
            return applicationInfo.nativeLibraryDir
        }

        // might be split apks, select the best path for the library
        val sourceDirs = applicationInfo.splitSourceDirs
        val architecture = LaunchUtils.applicationArchitecture

        if (!sourceDirs.isNullOrEmpty()) {
            val configAbi = architecture.replace("-", "_")
            val abiDir = sourceDirs.find { it.contains(configAbi) }

            if (abiDir != null) {
                return "$abiDir!/lib/$architecture/"
            }
        }

        // this configuration should never happen!
        // non split apk, but libraries are stored in apk
        val sourceDir = applicationInfo.sourceDir
        return "$sourceDir!/lib/$architecture/"
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadGeodeLibrary() {
        // Load Geode if exists
        // bundling the object with the application allows for nicer backtraces
        try {
            // put libgeode.so in jniLibs/armeabi-v7a to get this
            System.loadLibrary("geode")
            return
        } catch (e: UnsatisfiedLinkError) {
            // but users may prefer it stored with data
            val geodeFilename = LaunchUtils.geodeFilename
            val geodePath = File(filesDir.path, "launcher/$geodeFilename")
            if (geodePath.exists()) {
                System.load(geodePath.path)
                return
            }

            // you know zmx i have 0 clue what this does so im
            // just gonna like copy the binary from external
            // also i get 20 million permission denied errors
            val externalGeodePath = LaunchUtils.getInstalledGeodePath(this)!!

            val copiedPath = File(filesDir.path, "copied")
            if (copiedPath.exists()) {
                copiedPath.deleteRecursively()
            }
            copiedPath.mkdir()

            val copiedGeodePath = File(copiedPath.path, "Geode.so")

            if (externalGeodePath.exists()) {
                DownloadUtils.copyFile(
                    FileInputStream(externalGeodePath),
                    FileOutputStream(copiedGeodePath)
                )

                if (copiedGeodePath.exists()) {
                    println("Loading Geode from ${externalGeodePath.name}")
                    System.load(copiedGeodePath.path)
                    return
                }
            }

            throw e
        }
    }

    private fun createView(): FrameLayout {
        val frameLayoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val frameLayout = ConstrainedFrameLayout(this)
        frameLayout.layoutParams = frameLayoutParams

        if (displayMode == DisplayMode.LIMITED) {
            // despite not being perfectly 16:9, this is what Android calls "16:9"
            frameLayout.aspectRatio = 1.86f
        }

        val editTextLayoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val editText = Cocos2dxEditText(this)
        editText.layoutParams = editTextLayoutParams
        frameLayout.addView(editText)

        val glSurfaceView = Cocos2dxGLSurfaceView(this)

        this.mGLSurfaceView = glSurfaceView
        frameLayout.addView(this.mGLSurfaceView)

        glSurfaceView.setEGLConfigChooser(5, 6, 5, 0, 16, 8)

        if (isAndroidEmulator()) {
            glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        }

        glSurfaceView.initView()
        glSurfaceView.setCocos2dxRenderer(Cocos2dxRenderer())

        editText.inputType = 145
        glSurfaceView.cocos2dxEditText = editText

        return frameLayout
    }

    private fun setupUIState() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

        displayMode = DisplayMode.fromInt(
            PreferenceUtils.get(this).getInt(PreferenceUtils.Key.DISPLAY_MODE)
        )

        if (displayMode == DisplayMode.FULLSCREEN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        mHasWindowFocus = hasWindowFocus
        if (hasWindowFocus && !mIsOnPause) {
            resumeGame()
        }

        hideSystemUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceivers()
        FMOD.close()
    }

    private fun resumeGame() {
        mIsRunning = true
//        Cocos2dxHelper.onResume()
        mGLSurfaceView?.onResume()
    }

    private fun pauseGame() {
        mIsRunning = false
//        Cocos2dxHelper.onPause()
        mGLSurfaceView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        mIsOnPause = false
        BaseRobTopActivity.isPaused = false
        if (mHasWindowFocus && !this.mIsRunning) {
            resumeGame()
        }
    }

    override fun onRestart() {
        super.onRestart()
        mIsOnPause = false
        BaseRobTopActivity.isPaused = false
        if (!this.mIsRunning) {
            resumeGame()
        }
    }

    override fun onPause() {
        super.onPause()
        mIsOnPause = true
        BaseRobTopActivity.isPaused = true
        if (mIsRunning) {
            pauseGame()
        }
    }

    private fun registerReceiver() {
        unregisterReceivers()
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            mReceiver = BaseRobTopActivity.ReceiverScreen()
            registerReceiver(mReceiver, filter)
        } catch (_: Exception) {
        }
    }

    private fun unregisterReceivers() {
        if (mReceiver != null) {
            unregisterReceiver(mReceiver)
            mReceiver = null
        }
    }

    override fun runOnGLThread(runnable: Runnable) {
        mGLSurfaceView?.queueEvent(runnable)
    }

    override fun showDialog(title: String, message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                // the button shouldn't do anything but close
                .setPositiveButton(R.string.message_box_accept) { _, _ -> }
                .show()
        }
    }

    override fun showEditTextDialog(
        title: String,
        message: String,
        inputMode: Int,
        inputFlag: Int,
        returnType: Int,
        maxLength: Int
    ) {
        TODO("Not yet implemented")
    }

    private fun isAndroidEmulator(): Boolean {
        Log.d(sTag, "model=" + Build.MODEL)
        val product = Build.PRODUCT
        Log.d(sTag, "product=$product")
        var isEmulator = false
        if (product != null) {
            isEmulator = product == "sdk" || product.contains("_sdk") || product.contains("sdk_")
        }
        Log.d(sTag, "isEmulator=$isEmulator")
        return isEmulator
    }

    private fun getLoadTesting(): Boolean {
        val preferences = PreferenceUtils.get(this)
        return preferences.getBoolean(PreferenceUtils.Key.LOAD_TESTING)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadTestingLibraries() {
        // clear data dir
        val testDirPath = File(filesDir.path + File.separator + "testlib" + File.separator)
        if (testDirPath.exists()) {
            testDirPath.deleteRecursively()
        }
        testDirPath.mkdir()

        val dir = LaunchUtils.getBaseDirectory(this)
        val testingPath = File(dir, "test")

        testingPath.walk().forEach {
            if (it.isFile) {
                // welcome to the world of Android classloader permissions
                val outputFile = File(testDirPath, it.name)
                DownloadUtils.copyFile(
                    FileInputStream(it),
                    FileOutputStream(outputFile)
                )

                try {
                    println("Loading test library ${outputFile.name}")
                    System.load(outputFile.path)
                } catch (e: UnsatisfiedLinkError) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onCapabilityAdded(capability: String): Boolean {
        if (capability == GeodeUtils.CAPABILITY_EXTENDED_INPUT) {
            mGLSurfaceView?.useKeyboardEvents = true
            return true
        }

        return false
    }
}

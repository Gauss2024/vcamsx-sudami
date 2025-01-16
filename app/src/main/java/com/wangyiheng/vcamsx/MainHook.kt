package com.wangyiheng.vcamsx

import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.widget.Toast
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.data.models.VideoStatues
import com.wangyiheng.vcamsx.utils.InfoManager
import com.wangyiheng.vcamsx.utils.VideoToFrames
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.*


class MainHook : IXposedHookLoadPackage {

    private var c2_builder: CaptureRequest.Builder? = null
    val TAG = "vcamsx"
    var cameraCallbackClass: Class<*>? = null
    var hw_decode_obj: VideoToFrames? = null

    private var ijkMediaPlayer: IjkMediaPlayer? = null
    private var TheOnlyPlayer: IjkMediaPlayer? = null
    private var origin_preview_camera: Camera? = null
    private var fake_SurfaceTexture: SurfaceTexture? = null
    private var isplaying: Boolean = false
    private var videoStatus: VideoStatues? = null
    private var infoManager : InfoManager?= null
    private var context: Context? = null
    private var original_preview_Surface: Surface? = null

    private var original_c1_preview_SurfaceTexture:SurfaceTexture? = null

    var cameraOnpreviewframe: Camera? = null

    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null
    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }

        //获取context
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
            Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if (param!!.args[0] is Application) {
                        val application = param.args[0] as? Application ?: return
                        val applicationContext = application.applicationContext
                        if (context == applicationContext) return
                        try {
                            context = applicationContext
                            initStatus()
                            if(!lpparam.processName.contains(":")){
                                if(ijkMediaPlayer == null){
                                    if(videoStatus?.isLiveStreamingEnabled == true){
                                        initRTMPStream()
                                    }else if(videoStatus?.isVideoEnable == true){
                                        initIjkPlayer()
                                    }
                                }
                            }
                        } catch (ee: Exception) {
                            HLog.d("VCAMSX", "$ee")
                        }
                    }
                }
            })


        // 支持bilibili摄像头替换
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
            SurfaceTexture::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == null) {
                        return
                    }
                    if (param.args[0] == fake_SurfaceTexture) {
                        return
                    }
                    if (origin_preview_camera != null && origin_preview_camera == param.thisObject) {
                        param.args[0] = fake_SurfaceTexture
                        return
                    }

                    origin_preview_camera = param.thisObject as Camera
                    original_c1_preview_SurfaceTexture = param.args[0] as SurfaceTexture

                    fake_SurfaceTexture = if (fake_SurfaceTexture == null) {
                        SurfaceTexture(10)
                    } else {
                        fake_SurfaceTexture!!.release()
                        SurfaceTexture(10)
                    }
                    param.args[0] = fake_SurfaceTexture
                }
            })


        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if(ijkMediaPlayer == null || !ijkMediaPlayer!!.isPlayable){
                    if(videoStatus?.isLiveStreamingEnabled == true){
                        initRTMPStream()
                    }else if(videoStatus?.isVideoEnable == true){
                        initIjkPlayer()
                    }
                }
                TheOnlyPlayer = ijkMediaPlayer
                c1_camera_play()
            }
        })

//        XposedHelpers.findAndHookMethod(
//            "android.hardware.Camera",
//            lpparam.classLoader,
//            "setPreviewCallbackWithBuffer",
//            Camera.PreviewCallback::class.java,
//            object : XC_MethodHook() {
//                @Throws(Throwable::class)
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    if (param.args[0] != null) {
//                        process_callback(param)
//                    }
//                }
//            }
//        )

        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if(param.args[1] == null){
                            return
                        }
                        if(param.args[1] == c2_state_callback){
                            return
                        }
                        c2_state_callback = param.args[1] as CameraDevice.StateCallback

                        c2_state_callback_class = param.args[1]?.javaClass
                        process_camera2_init(c2_state_callback_class as Class<Any>?,lpparam)
                    }catch (e:Exception){
                        HLog.d("android.hardware.camera2.CameraManager报错了", "openCamera")
                    }
                }
            })
    }

//    private fun process_callback(param: XC_MethodHook.MethodHookParam) {
//        val previewCbClass = param.args[0].javaClass
//
//        XposedHelpers.findAndHookMethod(previewCbClass, "onPreviewFrame", ByteArray::class.java, Camera::class.java, object : XC_MethodHook() {
//            @Throws(Throwable::class)
//            override fun beforeHookedMethod(paramd: MethodHookParam) {
//
//                val localCam = paramd.args[1] as Camera
//                if (localCam == cameraOnpreviewframe) {
//                    System.arraycopy(data_buffer, 0, paramd.args[0], 0, Math.min(data_buffer.size, (paramd.args[0] as ByteArray).size))
//                } else {
//                    cameraCallbackClass = previewCbClass
//                    cameraOnpreviewframe = paramd.args[1] as Camera
//
//                    hw_decode_obj?.stopDecode()
//                    hw_decode_obj = VideoToFrames()
//                    hw_decode_obj!!.setSaveFrames(OutputImageFormat.NV21)
//                    hw_decode_obj!!.decode("/storage/emulated/0/Android/data/com.smile.gifmaker/files/Camera1/virtual.mp4")
//
//                    System.arraycopy(data_buffer, 0, paramd.args[0], 0, Math.min(data_buffer.size, (paramd.args[0] as ByteArray).size))
//                }
//            }
//        })
//    }


    fun initStatus(){
        infoManager = InfoManager(context!!)
        videoStatus = infoManager!!.getVideoStatus()
    }

    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                original_preview_Surface = null
            }
        })


        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            android.view.Surface::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] != null) {
                        if(param.args[0] == c2_virtual_surface)return
                        val surfaceInfo = param.args[0].toString()
                        if (!surfaceInfo.contains("Surface(name=null)")) {
                            if(original_preview_Surface != param.args[0] as Surface ){

                                original_preview_Surface = param.args[0] as Surface
                            }
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build",object :XC_MethodHook(){
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                if(param.thisObject != null && param.thisObject != c2_builder){
                    c2_builder = param.thisObject as CaptureRequest.Builder
                    if(ijkMediaPlayer == null || !ijkMediaPlayer!!.isPlayable){
                        if(videoStatus?.isLiveStreamingEnabled == true){
                            initRTMPStream()
                        }else if(videoStatus?.isVideoEnable == true){
                            initIjkPlayer()
                        }
                    }
                    TheOnlyPlayer = ijkMediaPlayer
                    process_camera_play()
                }
            }
        })

        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onDisconnected",CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                original_preview_Surface = null
            }
        })
    }

    fun process_camera_play() {
            ijkplay_play()
    }


    fun initIjkPlayer(){
        if(ijkMediaPlayer == null){
            ijkMediaPlayer = IjkMediaPlayer()
            //ijkMediaPlayer!!.setVolume(0F, 0F) // 设置音量为0
            // 设置解码方式为软解码
            if (videoStatus != null) {
                val codecType = videoStatus!!.codecType
                val mediaCodecOption = if (codecType) 1L else 0L // 将 Int 转换为 Long
                ijkMediaPlayer?.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", mediaCodecOption)
            }

            ijkMediaPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probsize", 4096)
            ijkMediaPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", 8192)
            ijkMediaPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
            ijkMediaPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0)
            ijkMediaPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2)

            ijkMediaPlayer!!.setOnPreparedListener {
                ijkMediaPlayer!!.isLooping= true
                if(original_preview_Surface != null){
                    ijkMediaPlayer!!.setSurface(original_preview_Surface)
                }
                ijkMediaPlayer!!.start()
            }


            ijkMediaPlayer!!.setOnCompletionListener {
                if(ijkMediaPlayer != TheOnlyPlayer){
                    ijkMediaPlayer!!.release()
                    ijkMediaPlayer = null
                }else{
                    playNextVideo()
                }
            }

            ijkMediaPlayer!!.setOnErrorListener { mp, what, extra ->
                ijkMediaPlayer!!.stop()
                true // 返回true表示已处理错误，返回false表示未处理错误
            }

            val videoUrl ="content://com.wangyiheng.vcamsx.videoprovider"
            ijkMediaPlayer!!.setDataSource(context, Uri.parse(videoUrl))
            ijkMediaPlayer!!.prepareAsync()
        }
    }

    fun initRTMPStream() {
        ijkMediaPlayer = IjkMediaPlayer().apply {
            try {
                // 硬件解码设置,0为软解，1为硬解
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)

                // 缓冲设置
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec_mpeg4", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "analyzemaxduration", 100L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "probesize", 1024L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "flush_packets", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)

                // 错误监听器
                setOnErrorListener { _, what, extra ->
                    Log.e("IjkMediaPlayer", "Error occurred. What: $what, Extra: $extra")
                    Toast.makeText(context, "直播接收失败$what", Toast.LENGTH_SHORT).show()
                    true
                }

                // 信息监听器
                setOnInfoListener { _, what, extra ->
                    Log.i("IjkMediaPlayer", "Info received. What: $what, Extra: $extra")
                    true
                }

                // 设置 RTMP 流的 URL
                dataSource = videoStatus!!.liveURL

                // 异步准备播放器
                prepareAsync()
                // 当播放器准备好后，开始播放
                setOnPreparedListener {
                    Log.d("vcamsx","onPrepared直播推流开始")
                    if(original_preview_Surface != null){
                        ijkMediaPlayer!!.setSurface(original_preview_Surface)
                    }
                    Toast.makeText(context, "直播接收成功，可以进行投屏", Toast.LENGTH_SHORT).show()
                    start()
                }
            } catch (e: Exception) {
                Log.d("vcamsx","$e")
            }
        }
    }


    private fun playNextVideo() {
        try {
            ijkMediaPlayer!!.reset()
            val videoUrl ="content://com.wangyiheng.vcamsx.videoprovider"
            ijkMediaPlayer!!.setDataSource(context, Uri.parse(videoUrl))
            ijkMediaPlayer!!.setSurface(original_preview_Surface)
            ijkMediaPlayer!!.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleMediaPlayer(surface: Surface) {
        try {
            initStatus()
            videoStatus?.let { status ->
               // val volume = if (status.isVideoEnable && status.volume) 1F else 0F
                ijkMediaPlayer?.setVolume(1F, 1F)
                if (status.isVideoEnable || status.isLiveStreamingEnabled) {
                    ijkMediaPlayer?.setSurface(surface)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun ijkplay_play() {
        original_preview_Surface?.let { surface ->
            handleMediaPlayer(surface)
        }
    }

    private fun c1_camera_play() {
        if (original_c1_preview_SurfaceTexture != null && videoStatus?.isVideoEnable == true) {
            original_preview_Surface = Surface(original_c1_preview_SurfaceTexture)
            if(original_preview_Surface!!.isValid == true){
                handleMediaPlayer(original_preview_Surface!!)
            }
        }
    }

    companion object {
        @Volatile
        var data_buffer = byteArrayOf(0)
    }
}


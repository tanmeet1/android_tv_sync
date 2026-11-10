package com.tvsync

import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.VideoView
import java.io.File

/**
 * Debug-only: loops a video with the platform hardware player, to check whether this TV routes
 * that codec/resolution through normal composition (capturable) or a hardware video plane.
 *
 * adb push clip.mp4 /sdcard/Android/data/com.tvsync/files/test.mp4
 * adb shell am start -n com.tvsync/.VideoTestActivity [--es view texture]
 *
 * The default renders into a SurfaceView (like most players); `--es view texture` renders into a
 * TextureView, which forces decoded frames through GPU composition.
 */
class VideoTestActivity : Activity() {
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(getExternalFilesDir(null), intent.getStringExtra("file") ?: "test.mp4")
        if (intent.getStringExtra("view") == "texture") playInTextureView(file) else playInVideoView(file)
    }

    private fun playInVideoView(file: File) {
        val video = VideoView(this)
        setContentView(video)
        video.setOnPreparedListener { it.isLooping = true }
        video.setVideoURI(Uri.fromFile(file))
        video.start()
    }

    private fun playInTextureView(file: File) {
        val texture = TextureView(this)
        setContentView(texture)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                player = MediaPlayer().apply {
                    setDataSource(file.path)
                    setSurface(Surface(st))
                    isLooping = true
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

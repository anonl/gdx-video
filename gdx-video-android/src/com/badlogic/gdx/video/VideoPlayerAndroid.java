/*******************************************************************************
 * Copyright 2014 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.video;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * Android implementation of the VideoPlayer class.
 *
 * @author Rob Bogie {@code <rob.bogie@codepoke.net>}
 */
public class VideoPlayerAndroid implements VideoPlayer, OnFrameAvailableListener {

    private static final Logger LOG = LoggerFactory.getLogger(VideoPlayerAndroid.class);

    private static final String ATTRIBUTE_TEXCOORDINATE = ShaderProgram.TEXCOORD_ATTRIBUTE + "0";
    private static final String VARYING_TEXCOORDINATE = "varTexCoordinate";
    private static final String UNIFORM_TEXTURE = "texture";
    private static final String UNIFORM_CAMERATRANSFORM = "camTransform";

	//@formatter:off
    private static final String VERTEX_SHADER_CODE =
        "attribute highp vec4 a_position; \n" +
		"attribute highp vec2 " + ATTRIBUTE_TEXCOORDINATE + ";" +
		"uniform highp mat4 " + UNIFORM_CAMERATRANSFORM + ";" +
		"varying highp vec2 " + VARYING_TEXCOORDINATE + ";" +
		"void main() \n" +
		"{ \n" +
		" gl_Position = " + UNIFORM_CAMERATRANSFORM + " * a_position; \n" +
		" varTexCoordinate = " + ATTRIBUTE_TEXCOORDINATE + ";\n" +
		"} \n";

    private static final String FRAGMENT_SHADER_CODE =
        "#extension GL_OES_EGL_image_external : require\n" +
		"uniform samplerExternalOES " + UNIFORM_TEXTURE + ";" +
		"varying highp vec2 " + VARYING_TEXCOORDINATE + ";" +
		"void main()                 \n" +
		"{                           \n" +
		"  gl_FragColor = texture2D(" + UNIFORM_TEXTURE + ", " + VARYING_TEXCOORDINATE + ");    \n" +
		"}";
    //@formatter:on

    private final ShaderProgram shader;
    private final int[] textures = new int[1];
    private final SurfaceTexture videoTexture;
    private final MediaPlayer player;

    private final VideoPlayerMesh mesh;
    private final Camera cam;

    private Viewport viewport;

    private VideoSizeListener sizeListener;
    private CompletionListener completionListener;

    private volatile boolean prepared = false;
    private volatile boolean done = false;
    private final AtomicBoolean frameAvailable = new AtomicBoolean();
    private float currentVolume = 1.0f;

    public VideoPlayerAndroid() {
        this(new FitViewport(Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
    }

    public VideoPlayerAndroid(Viewport viewport) {
        this(viewport.getCamera(), new VideoPlayerMesh());

        this.viewport = viewport;
    }

    public VideoPlayerAndroid(Camera cam, Mesh mesh, int primitiveType) {
        this(cam, VideoPlayerMesh.fromCustomMesh(mesh, primitiveType));
    }

    private VideoPlayerAndroid(Camera cam, VideoPlayerMesh mesh) {
        this.cam = cam;
        this.mesh = mesh;

        shader = new ShaderProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE);

        // Generate the actual texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);

        videoTexture = new SurfaceTexture(textures[0]);
        videoTexture.setOnFrameAvailableListener(this);

        player = new MediaPlayer();
    }

    @Override
    public boolean play(final FileHandle file) throws IOException {
        if (!file.exists()) {
            throw new FileNotFoundException("Could not find file: " + file);
        }

        player.reset();
        done = false;

        player.setOnPreparedListener(new OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                prepared = true;

                final int width = mp.getVideoWidth();
                final int height = mp.getVideoHeight();

                // set viewport world dimensions according to video dimensions and viewport type
                Gdx.app.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        mesh.setVideoSize(width, height);

                        // force viewport update to let scaling take effect
                        if (viewport != null) {
                            viewport.setWorldSize(width, height);
                            viewport.apply();
                        }
                    }
                });

                if (sizeListener != null) {
                    sizeListener.onVideoSize(width, height);
                }
                mp.start();
            }
        });

        player.setOnErrorListener(new OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                done = true;
                LOG.error("Video player error: {} {}", what, extra);
                return false;
            }
        });

        player.setOnCompletionListener(new OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                done = true;
                if (completionListener != null) {
                    completionListener.onCompletionListener(file);
                }
            }
        });

        if (file.type() == FileType.Classpath
                || (file.type() == FileType.Internal && !file.file().exists())) {
            AssetManager assets = ((AndroidApplication)Gdx.app).getAssets();
            AssetFileDescriptor descriptor = assets.openFd(file.path());
            player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(),
                    descriptor.getLength());
        } else {
            player.setDataSource(file.file().getAbsolutePath());
        }
        player.setSurface(new Surface(videoTexture));
        player.prepareAsync();

        return true;
    }

    @Override
    public void resize(int width, int height) {
        if (viewport != null) {
            viewport.update(width, height);
        }
    }

    @Override
    public boolean render() {
        if (done) {
            return false;
        }
        if (!prepared) {
            return true;
        }

        // Check if a new frame is available, and if so atomically set the flag to false
        if (frameAvailable.compareAndSet(true, false)) {
            videoTexture.updateTexImage();
        }

        // Draw texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        shader.begin();
        shader.setUniformMatrix(UNIFORM_CAMERATRANSFORM, cam.combined);
        shader.setUniformi(UNIFORM_TEXTURE, 0);
        mesh.render(shader);
        shader.end();

        return !done;
    }

    /**
     * For android, this will return whether the prepareAsync method of the android MediaPlayer is done with
     * preparing.
     *
     * @return whether the buffer is filled.
     */
    @Override
    public boolean isBuffered() {
        return prepared;
    }

    @Override
    public void stop() {
        if (prepared) {
            player.stop();
        }
        prepared = false;
        done = true;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        frameAvailable.set(true);
    }

    @Override
    public void pause() {
        // If it is running
        if (prepared) {
            player.pause();
        }
    }

    @Override
    public void resume() {
        // If it is running
        if (prepared) {
            player.start();
        }
    }

    @Override
    public void dispose() {
        stop();

        if (player != null) {
            player.release();
        }

        videoTexture.detachFromGLContext();

        GLES20.glDeleteTextures(1, textures, 0);
        textures[0] = 0;

        if (shader != null) {
            shader.dispose();
        }

        if (mesh != null) {
            mesh.dispose();
        }
    }

    @Override
    public void setOnVideoSizeListener(VideoSizeListener listener) {
        sizeListener = listener;
    }

    @Override
    public void setOnCompletionListener(CompletionListener listener) {
        completionListener = listener;
    }

    @Override
    public int getVideoWidth() {
        if (!prepared) {
            throw new IllegalStateException("Can't get width when video is not yet buffered!");
        }
        return player.getVideoWidth();
    }

    @Override
    public int getVideoHeight() {
        if (!prepared) {
            throw new IllegalStateException("Can't get height when video is not yet buffered!");
        }
        return player.getVideoHeight();
    }

    @Override
    public boolean isPlaying() {
        // This used to return false between prepare and play, but that makes the result pretty much useless.
        // In VideoPlayerDesktop, the isPlaying() method returns true from the moment play() is called up to
        // the moment the video finished or is stopped. Let's just do that instead.
        return !done;
    }

    @Override
    public void setVolume(float volume) {
        currentVolume = volume;
        player.setVolume(volume, volume);
    }

    @Override
    public float getVolume() {
        return currentVolume;
    }

}

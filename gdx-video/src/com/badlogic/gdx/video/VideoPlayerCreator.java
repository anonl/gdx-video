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

import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * This class is used to provide a way of creating a VideoPlayer, without knowing the platform the program is
 * running on. This has to be extended for each supported platform.
 *
 * @author Rob Bogie {@code <rob.bogie@codepoke.net>}
 */
public final class VideoPlayerCreator {

    /** Cached platform-specific video player factory instance */
    private static VideoPlayerFactory videoPlayerFactory;

    private VideoPlayerCreator() {
    }

    /**
     * @see VideoPlayerFactory#createVideoPlayer()
     */
    public static VideoPlayer createVideoPlayer() throws VideoPlayerInitException {
        return initFactory().createVideoPlayer();
    }

    /**
     * @see VideoPlayerFactory#createVideoPlayer(Viewport)
     */
    public static VideoPlayer createVideoPlayer(Viewport viewport) throws VideoPlayerInitException {
        return initFactory().createVideoPlayer(viewport);
    }

    /**
     * @see VideoPlayerFactory#createVideoPlayer(Camera, Mesh, int)
     */
    public static VideoPlayer createVideoPlayer(Camera cam, Mesh mesh, int primitiveType)
            throws VideoPlayerInitException {
        return initFactory().createVideoPlayer(cam, mesh, primitiveType);
    }

    private static synchronized VideoPlayerFactory initFactory() throws VideoPlayerInitException {
        if (videoPlayerFactory == null) {
            ApplicationType appType = Gdx.app.getType();

            String factoryClassName;
            switch (appType) {
            case Android:
                factoryClassName = "com.badlogic.gdx.video.AndroidVideoPlayerFactory";
                break;
            case Desktop:
                factoryClassName = "com.badlogic.gdx.video.DesktopVideoPlayerFactory";
                break;
            default:
                throw new VideoPlayerInitException(
                        "Platform is not supported by the Gdx Video Extension: " + appType);
            }

            try {
                videoPlayerFactory = (VideoPlayerFactory)Class.forName(factoryClassName).newInstance();
            } catch (Exception e) {
                throw new VideoPlayerInitException(
                        "Unable to instantiate video player factory: " + factoryClassName, e);
            }
        }
        return videoPlayerFactory;
    }
}

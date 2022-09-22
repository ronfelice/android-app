/*
 * Copyright (c) 2022. Proton AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.protonvpn.android

import android.content.Context
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.load.resource.drawable.DrawableResource
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder
import com.bumptech.glide.module.AppGlideModule
import java.io.IOException
import java.io.InputStream

class StreamLottieDecoder : ResourceDecoder<InputStream, LottieDrawable> {
    override fun handles(source: InputStream, options: Options): Boolean = true

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<LottieDrawable> {
        try {
            return LottieDrawableResource(decodeLottieDrawable(source))
        } catch (e : Throwable) {
            throw IOException("Unable to load lottie resource", e)
        }
    }

    private fun decodeLottieDrawable(source: InputStream): LottieDrawable {
        val result = LottieCompositionFactory.fromJsonInputStreamSync(source, null)
        if (result.value != null) {
            return LottieDrawable().apply {
                composition = result.value
                repeatCount = LottieDrawable.INFINITE
            }
        } else {
            throw result.exception!!
        }
    }
}

class LottieDrawableResource(drawable: LottieDrawable?) : DrawableResource<LottieDrawable>(drawable) {
    override fun getResourceClass(): Class<LottieDrawable> = LottieDrawable::class.java
    override fun getSize(): Int = 1 // We don't know the byte size of a LottieDrawable in memory.
    override fun recycle() = Unit
}

@GlideModule
class ProtonVpnGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry
            .append(InputStream::class.java, LottieDrawable::class.java, StreamLottieDecoder())
    }
}
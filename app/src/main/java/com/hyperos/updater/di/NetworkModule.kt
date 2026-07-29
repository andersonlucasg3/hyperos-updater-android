package com.hyperos.updater.di

import android.content.Context
import com.hyperos.updater.data.remote.OtaApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // No callTimeout is set intentionally. callTimeout applies to the ENTIRE call
    // (including body streaming), and DownloadUpdateUseCase streams large APKs
    // through this shared client — a 180 MB APK on a slow network could exceed
    // a global callTimeout. The per-read-gap readTimeout (15 s) is safe for
    // downloads while still preventing hung scrapes from stalling forever.
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            // Add Referer+Origin for APKPure CDN to bypass Cloudflare
            if (original.url.host.contains("apkpure.com") || original.url.host.contains("d.apkpure.com")) {
                builder.header("Referer", "https://apkpure.com/")
                builder.header("Origin", "https://apkpure.com")
            }
            chain.proceed(builder.build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .connectionPool(ConnectionPool(maxIdleConnections = 32, keepAliveDuration = 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .cache(try {
            Cache(File(context.cacheDir, "http_cache"), 20L * 1024 * 1024)
        } catch (e: Exception) {
            android.util.Log.w("NetworkModule", "Cannot create HTTP cache: ${e.message}")
            null
        })
        .build()

    @Provides
    @Singleton
    fun provideOtaApi(okHttpClient: OkHttpClient, moshi: Moshi): OtaApi =
        Retrofit.Builder()
            .baseUrl("https://update.miui.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OtaApi::class.java)
}

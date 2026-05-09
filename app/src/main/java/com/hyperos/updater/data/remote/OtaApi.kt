package com.hyperos.updater.data.remote

import com.hyperos.updater.data.remote.dto.OtaResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface OtaApi {

    @FormUrlEncoded
    @POST("updates/miotaV3.php")
    suspend fun checkUpdateLegacy(
        @Field("d") codename: String,
        @Field("b") branch: String,
        @Field("c") region: String,
        @Field("v") currentVersion: String,
        @Field("is_global") isGlobal: Int,
        @Field("r") regionCode: String,
        @Field("pn") productName: String,
        @Field("android") androidVersion: Int,
        @Field("sdk") sdkVersion: Int,
        @Field("is_signed") isSigned: Int = 0
    ): OtaResponse

    @GET("updates/miotaV3.php")
    suspend fun checkUpdateLegacyGet(
        @Query("d") codename: String,
        @Query("b") branch: String,
        @Query("c") region: String,
        @Query("v") currentVersion: String,
        @Query("is_global") isGlobal: Int,
        @Query("r") regionCode: String,
        @Query("pn") productName: String,
        @Query("android") androidVersion: Int,
        @Query("sdk") sdkVersion: Int,
        @Query("is_signed") isSigned: Int = 0
    ): OtaResponse
}

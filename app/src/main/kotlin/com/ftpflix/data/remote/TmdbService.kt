package com.ftpflix.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbService {

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("year") year: Int? = null,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
    ): TmdbPaged<TmdbMovie>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int? = null,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1,
    ): TmdbPaged<TmdbTv>

    @GET("movie/{id}")
    suspend fun movieDetails(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") append: String = "credits,videos,images,external_ids,release_dates",
    ): TmdbMovieDetails

    @GET("movie/{id}/external_ids")
    suspend fun movieExternalIds(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
    ): TmdbExternalIds

    @GET("tv/{id}")
    suspend fun tvDetails(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") append: String = "credits,videos,images,external_ids,content_ratings",
    ): TmdbTvDetails

    @GET("tv/{id}/external_ids")
    suspend fun tvExternalIds(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
    ): TmdbExternalIds

    @GET("movie/{id}/release_dates")
    suspend fun movieReleaseDates(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
    ): TmdbReleaseDates

    @GET("tv/{id}/content_ratings")
    suspend fun tvContentRatings(
        @Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
    ): TmdbContentRatings

    @GET("trending/{kind}/week")
    suspend fun trending(
        @Path("kind") kind: String,
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
    ): TmdbPaged<TmdbMovie>

    @GET("discover/{kind}")
    suspend fun discover(
        @Path("kind") kind: String,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("query") textQuery: String? = null,
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US",
    ): TmdbPaged<TmdbMovie>

    @GET("tv/{id}/season/{season}")
    suspend fun tvSeasonDetails(
        @Path("id") id: Long,
        @Path("season") season: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
    ): TmdbSeasonDetails

    @GET("genre/movie/list")
    suspend fun movieGenres(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
    ): TmdbGenreList

    @GET("genre/tv/list")
    suspend fun tvGenres(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
    ): TmdbGenreList
}

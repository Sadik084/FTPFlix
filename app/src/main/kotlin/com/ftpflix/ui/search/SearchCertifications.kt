package com.ftpflix.ui.search

/**
 * Age-rating options per certification country.
 *
 * TMDB stores certifications per country, so both the offered values and the
 * values sent to the API depend on the selected country.
 */
object SearchCertifications {

    data class Country(
        val code: String,
        val label: String,
        val movie: List<String>,
        val tv: List<String>,
    )

    val COUNTRIES: List<Country> = listOf(
        Country(
            code = "US",
            label = "United States",
            movie = listOf("G", "PG", "PG-13", "R", "NC-17"),
            tv = listOf("TV-Y", "TV-G", "TV-PG", "TV-14", "TV-MA"),
        ),
        Country(
            code = "GB",
            label = "United Kingdom",
            movie = listOf("U", "PG", "12", "15", "18"),
            tv = listOf("U", "PG", "12", "15", "18"),
        ),
        Country(
            code = "CA",
            label = "Canada",
            movie = listOf("G", "PG", "PG-13", "R", "18A"),
            tv = listOf("G", "PG", "14A", "18A", "R"),
        ),
        Country(
            code = "AU",
            label = "Australia",
            movie = listOf("G", "PG", "M", "MA15+", "R18+"),
            tv = listOf("G", "PG", "M", "MA15+", "R18+"),
        ),
        Country(
            code = "IN",
            label = "India",
            movie = listOf("U", "U/A", "A", "S"),
            tv = listOf("U", "U/A", "A", "S"),
        ),
    )

    /** Country definition, falling back to the default (US). */
    fun country(code: String): Country = COUNTRIES.firstOrNull { it.code == code } ?: COUNTRIES.first()

    /** Certification values that apply to movies in the given country. */
    fun movieCerts(code: String): List<String> = country(code).movie

    /** Certification values that apply to series in the given country. */
    fun tvCerts(code: String): List<String> = country(code).tv
}

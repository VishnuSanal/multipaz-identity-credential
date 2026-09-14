package org.multipaz.testapp

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import kotlin.time.Instant

private val PHOTO_ID_AGE_THRESHOLDS = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)

class AgeCredentialProfile(
    private val birthDate: LocalDate,
) {
    fun createDataOverrides(issuedAt: Instant): CredentialDataOverrides {
        val issuanceDate = issuedAt.toLocalDateTime(TimeZone.UTC).date
        val ageInYears = calculateAgeInYears(issuanceDate)
        val ageOver18 = ageInYears >= 18
        val ageOver21 = ageInYears >= 21

        val photoIdAgeValues = buildMap {
            put("birth_date", buildCborMap {
                put("birth_date", birthDate.toDataItemFullDate())
            })
            put("age_in_years", ageInYears.toDataItem())
            put("age_birth_year", birthDate.year.toDataItem())
            for (threshold in PHOTO_ID_AGE_THRESHOLDS) {
                put(
                    "age_over_${threshold.toString().padStart(2, '0')}",
                    (ageInYears >= threshold).toDataItem()
                )
            }
        }

        val euPidAgeValues = mapOf(
            "birth_date" to birthDate.toDataItemFullDate(),
            "age_in_years" to ageInYears.toDataItem(),
            "age_birth_year" to birthDate.year.toDataItem(),
            "age_over_18" to ageOver18.toDataItem(),
            "age_over_21" to ageOver21.toDataItem(),
        )

        return CredentialDataOverrides(
            mdocValues = mapOf(
                PhotoID.ISO_23220_2_NAMESPACE to photoIdAgeValues,
                EUPersonalID.EUPID_NAMESPACE to euPidAgeValues,
            ),
            jsonValues = mapOf(
                "birthdate" to JsonPrimitive(birthDate.toString()),
                "age_in_years" to JsonPrimitive(ageInYears),
                "age_birth_year" to JsonPrimitive(birthDate.year),
                "age_equal_or_over" to buildJsonObject {
                    put("18", JsonPrimitive(ageOver18))
                    put("21", JsonPrimitive(ageOver21))
                },
            ),
        )
    }

    private fun calculateAgeInYears(date: LocalDate): Int {
        require(birthDate <= date) { "Birth date must not be after the credential issuance date" }
        return date.year - birthDate.year - if (
            date.month.number < birthDate.month.number ||
            (date.month.number == birthDate.month.number && date.day < birthDate.day)
        ) {
            1
        } else {
            0
        }
    }
}

data class CredentialDataOverrides(
    val mdocValues: Map<String, Map<String, DataItem>>,
    val jsonValues: Map<String, JsonElement>,
)
package org.multipaz.presentment

import org.multipaz.document.Document
import org.multipaz.util.Logger
import org.multipaz.util.generateAllPaths

/**
 * An object containing data related to a credential presentment event.
 */
interface CredentialPresentmentData {
    /**
     * A list of credential sets which can be presented. Contains at least one set but may contain more.
     */
    val credentialSets: List<CredentialPresentmentSet>

    /**
     * Consolidates matches from several options and members into one.
     *
     * Applications can use this when constructing user interfaces for conveying various
     * options to the end user.
     *
     * For example, for a relying party which requests an identity document (say, mDL, PID or PhotoID)
     * and a transportation ticket (say, airline boarding pass or train ticket) the resulting
     * [CredentialPresentmentData] after executing the request would be:
     * ```
     *   CredentialSet
     *     Option
     *       Member
     *         Match: mDL
     *     Option
     *       Member
     *         Match: PID
     *     Option
     *       Member
     *         Match: PhotoID
     *         Match: PhotoID #2
     *   CredentialSet
     *     Option
     *       Member
     *         Match: Boarding Pass BOS -> ERW
     *     Option
     *       Member
     *         Match: Train Ticket Providence -> New York Penn Station
     * ```
     * This function consolidates options, members, and matches like so
     * ```
     *   CredentialSet
     *     Option
     *       Member
     *         Match: mDL
     *         Match: PID
     *         Match: PhotoID
     *         Match: PhotoID #2
     *   CredentialSet
     *     Option
     *       Member
     *         Match: Boarding Pass BOS -> SFO
     *         Match: Train Ticket Providence -> New York Penn Station
     * ```
     * which - depending on how the application constructs its user interface - may give the user
     * a simpler user interface for deciding which credentials to return.
     *
     * @return a [CredentialPresentmentData] with options, members, and matches consolidated.
     */
    fun consolidate(): CredentialPresentmentData

    /**
     * Selects a particular combination of credentials to select.
     *
     * If [preselectedDocuments] is empty, this picks the first option, member, and match.
     *
     * Otherwise if [preselectedDocuments] is not empty, the options, members, and matches are
     * selected such that the list of returned credentials match the documents in [preselectedDocuments].
     * If this isn't possible, the selection returned will be the same as if [preselectedDocuments]
     * was the empty list.
     *
     * @param preselectedDocuments either empty or a list of documents the user already selected.
     * @return a [CredentialPresentmentSelection].
     */
    fun select(preselectedDocuments: List<Document>): CredentialPresentmentSelection

    fun generateCombinations(preselectedDocuments: List<Document>): List<Combination> {

        val combinations = mutableListOf<Combination>()

        // First consolidate all single-member options into one...
        val consolidated = consolidate()

        // ...then explode all combinations
        val credentialSetsMaxPath = mutableListOf<Int>()
        consolidated.credentialSets.forEachIndexed { n, credentialSet ->
            // If a credentialSet is optional, it's an extra combination we tag at the end
            credentialSetsMaxPath.add(credentialSet.options.size + (if (credentialSet.optional) 1 else 0))
        }

        for (path in credentialSetsMaxPath.generateAllPaths()) {
            val elements = mutableListOf<CombinationElement>()
            consolidated.credentialSets.forEachIndexed { credentialSetNum, credentialSet ->
                val omitCredentialSet = (path[credentialSetNum] == credentialSet.options.size)
                if (omitCredentialSet) {
                    check(credentialSet.optional)
                } else {
                    val option = credentialSet.options[path[credentialSetNum]]
                    for (member in option.members) {
                        elements.add(
                            CombinationElement(
                                matches = member.matches
                            )
                        )
                    }
                }
            }
            combinations.add(
                Combination(
                    elements = elements
                )
            )
        }

        if (preselectedDocuments.size == 0) {
            return combinations
        }

        val setOfPreselectedDocuments = preselectedDocuments.toSet()
        combinations.forEach { combination ->
            if (combination.elements.size == preselectedDocuments.size) {
                val chosenElements = mutableListOf<CombinationElement>()
                combination.elements.forEachIndexed { n, element ->
                    val match =
                        element.matches.find { setOfPreselectedDocuments.contains(it.credential.document) }
                    if (match == null) {
                        return@forEach
                    }
                    chosenElements.add(CombinationElement(matches = listOf(match)))
                }
                // Winner, winner, chicken dinner!
                return listOf(Combination(elements = chosenElements))
            }
        }
        Logger.w(
            "CredentialPresentmentData",
            "Error picking combination for pre-selected documents"
        )
        return combinations
    }
}

public data class CombinationElement(
    val matches: List<CredentialPresentmentSetOptionMemberMatch>
)

public data class Combination(
    val elements: List<CombinationElement>
)
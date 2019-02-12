package net.corda.cordan.state

import net.corda.core.contracts.ContractState
import net.corda.core.identity.Party

/**
 * This is where you'll add the definition of your state object. Look at the unit tests in [IOUStateTests] for
 * instructions on how to complete the [IOUState] class.
 *
 * Remove the "val data: String = "data" property before starting the [IOUState] tasks.
 */
data class IOUState(val data: String = "data"): ContractState {
    override val participants: List<Party> get() = listOf()
}
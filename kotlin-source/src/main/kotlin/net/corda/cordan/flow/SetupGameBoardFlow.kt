package net.corda.cordan.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.cordan.contract.GameStateContract
import net.corda.cordan.contract.TurnTrackerContract
import net.corda.cordan.state.HexTile
import net.corda.cordan.state.PortTile
import net.corda.cordan.state.*
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import kotlin.math.roundToInt

// *********
// * Game Start Flow *
// *********
@InitiatingFlow(version = 1)
@StartableByRPC
class SetupGameStartFlow(val p1: Party, val p2: Party, val p3: Party, val p4: Party) : FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {
        // Step 1. Get a reference to the notary service on the network
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        // Step 2. Create a new transaction builder
        val tb = TransactionBuilder(notary)

        // Step 3. Create a new issue command and add it to the transaction.
        val issueCommand = Command(GameStateContract.Commands.SetUpGameBoard(), listOf(p1.owningKey, p2.owningKey, p3.owningKey, p4.owningKey))
        val createTurnTracker = Command(TurnTrackerContract.Commands.CreateTurnTracker(), listOf(p1.owningKey, p2.owningKey, p3.owningKey, p4.owningKey))
        tb.addCommand(issueCommand)
        tb.addCommand(createTurnTracker)

        // Step 4. Generate data for new game state

        // Storage for hexTiles that will be randomly generated.
        val hexTiles = arrayListOf<HexTile>()

        // Available Terrain types which will determine resources generated by hex tiles.
        val terrainTypes: Array<String> = arrayOf("Forest", "Pasture", "Field", "Hill", "Mountain", "Desert")

        /**
         * Ports in Settlers of Cordan enable users to exchange resources at more favourable rates than those available to players generally.
         * To access a port, a player must have previously built a settlement on a hex tile with an adjacent port. The settlement must also
         * be built on one of the designated access point specified below.
         */

        // Available Port Tiles
        val portTilesTracking = BooleanArray(9)
        val portTiles: ArrayList<PortTile> = arrayListOf(
                PortTile(Amount(2, Wood())),
                PortTile(Amount(2, Ore())),
                PortTile(Amount(2, Wheat())),
                PortTile(Amount(3, GameCurrency())),
                PortTile(Amount(2, Sheep())),
                PortTile(Amount(2, Brick())),
                PortTile(Amount(3, GameCurrency())),
                PortTile(Amount(3, GameCurrency())),
                PortTile(Amount(3, GameCurrency()))
        )

        val portHexTileAccessPointMapping = arrayListOf(
                listOf(AccessPoint(0, listOf(5, 1))),
                listOf(AccessPoint(1, listOf(0, 2)), AccessPoint(2, listOf(5))),
                listOf(AccessPoint(2, listOf(2)), AccessPoint(6, listOf(0, 1))),
                listOf(AccessPoint(11, listOf(1, 2))),
                listOf(AccessPoint(15, listOf(2, 3)), AccessPoint(18, listOf(1))),
                listOf(AccessPoint(18, listOf(4)), AccessPoint(17, listOf(2, 3))),
                listOf(AccessPoint(16, listOf(3, 4))),
                listOf(AccessPoint(12, listOf(4, 5)), AccessPoint(7, listOf(3))),
                listOf(AccessPoint(3, listOf(4, 5)), AccessPoint(7, listOf(0)))
        )

        val ports: ArrayList<Port> = arrayListOf()

        for (i in 0..8) {
            var currPortTileIndex = Math.floor(Math.random() * (portTiles.size)).toInt()
            while (portTilesTracking[currPortTileIndex]) {
                currPortTileIndex = Math.floor(Math.random() * (portTiles.size)).toInt()
            }

            val currPortTile = portTiles[currPortTileIndex]
            ports.add(i, Port(currPortTile, portHexTileAccessPointMapping[i]))
        }

        /**
         * Role trigger tiles are placed on hexTiles to denote the dice role that gives the player the right to harvest a resource on a given turn.
         * These are placed in counter-clockwise order, start from the top left corner of the game board.
         */
        val roleTriggerTilePlacementMapping: Map<Int, Int> = mapOf(
                0 to 5, 1 to 2, 2 to 6, 3 to 3, 4 to 8, 5 to 10, 6 to 9, 7 to 12, 8 to 11, 9 to 4,
                10 to 8, 11 to 10, 12 to 9, 13 to 4, 14 to 5, 15 to 6, 16 to 3, 17 to 11
        )

        /**
         * Order in which rollTriggerTiles are placed on the existing hex tiles (translating counterclockwise placement to row-by-row hexTile ordering)
         */
        val roleTriggerTilePlacementOrder = arrayListOf(0, 11, 10, 1, 12, 17, 9, 2, 13, 18, 16, 8, 3, 14, 15, 7, 4, 5, 6)

        // Array with maximums for a specific type of HexTile that may be added to the game board.
        val checkArray = intArrayOf(4, 4, 4, 3, 3, 1)

        // Array with counters for specific types of HexTiles added to the game board.
        val countArray = intArrayOf(0, 0, 0, 0, 0, 0)

        // Index adjustment variable to account desert placement
        var desertSkippedIndexAdjustment = 0

        for (i in 0..18) {

            // Get a random index between 0 and 5 which will be used to access the HexTileTypes.
            var hexTypeIndex = (Math.random() * 6).toInt()

            // Check to ensure HexTiles selected so far do not exceed max of each type specified in checkArray.
            while (countArray[hexTypeIndex] >= checkArray[hexTypeIndex]) {
                hexTypeIndex = (Math.random() * 6).toInt()
            }

            // Get the hex resource type.
            val hexType = terrainTypes[hexTypeIndex]

            // Get the port (if relevant) to add to the HexTile


            // Create a HexTile to add to the gameboard.
            // Use role trigger placement mapping, role trigger placement order, and desertSkippedIndexAdjustment to ensure that role triggers
            // Are placed in the appropriate order.
            hexTiles.add(i, HexTile(
                    hexType,
                    if (hexType.equals("Desert")) 0 else roleTriggerTilePlacementMapping.getOrElse(roleTriggerTilePlacementOrder[i - desertSkippedIndexAdjustment]) { 0 },
                    terrainTypes[hexTypeIndex] == "Desert"
            )
            )
            countArray[hexTypeIndex]++

            // Establish the index adjustment once a desert HexTile has been encountered.
            if (hexType.equals("Desert")) {
                desertSkippedIndexAdjustment = 1
            }
        }

        /**
         * Define the neighbouring hexTiles for each individual hexTile. In essence, we are creating a fully-connected graph modelling the state of the game board, this is necessary for a number of reasons,
         * including checking for valid placement of new roads and structures without forcing the user to provide unnecessarily specific input.
         */

        for (i in 0..2) {
            hexTiles[i].connect(3, hexTiles[ i + 3])
            hexTiles[i].connect(2, hexTiles[ i + 4])
            if (i != 2) hexTiles[i].connect(1, hexTiles[ i + 1])
        }

        for (i in 3..6) {
            hexTiles[i].connect(3, hexTiles[ i + 4])
            hexTiles[i].connect(2, hexTiles[ i + 5])
            if (i != 6) hexTiles[i].connect(1, hexTiles[ i + 1])
        }

        for (i in 12..15) {
            hexTiles[i].connect(5, hexTiles[ i - 5])
            hexTiles[i].connect(0, hexTiles[ i - 4])
            if (i != 15) hexTiles[i].connect(1, hexTiles[ i + 1])
        }

        for (i in 16..18) {
            hexTiles[i].connect(5, hexTiles[ i - 4])
            hexTiles[i].connect(0, hexTiles[ i - 3])
            if (i != 18) hexTiles[i].connect(1, hexTiles[ i + 1])
        }

        // Step 5. Create a new game state
        // Randomize turn order
        val playersList = listOf(p1, p2, p3, p4)
        val randomizedPlayersList = arrayListOf<Party>()
        while (randomizedPlayersList.size < 4) {
            val randomNumber = (Math.random() * 3).roundToInt()
            if (!randomizedPlayersList.contains(playersList[randomNumber])) {
                randomizedPlayersList.add(playersList[randomNumber])
            }
            System.out.println("How many times am I running")
        }

        val newGameState = GameBoardState(false, hexTiles, ports, randomizedPlayersList)

        // Step 6. Create a new turn tracker state
        val turnTrackerState = TurnTrackerState(participants = newGameState.players)

        // Step 6. Add the states to the transaction
        tb.addOutputState(newGameState, GameStateContract.ID)
        tb.addOutputState(turnTrackerState, TurnTrackerContract.ID)

        // Step 7. Verify and sign the transaction
        tb.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(tb)

        // Step 8. Create a list of flows with the relevant participants
        val sessions = (newGameState.participants - ourIdentity).map { initiateFlow(it) }.toSet()

        // Step 9. Collect other signatures
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        // Step 10. Run the FinalityFlow
        return subFlow(FinalityFlow(stx, sessions))

    }
}

@InitiatedBy(SetupGameStartFlow::class)
class SetupGameStartFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                stx.verify(serviceHub, false)
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = counterpartySession, expectedTxId = txWeJustSignedId.id))
    }
}
package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.VerseProperties
import wotw.io.messages.protobuf.*
import wotw.server.bingo.coopStates
import wotw.server.bingo.multiStates
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.io.handleClientSocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.rezero
import wotw.server.util.then
import wotw.server.util.zerore

class MultiverseEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        post<UberStateUpdateMessage>("multiverses/{multiverse_id}/{player_id}/state") { message ->
            if (System.getenv("DEV").isNullOrBlank()) {
                throw BadRequestException("Only available in dev mode")
            }

            val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("")
            val playerId = call.parameters["player_id"]?.ifEmpty { null } ?: throw BadRequestException("")

            val result = newSuspendedTransaction {
                val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException()
                val world = World.find(multiverseId, playerId) ?: throw NotFoundException()
                val result = server.sync.aggregateState(world, message.uberId, message.value)
                multiverse.updateCompletions(world)
                result
            }

            server.sync.syncState(multiverseId, playerId, message.uberId, result)
            server.sync.syncMultiverseProgress(multiverseId)
            call.respond(HttpStatusCode.NoContent)
        }
        get("multiverses/{multiverse_id}/worlds/{world_id}") {
            val multiverseId =
                call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
            val worldId =
                call.parameters["world_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable WorldID")
            val (world, members) = newSuspendedTransaction {
                val multiverse =
                    Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                val world = multiverse.worlds.firstOrNull { it.id.value == worldId }
                    ?: throw NotFoundException("World does not exist!")
                world to world.members.map { UserInfo(it.id.value, it.name, it.avatarId) }
            }
            println(members)
            call.respond(WorldInfo(worldId, world.name, members))
        }
        get("multiverses/{multiverse_id}") {
            val multiverseId =
                call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
            call.respond(newSuspendedTransaction {
                val multiverse =
                    Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                multiverse.multiverseInfo
            })
        }
        authenticate(SESSION_AUTH, JWT_AUTH) {
            post("multiverses") {
                wotwPrincipal().require(Scope.MULTIVERSE_CREATE)
                val propsIn = call.receiveOrNull<VerseProperties>() ?: VerseProperties()
                val multiverse = newSuspendedTransaction {
                    Multiverse.new {
                        props = propsIn
                    }
                }
                call.respondText("${multiverse.id.value}", status = HttpStatusCode.Created)
            }
            post("multiverses/{multiverse_id}/{universe_id?}/worlds") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Unparsable MultiverseID")
                val universeId =
                    call.parameters["universe_id"]?.toLongOrNull()
                val multiverseInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.WORLD_CREATE)

                    val multiverse =
                        Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                    if (multiverse.spectators.contains(player)) {
                        throw ConflictException("You cannot join this multiverse because you are spectating")
                    }

                    multiverse.removePlayerFromWorlds(player)
                    val universe =
                        if (universeId != null) {
                            Universe.findById(universeId) ?: throw NotFoundException("Universe does not exist!")
                        } else {
                            val universe = Universe.new {
                                name = "${player.name}'s Universe"
                                this.multiverse = multiverse
                            }
                            GameState.new {
                                this.multiverse = multiverse
                                this.universe = universe
                            }
                            universe
                        }
                    World.new(universe, player)
                    multiverse.multiverseInfo
                }

                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.Created)
            }

            post("multiverses/{multiverse_id}/worlds/{world_id}") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Unparsable MultiverseID")
                val worldId =
                    call.parameters["world_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable WorldID")

                val multiverseInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.WORLD_JOIN)

                    val multiverse =
                        Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")

                    if (multiverse.spectators.contains(player)) {
                        throw ConflictException("You cannot join this multiverse because you are spectating")
                    }

                    multiverse.removePlayerFromWorlds(player)

                    val world = multiverse.worlds.firstOrNull { it.id.value == worldId }
                        ?: throw NotFoundException("World does not exist!")
                    world.members = SizedCollection(world.members + player)
                    multiverse.multiverseInfo
                }

                server.sync.aggregationStrategies.remove(multiverseId)
                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.OK)
            }

            post("multiverses/{multiverse_id}/spectate") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Unparsable MultiverseID")

                val (multiverseInfo, playerId) = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.MULTIVERSE_SPECTATE)

                    val multiverse =
                        Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                    multiverse.removePlayerFromWorlds(player)

                    if (!multiverse.spectators.contains(player)) {
                        multiverse.spectators = SizedCollection(multiverse.spectators + player)
                    }

                    multiverse.multiverseInfo to player.id.value
                }

                server.connections.setSpectating(multiverseInfo.id, playerId, true)
                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.Created)
            }

            webSocket("multiverse_sync/") {
                handleClientSocket {
                    var playerId = ""
                    var worldId = 0L

                    afterAuthenticated {
                        playerId = principalOrNull?.userId ?: return@afterAuthenticated this@webSocket.close(
                            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session active!")
                        )
                        principalOrNull?.hasScope(Scope.MULTIVERSE_CONNECT) ?: this@webSocket.close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                "You are not allowed to connect with these credentials!"
                            )
                        )

                        val (_worldId, multiverseId, worldName, worldMembers) = newSuspendedTransaction {
                            val world = WorldMemberShip.find {
                                WorldMemberships.playerId eq playerId
                            }.sortedByDescending { it.id.value }.firstOrNull()?.world

                            world?.id?.value then world?.universe?.id?.value then world?.name then world?.members?.map { it.name }
                        }

                        if (multiverseId == null || _worldId == null) {
                            return@afterAuthenticated this@webSocket.close(
                                CloseReason(CloseReason.Codes.NORMAL, "Player is not part of an active multiverse")
                            )
                        }

                        worldId = _worldId
                        server.connections.registerMultiverseConn(socketConnection, playerId, multiverseId)

                        val initData = newSuspendedTransaction {
                            World.findById(worldId)?.universe?.multiverse?.board?.goals?.flatMap { it.value.keys }
                                ?.map { UberId(it.first, it.second) }
                        }.orEmpty()
                        val multiverseProps = newSuspendedTransaction {
                            World.findById(worldId)?.universe?.multiverse?.props ?: VerseProperties()
                        }
                        val userName = newSuspendedTransaction {
                            User.find {
                                Users.id eq playerId
                            }.firstOrNull()?.name
                        } ?: "Mystery User"

                        val states = (if (multiverseProps.isMulti) multiStates() else emptyList())
                            .plus(if (multiverseProps.isCoop) coopStates() else emptyList())
                            .plus(initData)  // don't sync new data
                        socketConnection.sendMessage(InitGameSyncMessage(states.map {
                            UberId(zerore(it.group), zerore(it.state))
                        }))

                        var greeting = "$userName - Connected to multiverse $multiverseId"

                        if (worldName != null) {
                            greeting += "\nWorld: $worldName\n" + worldMembers?.joinToString()
                        }

                        socketConnection.sendMessage(PrintTextMessage(text = greeting, frames = 240, ypos = 3f))
                    }
                    onMessage(UberStateUpdateMessage::class) {
                        if (worldId != 0L && playerId.isNotEmpty()) {
                            updateUberState(worldId, playerId)
                        }
                    }
                    onMessage(UberStateBatchUpdateMessage::class) {
                        if (worldId != 0L && playerId.isNotEmpty()) {
                            updateUberStates(worldId, playerId)
                        }
                    }
                    onMessage(PlayerPositionMessage::class) {
                        server.connections
                    }
                }
            }
        }
    }

    private suspend fun UberStateUpdateMessage.updateUberState(worldId: Long, playerId: String) {
        val uberGroup = rezero(uberId.group)
        val uberState = rezero(uberId.state)
        val sentValue = rezero(value)
        val (result, multiverseId) = newSuspendedTransaction {
            logger.debug("($uberGroup, $uberState) -> $sentValue")
            val world = World.findById(worldId) ?: error("Inconsistent multiverse state")
            val result = server.sync.aggregateState(world, UberId(uberGroup, uberState), sentValue) to
                    world.universe.multiverse.id.value
            world.universe.multiverse.updateCompletions(world)
            result
        }
        val pc = server.connections.playerMultiverseConnections[playerId]!!
        if (pc.multiverseId != multiverseId) {
            server.connections.unregisterMultiverseConn(playerId)
            server.connections.registerMultiverseConn(pc.clientConnection, playerId, multiverseId)
        }
        server.sync.syncMultiverseProgress(multiverseId)
        server.sync.syncState(multiverseId, playerId, UberId(uberGroup, uberState), result)
    }

    private suspend fun UberStateBatchUpdateMessage.updateUberStates(worldId: Long, playerId: String) {
        val updates = updates.map {
            UberId(rezero(it.uberId.group), rezero(it.uberId.state)) to if (it.value == -1.0) 0.0 else it.value
        }.toMap()

        val (results, multiverseId) = newSuspendedTransaction {
            val world = World.findById(worldId) ?: error("Inconsistent multiverse state")
            val result = updates.mapValues { (uberId, value) ->
                server.sync.aggregateState(world, uberId, value)
            } to world.universe.multiverse.id.value
            world.universe.multiverse.updateCompletions(world)
            result
        }

        val pc = server.connections.playerMultiverseConnections[playerId]!!
        if (pc.multiverseId != multiverseId) {
            server.connections.unregisterMultiverseConn(playerId)
            server.connections.registerMultiverseConn(pc.clientConnection, playerId, multiverseId)
        }
        server.sync.syncMultiverseProgress(multiverseId)
        server.sync.syncStates(multiverseId, playerId, results)
    }
}